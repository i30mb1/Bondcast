package n7.bondcast.chat.twitch

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Хранит строки шифрованными: ключ AES/GCM в AndroidKeyStore, шифртекст — в обычных
 * SharedPreferences. Отдельный стор для OAuth-токена, а не общий plaintext DataStore.
 */
internal class SecureBlobStore(context: Context, prefsName: String) {

    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun read(key: String): String? {
        val blob = prefs.getString(key, null) ?: return null
        return runCatching { decrypt(blob) }.getOrNull()
    }

    fun write(key: String, value: String) {
        prefs.edit().putString(key, encrypt(value)).apply()
    }

    fun clear(key: String) {
        prefs.edit().remove(key).apply()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + SEP + Base64.encodeToString(cipherText, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val parts = blob.split(SEP)
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "twitch_auth_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val SEP = ":"
    }
}
