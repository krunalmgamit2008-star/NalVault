package com.nal.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Fingerprint unlock. The master password is stored encrypted under an Android Keystore key that
 * only opens after a biometric prompt, so the password itself is never written in the clear and a
 * fingerprint alone cannot be used to read the vault on another device.
 */
object BiometricUnlock {
    private const val KEY_ALIAS = "nal_vault_fingerprint"
    private const val PREFS = "vault"
    private const val KEY_BLOB = "fingerprint_blob"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isAvailable(context: Context): Boolean = runCatching {
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)

    fun isEnabled(context: Context): Boolean = prefs(context).contains(KEY_BLOB)

    fun disable(context: Context) {
        prefs(context).edit().remove(KEY_BLOB).apply()
        runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.deleteEntry(KEY_ALIAS)
        }
    }

    private fun secretKey(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun prompt(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        onResult: (Cipher?) -> Unit
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onResult(result.cryptoObject?.cipher)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onResult(null)
                }
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Cancel")
                .build(),
            BiometricPrompt.CryptoObject(cipher)
        )
    }

    /** Stores the master password behind a fingerprint. */
    fun enable(
        context: Context,
        activity: FragmentActivity,
        password: String,
        onResult: (Boolean) -> Unit
    ) {
        runCatching {
            val key = secretKey(true) ?: return@runCatching onResult(false)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            prompt(activity, cipher, "Enable fingerprint unlock") { ready ->
                if (ready == null) {
                    onResult(false)
                    return@prompt
                }
                runCatching {
                    val encrypted = ready.doFinal(password.toByteArray(Charsets.UTF_8))
                    val blob = Base64.encodeToString(ready.iv, Base64.NO_WRAP) + ":" +
                        Base64.encodeToString(encrypted, Base64.NO_WRAP)
                    prefs(context).edit().putString(KEY_BLOB, blob).apply()
                    onResult(true)
                }.onFailure { onResult(false) }
            }
        }.onFailure { onResult(false) }
    }

    /** Reads the stored password back after a fingerprint. */
    fun unlock(context: Context, activity: FragmentActivity, onPassword: (String?) -> Unit) {
        val blob = prefs(context).getString(KEY_BLOB, null)
        if (blob == null) {
            onPassword(null)
            return
        }
        val parts = blob.split(":")
        if (parts.size != 2) {
            onPassword(null)
            return
        }
        runCatching {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val data = Base64.decode(parts[1], Base64.NO_WRAP)
            val key = secretKey(false) ?: return@runCatching onPassword(null)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            prompt(activity, cipher, "Unlock Nal Vault") { ready ->
                if (ready == null) {
                    onPassword(null)
                    return@prompt
                }
                val plain = runCatching { ready.doFinal(data) }.getOrNull()
                onPassword(plain?.toString(Charsets.UTF_8))
            }
        }.onFailure { onPassword(null) }
    }
}
