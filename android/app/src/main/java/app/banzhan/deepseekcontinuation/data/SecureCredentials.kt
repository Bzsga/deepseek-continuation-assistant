package app.banzhan.deepseekcontinuation.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only encrypted values are written to preferences. The wrapping key never leaves Android Keystore. */
internal class SecureCredentials(context: Context) {
    private val preferences = context.getSharedPreferences("encrypted_credentials", Context.MODE_PRIVATE)
    private val alias = "deepseek-continuation-credentials-v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    @Synchronized
    fun put(name: String, value: String) {
        if (value.isBlank()) {
            check(preferences.edit().remove(name).commit()) { "凭据保存失败，请重试" }
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString(name, payload).commit()) { "凭据保存失败，请重试" }
    }

    @Synchronized
    fun get(name: String): String {
        val payload = preferences.getString(name, null) ?: return ""
        try {
            val parts = payload.split('.', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (error: Exception) {
            throw IllegalStateException("设备安全密钥已变化，请重新填写服务凭据", error)
        }
    }

    fun remove(name: String) = put(name, "")

    @Synchronized
    fun removeTaskSecrets() {
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith("task:") }.forEach { editor.remove(it) }
        check(editor.commit()) { "任务凭据清除失败，请重试" }
    }
}
