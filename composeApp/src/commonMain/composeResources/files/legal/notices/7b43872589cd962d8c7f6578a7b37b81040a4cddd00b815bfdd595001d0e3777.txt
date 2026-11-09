# Bundled public TLS roots

- Source: https://curl.se/ca/cacert.pem ; method: HTTPS download, 2026-11-06 UTC.
- Upstream header: Mozilla certificate data as of 2026-11-05 04:12:01 GMT.
- SHA-256: `427c064216761a1e72a8b30f0d954d08cb6e4435a82091fb865afd20c8497285`.
- Location: `composeApp/src/desktopMain/resources/dev/veyra/desktop/runtime/ca-bundle.crt`.
- Source/license information: https://curl.se/docs/caextract.html ; Mozilla NSS certdata uses MPL-2.0; license text is adjacent.

These are public CA certificates, not private keys. Deliberate reviewed updates must change both this record and the runtime's integrity pin. FFmpeg/libmpv trust does not automatically inherit the JVM truststore. Tests must establish trusted success and untrusted-certificate rejection. No verification-disable fallback is allowed.
