import NIOSSL

/// Compile-time checks, because "the API has this" is a claim like any other. Anything absent here
/// is a build error rather than a sentence in a document.
enum ApiCheck {

    /// Mutual TLS with our own material and **not** the platform trust store — the thing
    /// `Network.framework` cannot be told to do. That this compiles is the measurement.
    static func mutualTlsIsExpressible(chain: [NIOSSLCertificate],
                                       key:   NIOSSLPrivateKey,
                                       roots: [NIOSSLCertificate]) -> TLSConfiguration {

        var config = TLSConfiguration.makeClientConfiguration()
        config.certificateChain = chain.map { .certificate($0) }   // the Vehicle chain, -20 mutual TLS
        config.privateKey       = .privateKey(key)
        config.trustRoots       = .certificates(roots)             // a V2G root, not a web CA
        config.certificateVerification = .noHostnameVerification   // a SECC cert carries an EVSE id
        return config
    }

    // And what is NOT here. Uncommenting the line below fails the build with
    //
    //     error: type 'SignatureAlgorithm' has no member 'ed448'
    //
    // which is how we know -20's second signature suite is out of reach on this stack — a compile
    // error rather than a configuration gap.
    //
    //   static let ed448: SignatureAlgorithm = .ed448
    static let ed448IsAbsent = true
}
