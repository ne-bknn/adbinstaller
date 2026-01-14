package com.ne_bknn.adbinstaller.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.security.auth.x500.X500Principal
import java.security.KeyPairGenerator

class KeystoreBackedKeys(
    private val alias: String,
) {
    data class Keys(
        val privateKey: PrivateKey,
        val certificate: X509Certificate,
    )

    fun getOrCreate(): Keys {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        if (!ks.containsAlias(alias)) {
            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA,
                ANDROID_KEYSTORE
            )

            val now = Calendar.getInstance()
            val notBefore = now.time
            now.add(Calendar.YEAR, 20)
            val notAfter = now.time

            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN
            )
                .setKeySize(2048)
                .setCertificateSubject(X500Principal("CN=ADBInstaller,O=ADBInstaller"))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(notBefore)
                .setCertificateNotAfter(notAfter)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build()

            kpg.initialize(spec)
            kpg.generateKeyPair()
        }

        val entry = ks.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        val privateKey = entry.privateKey
        val cert = entry.certificate as X509Certificate
        return Keys(privateKey = privateKey, certificate = cert)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

