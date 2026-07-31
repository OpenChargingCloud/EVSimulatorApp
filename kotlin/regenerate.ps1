# Regenerates every checked-in Kotlin codec in place.
#
# The thing this script exists to get right: the ORDER of --xsd decides declaration order in the
# output, so the message set's own schema comes first. Passing a directory listing instead
# reorders everything while encoding the very same bytes.
# The fragment element lists mirror <ExiFragmentElements> in the matching C# project.
#
# --out is the package DIRECTORY: the Kotlin back end emits one file per type. The driver deletes
# generated files it no longer produces, so a renamed or dropped type does not leave a stale
# declaration behind; hand-written sources in the same directory are left alone.
#
# Run from the repository root:  pwsh kotlin/regenerate.ps1
# Regenerating without an emitter change must leave every file byte-identical.

$ErrorActionPreference = 'Stop'
$root  = Split-Path -Parent $PSScriptRoot
$proj  = Join-Path $root 'libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Codegen/Vanaheimr.V2G.Exi.Codegen.csproj'
$libs  = Join-Path $root 'libs/Vanaheimr.V2G.Exi'

dotnet build $proj -c Release -v q --nologo

function Generate($schemas, $outDir, $package, $codec, $fragments) {
    $args = @('--xsd', ($schemas -join ';'), '--out', $outDir,
              '--lang', 'kotlin', '--namespace', $package, '--codec', $codec)
    if ($fragments) { $args += @('--fragments', $fragments) }
    dotnet run --project $proj -c Release --no-build -- @args
}

# ---- ISO 15118 AppProtocol ----------------------------------------------------------------
Generate @("$libs/Vanaheimr.V2G.Exi.Prototype/Schemas/V2G_CI_AppProtocol.xsd") `
    "$root/kotlin/exi-appprotocol/src/main/kotlin/cloud/charging/v2g/appprotocol" `
    'cloud.charging.v2g.appprotocol' 'SupportedAppProtocolCodec' $null

# ---- ISO 15118-2 --------------------------------------------------------------------------
$s = "$libs/Vanaheimr.V2G.Exi.Iso15118_2/Schemas"
Generate @("$s/V2G_CI_MsgDef.xsd", "$s/V2G_CI_MsgBody.xsd", "$s/V2G_CI_MsgDataTypes.xsd",
           "$s/V2G_CI_MsgHeader.xsd", "$s/xmldsig-core-schema.xsd") `
    "$root/kotlin/exi-iso2/src/main/kotlin/cloud/charging/v2g/iso2" `
    'cloud.charging.v2g.iso2' 'Iso15118_2Codec' `
    'AuthorizationReq MeteringReceiptReq SalesTariff SignedInfo'

# ---- Standalone W3C XMLDSig ---------------------------------------------------------------
# Not a message set. This grammar exists only to reproduce the EXI fragment encoding of a
# SignedInfo built over xmldsig-core-schema.xsd *alone*, which is what Josev/EXIficient actually
# signs — distinct from the combined fragment grammar every other set here uses. Plug & Charge
# needs it; without it a port produces signatures that verify locally and nowhere else.
Generate @("$libs/Vanaheimr.V2G.Exi.XmlDsig/Schemas/xmldsig-core-schema.xsd") `
    "$root/kotlin/exi-xmldsig/src/main/kotlin/cloud/charging/v2g/xmldsig" `
    'cloud.charging.v2g.xmldsig' 'XmlDsigCodec' 'SignedInfo'

# ---- ISO 15118-20 -------------------------------------------------------------------------
$sets = @(
    @{ Name = 'CommonMessages'; Xsd = 'V2G_CI_CommonMessages.xsd'; Dir = 'common';   Codec = 'CommonMessagesCodec'
       Frag = 'AbsolutePriceSchedule CertificateInstallationReq MeteringConfirmationReq OEMProvisioningCertificateChain PnC_AReqAuthorizationMode SignedInstallationData SignedInfo' },
    @{ Name = 'AC';             Xsd = 'V2G_CI_AC.xsd';            Dir = 'ac';       Codec = 'ACCodec'
       Frag = 'AC_ChargeParameterDiscoveryRes SignedInfo' },
    @{ Name = 'DC';             Xsd = 'V2G_CI_DC.xsd';            Dir = 'dc';       Codec = 'DCCodec'
       Frag = 'DC_ChargeParameterDiscoveryRes SignedInfo' },
    @{ Name = 'WPT';            Xsd = 'V2G_CI_WPT.xsd';           Dir = 'wpt';      Codec = 'WPTCodec';       Frag = $null },
    @{ Name = 'ACDP';           Xsd = 'V2G_CI_ACDP.xsd';          Dir = 'acdp';     Codec = 'ACDPCodec';      Frag = $null },
    @{ Name = 'AC_DER_IEC';     Xsd = 'V2G_CI_AC_DER_IEC.xsd';    Dir = 'acderiec'; Codec = 'AcDerIecCodec'
       Frag = 'AC_ChargeParameterDiscoveryRes SignedInfo' },
    @{ Name = 'AC_DER_SAE';     Xsd = 'V2G_CI_AC_DER_SAE.xsd';    Dir = 'acdersae'; Codec = 'AcDerSaeCodec'
       Frag = 'AC_ChargeParameterDiscoveryRes SignedInfo' }
)

foreach ($set in $sets) {
    $s = "$libs/Vanaheimr.V2G.Exi.Iso15118_20.$($set.Name)/Schemas"
    # The DER sets layer their own schema on top of V2G_CI_AC.xsd; the others have just the two.
    $xsds = @("$s/$($set.Xsd)")
    if (Test-Path "$s/V2G_CI_AC.xsd") { if ($set.Xsd -ne 'V2G_CI_AC.xsd') { $xsds += "$s/V2G_CI_AC.xsd" } }
    $xsds += @("$s/V2G_CI_CommonTypes.xsd", "$s/xmldsig-core-schema.xsd")

    Generate $xsds `
        "$root/kotlin/exi-iso20-$($set.Dir)/src/main/kotlin/cloud/charging/v2g/iso20/$($set.Dir)" `
        "cloud.charging.v2g.iso20.$($set.Dir)" $set.Codec $set.Frag
}
