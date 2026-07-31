package cloud.charging.v2g.evcc

import com.google.gson.JsonParser
import java.io.File
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import cloud.charging.v2g.certificates.V2GCertificate
import cloud.charging.v2g.keystore.InMemoryP256Signer
import cloud.charging.v2g.keystore.V2GSigner
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec

/**
 * The fixed Plug & Charge identity a recorded session signs with, read out of the submodule — the
 * same file the C# and Swift suites read.
 *
 * Shared rather than duplicated for the reason the corpus exists at all: three copies of a constant
 * drift, and a drifted contract key would change the message body, not just the signature, so the
 * failure would look like a state-machine bug rather than a stale constant.
 */
internal object PncMaterial {

    private val material by lazy {
        var dir = File(".").absoluteFile
        while (!File(dir, "libs/Vanaheimr.V2G.Exi").isDirectory)
            dir = dir.parentFile ?: error("repository root not found")

        val file = File(dir, "libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Simulation.Tests/" +
                             "Vectors/Session.pnc-material.json")
        require(file.isFile) { "PnC material not found at $file" }
        JsonParser.parseString(file.readText()).asJsonObject
    }

    val certificate: ByteArray by lazy { hex(material.get("certificate").asString) }

    /** A certificate whose Common Name is 19 characters and therefore cannot be an eMAID — the
     *  negative case every back end's length check is held to. */
    val certificateWithUnusableEmaid: ByteArray by lazy {
        hex(material.get("certificateWithUnusableEmaid").asString)
    }

    val key: PrivateKey by lazy {
        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)

        KeyFactory.getInstance("EC").generatePrivate(
            ECPrivateKeySpec(BigInteger(1, hex(material.get("privateKeyD").asString)), parameters))
    }

    /** The corpus key as a signer. Software and in memory, and it says so — the same arrangement
     *  §3.4 asks for, working identically for test material and a real credential. */
    val signer: V2GSigner by lazy {
        InMemoryP256Signer(key, V2GCertificate(certificate).publicKeyDer)
    }

    val options: PncEvccOptions
        get() = PncEvccOptions(certificate, listOf(certificate), signer)

    private fun hex(s: String) = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
