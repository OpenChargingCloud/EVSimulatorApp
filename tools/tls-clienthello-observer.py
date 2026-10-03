#!/usr/bin/env python3
"""What a TLS client actually offers, read off its ClientHello.

Deliberately **not** a TLS server. A server would answer, and its own preferences and its own
library's support would filter what we got to see — which is the thing being measured. This listens
on a plain socket, reads the first record, parses the ClientHello and hangs up. Every client
therefore fails its handshake, which is expected and is not the result.

The result is the cipher-suite list, the offered TLS versions, the named groups, and whether
RFC 6066's `trusted_ca_keys` (extension 3, `[V2G2-651]`) is there at all.

Written for `docs/experiments/tls-platform-suites.md`, which is the measurement stage 4 of
`docs/mobile-workplan.md` asks for before any TLS code. Kept because that measurement has to be
repeatable: platform TLS stacks change under you, and a claim about one is only worth what its last
re-run says.

    python3 tools/tls-clienthello-observer.py <label> <port>

then point a client at that port. Prints one JSON report on stdout.
"""

import json
import socket
import struct
import sys

# ISO 15118's own suites first, then the ones a platform is likely to offer instead, so a report can
# say what *was* offered rather than only what was missing.
SUITES = {
    0xC023: "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",   # ISO 15118-2, optional
    0xC025: "TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256",    # ISO 15118-2, MANDATORY (static ECDH)
    0xC024: "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
    0xC026: "TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384",
    0x1301: "TLS_AES_128_GCM_SHA256",                    # ISO 15118-20
    0x1302: "TLS_AES_256_GCM_SHA384",                    # ISO 15118-20
    0x1303: "TLS_CHACHA20_POLY1305_SHA256",              # ISO 15118-20
    0xC02B: "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
    0xC02C: "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
    0xC02F: "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
    0xC030: "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
    0xCCA8: "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
    0xCCA9: "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
    0xC009: "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
    0xC00A: "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
    0xC013: "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
    0xC014: "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
    0xC027: "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
    0xC028: "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
    0x009C: "TLS_RSA_WITH_AES_128_GCM_SHA256",
    0x009D: "TLS_RSA_WITH_AES_256_GCM_SHA384",
    0x002F: "TLS_RSA_WITH_AES_128_CBC_SHA",
    0x0035: "TLS_RSA_WITH_AES_256_CBC_SHA",
    0x003C: "TLS_RSA_WITH_AES_128_CBC_SHA256",
    0x003D: "TLS_RSA_WITH_AES_256_CBC_SHA256",
    0x00FF: "TLS_EMPTY_RENEGOTIATION_INFO_SCSV",
    0x5600: "TLS_FALLBACK_SCSV",
}

GROUPS = {
    0x0017: "secp256r1", 0x0018: "secp384r1", 0x0019: "secp521r1",
    0x001D: "x25519", 0x001E: "x448", 0x001F: "brainpoolP256r1tls13",
    0x0100: "ffdhe2048", 0x0101: "ffdhe3072",
}

# What a TLS 1.3 client says it can verify. -20 signs with secp521r1/SHA-512, so its presence here
# is the difference between "a P-521 station certificate can be checked" and "it cannot".
SIGNATURE_ALGORITHMS = {
    0x0403: "ecdsa_secp256r1_sha256", 0x0503: "ecdsa_secp384r1_sha384",
    0x0603: "ecdsa_secp521r1_sha512", 0x0807: "ed25519", 0x0808: "ed448",
    0x0804: "rsa_pss_rsae_sha256", 0x0805: "rsa_pss_rsae_sha384", 0x0806: "rsa_pss_rsae_sha512",
    0x0401: "rsa_pkcs1_sha256", 0x0501: "rsa_pkcs1_sha384", 0x0601: "rsa_pkcs1_sha512",
    0x0201: "rsa_pkcs1_sha1", 0x0203: "ecdsa_sha1",
}

VERSIONS = {0x0300: "SSL3.0", 0x0301: "TLS1.0", 0x0302: "TLS1.1", 0x0303: "TLS1.2", 0x0304: "TLS1.3"}

EXTENSIONS = {
    0: "server_name", 3: "trusted_ca_keys", 5: "status_request", 10: "supported_groups",
    11: "ec_point_formats", 13: "signature_algorithms", 16: "alpn", 21: "padding",
    23: "extended_master_secret", 35: "session_ticket", 43: "supported_versions",
    45: "psk_key_exchange_modes", 51: "key_share", 65281: "renegotiation_info",
}


class Reader:
    def __init__(self, data):
        self.data, self.i = data, 0

    def take(self, n):
        if self.i + n > len(self.data):
            raise ValueError(f"truncated: wanted {n} at {self.i} of {len(self.data)}")
        out = self.data[self.i:self.i + n]
        self.i += n
        return out

    def u8(self):  return self.take(1)[0]
    def u16(self): return struct.unpack(">H", self.take(2))[0]
    def u24(self): return int.from_bytes(self.take(3), "big")


def parse_client_hello(record):
    r = Reader(record)

    if r.u8() != 0x16:
        raise ValueError("not a handshake record")
    record_version = r.u16()
    r.u16()                                  # record length

    if r.u8() != 0x01:
        raise ValueError("not a ClientHello")
    r.u24()                                  # handshake length

    legacy_version = r.u16()
    r.take(32)                               # random
    r.take(r.u8())                           # legacy session id

    suite_bytes = r.take(r.u16())
    suites = [struct.unpack(">H", suite_bytes[i:i + 2])[0] for i in range(0, len(suite_bytes), 2)]

    r.take(r.u8())                           # compression methods

    extensions, groups, versions, signatures = {}, [], [], []
    if r.i < len(r.data):
        end = r.i + r.u16()
        while r.i < end:
            kind, body = r.u16(), r.take(r.u16())
            extensions[kind] = body
            if kind == 10:
                inner = body[2:]
                groups = [struct.unpack(">H", inner[i:i + 2])[0] for i in range(0, len(inner), 2)]
            elif kind == 43:
                inner = body[1:]
                versions = [struct.unpack(">H", inner[i:i + 2])[0] for i in range(0, len(inner), 2)]
            elif kind == 13:
                inner = body[2:]
                signatures = [struct.unpack(">H", inner[i:i + 2])[0] for i in range(0, len(inner), 2)]

    def named(value, table):
        return table.get(value, f"0x{value:04x}")

    return {
        "recordVersion":   named(record_version, VERSIONS),
        "legacyVersion":   named(legacy_version, VERSIONS),
        # A TLS-1.2-only ClientHello carries no supported_versions at all; that absence is a result.
        "offeredVersions": [named(v, VERSIONS) for v in versions] or ["(no supported_versions)"],
        "cipherSuites":     [named(s, SUITES) for s in suites],
        "cipherSuiteCount": len(suites),
        "supportedGroups":  [named(g, GROUPS) for g in groups],
        "signatureAlgorithms": [named(a, SIGNATURE_ALGORITHMS) for a in signatures],
        "extensions":       sorted(EXTENSIONS.get(k, f"0x{k:04x}") for k in extensions),
        "iso15118": {
            "iso2Mandatory_TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256": 0xC025 in suites,
            "iso2Optional_TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256": 0xC023 in suites,
            "iso20_TLS_AES_256_GCM_SHA384":       0x1302 in suites,
            "iso20_TLS_CHACHA20_POLY1305_SHA256": 0x1303 in suites,
            "secp521r1Offered":       0x0019 in groups,
            "ecdsaSecp521r1Sha512":   0x0603 in signatures,
            "ed448Offered":           0x0808 in signatures,
            "trustedCaKeysSent": 3 in extensions,
        },
    }


def main():
    label = sys.argv[1] if len(sys.argv) > 1 else "client"
    port  = int(sys.argv[2]) if len(sys.argv) > 2 else 0

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("0.0.0.0", port))
    server.listen(1)
    print(f"listening on {server.getsockname()[1]}", file=sys.stderr, flush=True)

    server.settimeout(120)
    conn, peer = server.accept()
    conn.settimeout(10)

    data = b""
    while len(data) < 5:
        chunk = conn.recv(4096)
        if not chunk:
            break
        data += chunk
    if len(data) >= 5:
        want = 5 + struct.unpack(">H", data[3:5])[0]
        while len(data) < want:
            chunk = conn.recv(4096)
            if not chunk:
                break
            data += chunk
    conn.close()

    report = {"label": label, "peer": f"{peer[0]}:{peer[1]}", "bytes": len(data)}
    try:
        report.update(parse_client_hello(data))
    except Exception as error:                                   # noqa: BLE001
        report["error"] = f"{type(error).__name__}: {error}"
        report["raw"] = data[:64].hex()

    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
