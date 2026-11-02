package com.jarvis.assistant.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.jarvis.core.util.JarvisLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys and tokens, encrypted with an AES-256-GCM key that lives in the Android Keystore (hardware-backed where the device has it; the key cannot
 * be exported). Only ciphertext is written to SharedPreferences. If the key is lost or invalidated (restore to a new device, lock-screen reset on some
 * OEMs) the affected secrets are cleared and [lostSecrets] reports it, so the UI can ask the user to enter them again instead of failing mysteriously.
 *
 * There is deliberately NO plaintext fallback: if the Keystore cannot be used, saving a secret fails and [lastError] says why.
 */
class SecretStore(context: Context, private val log: JarvisLog = JarvisLog.None) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_secrets", Context.MODE_PRIVATE)

    @Volatile var lastError: String? = null
        private set
    private val lost = LinkedHashSet<String>()

    /** Names of secrets that could not be decrypted (and were therefore removed) since the app started. */
    fun lostSecrets(): Set<String> = synchronized(lost) { lost.toSet() }

    private fun key(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val (iv, ct) = stored.split(':', limit = 2).let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
            val k = key(create = false) ?: throw IllegalStateException("key missing")
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            // wrong key, tampered or half-written value: it is unusable, so remove it and tell the user to re-enter it
            log.w("Secrets", "a stored secret could not be decrypted (${e.javaClass.simpleName}) — cleared")
            prefs.edit().remove(name).apply()
            synchronized(lost) { lost.add(name) }
            null
        }
    }

    /** Saves (or removes, when [value] is null/blank) a secret. Returns false and sets [lastError] if it could not be stored securely. */
    fun put(name: String, value: String?): Boolean {
        lastError = null
        if (value.isNullOrBlank()) { prefs.edit().remove(name).apply(); synchronized(lost) { lost.remove(name) }; return true }
        return try {
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.ENCRYPT_MODE, key(create = true))
            val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
            val enc = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
            val ok = prefs.edit().putString(name, enc).commit()
            if (!ok) lastError = "Could not write the secret to storage."
            if (ok) synchronized(lost) { lost.remove(name) }
            ok
        } catch (e: Exception) {
            lastError = "Secure key storage isn't available on this device (${e.javaClass.simpleName}). The key was not saved."
            log.e("Secrets", "could not store a secret (${e.javaClass.simpleName})")
            false
        }
    }

    fun has(name: String): Boolean = prefs.contains(name)

    /** Removes every stored secret (used by "Reset JARVIS"). */
    fun clearAll() { prefs.edit().clear().apply(); synchronized(lost) { lost.clear() } }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "jarvis_secret_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
