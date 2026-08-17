#!/usr/bin/env bash
#
# Regenerates every checked-in TypeScript codec in place.
#
# The thing this script exists to get right: the ORDER of --xsd decides declaration order in the
# output, so the message set's own schema comes first. Passing a directory listing instead
# reorders everything while encoding the very same bytes.
# The fragment element lists mirror <ExiFragmentElements> in the matching C# project.
#
# --out is the module DIRECTORY: the TypeScript back end emits one file per type. The driver
# deletes generated files it no longer produces, so a renamed or dropped type does not leave a
# stale declaration behind; hand-written sources in the same directory are left alone.
#
# Run from anywhere:  bash typescript/regenerate.sh
# Regenerating without an emitter change must leave every file byte-identical.
#
# Paths below are relative, and the script cds to the repository root to make them so. That is not
# tidiness: under Git Bash on Windows, MSYS rewrites a lone POSIX path into Windows form before
# handing it to dotnet.exe but leaves a ';'-joined list of them alone, so an absolute --xsd list
# arrives as /c/Users/… and the driver cannot find a single schema. Relative paths have nothing to
# rewrite and behave the same on Linux, macOS, WSL and Git Bash.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

proj='tools/EVSimulatorApp.Codegen/EVSimulatorApp.Codegen.csproj'
# The schema sets live in the WWCP_ISO15118 submodule; only the port back ends are in this repo.
libs='libs/WWCP_ISO15118'
# The two wire-format switches, stated here rather than left to the tool's default.
#
# They mirror <ExiDocumentElementOrder> and <ExiParticleGrammar> in
# libs/WWCP_ISO15118/Directory.Build.props, which is what the C# codecs beside these are built with.
# Those two decisions -- "where cbexigen and ISO's schema disagree, follow the schema" -- were taken
# on 2026-08-08 and reached the C# side only, because this driver had no flag for either and
# defaults to the library's cbexigen-compatible behaviour. The ports emitted the old grammar for
# nine days and no gate could say so. Naming them here means a reader of this script can see which
# grammar produced the checked-in files, instead of having to know a default.
grammar='--doc-order ExiSorted --particles SchemaConformant'


dotnet build "$proj" -c Release -v q --nologo

# generate <out-dir> <namespace> <codec> <fragments|""> <xsd>...
generate() {
    local out="$1" namespace="$2" codec="$3" fragments="$4"; shift 4
    local xsds; xsds="$(IFS=';'; echo "$*")"

    if [ -n "$fragments" ]; then
        dotnet run --project "$proj" -c Release --no-build -- \
            --xsd "$xsds" --out "$out" --lang typescript \
            --namespace "$namespace" --codec "$codec" --fragments "$fragments" $grammar
    else
        dotnet run --project "$proj" -c Release --no-build -- \
            --xsd "$xsds" --out "$out" --lang typescript \
            --namespace "$namespace" --codec "$codec" $grammar
    fi
}

# ---- ISO 15118 AppProtocol ----------------------------------------------------------------
generate 'typescript/src/appprotocol' \
    'cloud.charging.v2g.appprotocol' 'SupportedAppProtocolCodec' '' \
    "$libs/WWCP_ISO15118_EXI/Schemas/V2G_CI_AppProtocol.xsd"

# ---- ISO 15118-2 --------------------------------------------------------------------------
s="$libs/WWCP_ISO15118_2/Schemas"
generate 'typescript/src/iso2' \
    'cloud.charging.v2g.iso2' 'Iso15118_2Codec' \
    'AuthorizationReq CertificateInstallationReq CertificateUpdateReq ContractSignatureCertChain ContractSignatureEncryptedPrivateKey DHpublickey eMAID MeteringReceiptReq SalesTariff SignedInfo' \
    "$s/V2G_CI_MsgDef.xsd" "$s/V2G_CI_MsgBody.xsd" "$s/V2G_CI_MsgDataTypes.xsd" \
    "$s/V2G_CI_MsgHeader.xsd" "$s/xmldsig-core-schema.xsd"

# ---- Standalone W3C XMLDSig ---------------------------------------------------------------
# Not a message set. This grammar exists only to reproduce the EXI fragment encoding of a
# SignedInfo built over xmldsig-core-schema.xsd *alone*, which is what Josev/EXIficient actually
# signs — distinct from the combined fragment grammar every other set here uses. Plug & Charge
# needs it; without it a port produces signatures that verify locally and nowhere else.
#
# The namespace is 'xmldsig', NOT Swift's 'ExiXmlDsig'. Same schema, same fragment, different
# name per language — and passing Swift's here is not hypothetical: it happened once and produced
# 26 files claiming the wrong schema target.
generate 'typescript/src/xmldsig' \
    'xmldsig' 'XmlDsigCodec' 'SignedInfo' \
    "$libs/WWCP_ISO15118_XMLDSig/Schemas/xmldsig-core-schema.xsd"

# ---- ISO 15118-20 -------------------------------------------------------------------------
# set | schema | module | namespace suffix | codec | fragments
sets=(
  'CommonMessages|V2G_CI_CommonMessages.xsd|iso20common|common|CommonMessagesCodec|AbsolutePriceSchedule CertificateInstallationReq MeteringConfirmationReq OEMProvisioningCertificateChain PnC_AReqAuthorizationMode SignedInstallationData SignedInfo'
  'AC|V2G_CI_AC.xsd|iso20ac|ac|ACCodec|AC_ChargeParameterDiscoveryRes SignedInfo'
  'DC|V2G_CI_DC.xsd|iso20dc|dc|DCCodec|DC_ChargeParameterDiscoveryRes SignedInfo'
)

for entry in "${sets[@]}"; do
    IFS='|' read -r name xsd module suffix codec frag <<< "$entry"
    s="$libs/WWCP_ISO15118_20.$name/Schemas"

    xsds=("$s/$xsd" "$s/V2G_CI_CommonTypes.xsd" "$s/xmldsig-core-schema.xsd")

    generate "typescript/src/$module" "cloud.charging.v2g.iso20.$suffix" "$codec" "$frag" "${xsds[@]}"
done

# WPT, ACDP and the two DER sets have no TypeScript port. The inspector this back end serves reads
# what a session actually carries, and none of them appear in one — see typescript/README.md.
