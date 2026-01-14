package com.ne_bknn.adbinstaller.crypto

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Generates an RSA keypair and a self-signed X.509 certificate in app-private storage.
 *
 * This is used instead of AndroidKeyStore because some devices/providers refuse to use
 * Keystore-backed RSA keys for the TLS operations used by Wireless debugging pairing.
 */
class SoftwareBackedKeys(
    private val context: Context,
    private val baseName: String,
    private val onLog: ((String) -> Unit)? = null,
) {
    data class Keys(
        val privateKey: PrivateKey,
        val certificate: X509Certificate,
    )

    fun getOrCreate(): Keys {
        val privFile = File(context.filesDir, "$baseName.pk8")
        val certFile = File(context.filesDir, "$baseName.crt")

        if (!privFile.exists() || !certFile.exists()) {
            onLog?.invoke("Keys missing; generating new RSA keypair + X.509 cert…")
            generate(privFile, certFile)
        }

        return try {
            val privateKey = loadPrivateKey(privFile)
            val certificate = loadCertificate(certFile)
            Keys(privateKey = privateKey, certificate = certificate)
        } catch (t: Throwable) {
            // Keys can get corrupted (partial writes, provider changes, etc.). Regenerate once.
            onLog?.invoke("Key load failed (${t::class.java.simpleName}: ${t.message}); regenerating…")
            runCatching { privFile.delete() }
            runCatching { certFile.delete() }
            generate(privFile, certFile)

            val privateKey = loadPrivateKey(privFile)
            val certificate = loadCertificate(certFile)
            Keys(privateKey = privateKey, certificate = certificate)
        }
    }

    private fun generate(privFile: File, certFile: File) {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()

        val now = Date()
        val notAfter = Date(now.time + TimeUnit.DAYS.toMillis(3650)) // ~10 years
        val serial = BigInteger.valueOf(now.time)
        val subject = X500Name("CN=ADBInstaller,O=ADBInstaller")

        val builder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            now,
            notAfter,
            subject,
            kp.public
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .build(kp.private)

        val holder = builder.build(signer)
        // Some Android builds ship a "BC" provider that does not implement CertificateFactory("X.509"),
        // which breaks JcaX509CertificateConverter on those devices. Avoid provider-specific conversion:
        // decode the certificate bytes using the platform CertificateFactory with a safe X509 fallback.
        val cf = runCatching { CertificateFactory.getInstance("X.509") }
            .getOrElse { CertificateFactory.getInstance("X509") }
        val cert = cf.generateCertificate(ByteArrayInputStream(holder.encoded)) as X509Certificate

        privFile.writeBytes(kp.private.encoded)
        certFile.writeBytes(cert.encoded)
    }

    private fun loadPrivateKey(privFile: File): PrivateKey {
        val spec = PKCS8EncodedKeySpec(privFile.readBytes())
        return KeyFactory.getInstance("RSA").generatePrivate(spec)
    }

    private fun loadCertificate(certFile: File): X509Certificate {
        val cf = runCatching { CertificateFactory.getInstance("X.509") }
            .onFailure { onLog?.invoke("CertificateFactory(\"X.509\") failed; retrying with \"X509\"") }
            .getOrElse { CertificateFactory.getInstance("X509") }
        val cert = cf.generateCertificate(ByteArrayInputStream(certFile.readBytes()))
        return cert as X509Certificate
    }

    private fun ensureBouncyCastle() {
        // Avoid replacing the platform "BC" provider; register as "BC-ADBInstaller" if needed.
        val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (existing == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }
}

