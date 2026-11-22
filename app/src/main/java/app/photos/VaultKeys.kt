package app.photos

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore keys for the vault. Neither key ever leaves the phone's secure hardware.
 *  - The device key wraps the PIN/password-derived key, so a copied key file cannot be guessed offline.
 *  - The biometric key needs a strong fingerprint/face check for every use and is destroyed when a new one is enrolled.
 */
object VaultKeys {
    private const val DEVICE = "photos_device_v2"
    private const val BIOMETRIC = "photos_biometric_v1"
    private const val GCM = "AES/GCM/NoPadding"

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(alias: String) = store().getKey(alias, null) as? SecretKey
    private fun generate(alias: String, configure: KeyGenParameterSpec.Builder.() -> Unit = {}): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).apply(configure).build())
        }.generateKey()

    fun deviceSeal(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(GCM).apply { init(Cipher.ENCRYPT_MODE, key(DEVICE) ?: generate(DEVICE)) }
        return cipher.iv + cipher.doFinal(bytes)
    }
    fun deviceOpen(bytes: ByteArray): ByteArray {
        val k = key(DEVICE) ?: error("This phone's vault key is missing")
        return Cipher.getInstance(GCM).apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, bytes, 0, 12)) }.doFinal(bytes, 12, bytes.size - 12)
    }

    fun biometricAvailable(context: Context) =
        context.getSystemService(BiometricManager::class.java)?.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** A fresh key each time fingerprint unlock is turned on. */
    fun biometricEncrypt(): Cipher {
        removeBiometric()
        val k = generate(BIOMETRIC) {
            setUserAuthenticationRequired(true)
            setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            setInvalidatedByBiometricEnrollment(true)
        }
        return Cipher.getInstance(GCM).apply { init(Cipher.ENCRYPT_MODE, k) }
    }
    /** Null when the key was invalidated (new fingerprint enrolled, or screen lock removed). */
    fun biometricDecrypt(iv: ByteArray): Cipher? = try {
        val k = key(BIOMETRIC) ?: return null
        Cipher.getInstance(GCM).apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv)) }
    } catch (_: KeyPermanentlyInvalidatedException) { null }

    fun removeBiometric() { runCatching { store().deleteEntry(BIOMETRIC) } }

    /** Shows the system fingerprint/face sheet. [onDone] gets the unlocked cipher, or null with a message (null message = cancelled). */
    fun prompt(context: Context, title: String, cancel: String, cipher: Cipher, onDone: (Cipher?, String?) -> Unit): CancellationSignal {
        val signal = CancellationSignal()
        val executor = context.mainExecutor
        BiometricPrompt.Builder(context).setTitle(title).setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButton(cancel, executor) { _, _ -> onDone(null, null) }.build()
            .authenticate(BiometricPrompt.CryptoObject(cipher), signal, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onDone(result.cryptoObject?.cipher, null) }
                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    val quiet = code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                    onDone(null, if (quiet) null else message.toString())
                }
            })
        return signal
    }
}

/** Slows down guessing: after 5 wrong attempts, waits 30 s, doubling up to 1 hour. Survives app restarts. */
class VaultGuard(context: Context) {
    private val prefs = context.getSharedPreferences("vault_guard", 0)
    private val hour = 60L * 60 * 1000
    /** Milliseconds left before another attempt is allowed (capped, so moving the clock back cannot lock you out for longer). */
    fun waitMillis(): Long = (prefs.getLong("until", 0) - System.currentTimeMillis()).coerceIn(0, hour)
    fun failed() {
        val count = prefs.getInt("failures", 0) + 1
        val wait = if (count < 5) 0L else (30_000L shl (count - 5).coerceAtMost(7)).coerceAtMost(hour)
        prefs.edit().putInt("failures", count).putLong("until", System.currentTimeMillis() + wait).commit()
    }
    fun reset() { prefs.edit().clear().commit() }
}
