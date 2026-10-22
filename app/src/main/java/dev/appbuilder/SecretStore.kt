package dev.appbuilder

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small secrets (the GitHub token) encrypted with an AES-GCM key that never leaves the Android Keystore. */
class SecretStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val alias = "appbuilder-secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build(),
        )
        return gen.generateKey()
    }

    fun put(name: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(value.toByteArray())
        sp.edit().putString(name, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? = try {
        val raw = Base64.decode(sp.getString(name, null) ?: return null, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12)) }
        String(c.doFinal(raw, 12, raw.size - 12))
    } catch (_: Exception) { null }

    fun remove(name: String) { sp.edit().remove(name).apply() }
}

/** The signed-in GitHub account on this device. */
class GitHubSession(ctx: Context) {
    private val store = SecretStore(ctx)
    val token: String? get() = store.get("github.token")
    val login: String? get() = store.get("github.login")
    val signedIn: Boolean get() = token != null
    fun save(token: String, login: String) { store.put("github.token", token); store.put("github.login", login) }
    fun clear() { store.remove("github.token"); store.remove("github.login") }
    fun auth(): GitAuth? = token?.let { GitAuth(login ?: "x-access-token", it) }
    fun api(): GitHubApi? = token?.let { GitHubApi(it) }
}
