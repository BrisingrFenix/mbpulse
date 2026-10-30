package pt.ua.mbpulse.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small app-private files sealed with a hardware-backed AES-256-GCM Keystore
 * key (same approach as `DatabaseKeyProvider`): `IV || ciphertext+tag`.
 * Holds the Supabase session and the silent re-login credentials, which must
 * not sit in plain SharedPreferences (supabase-kt's default).
 */
internal class SealedFileStore(private val context: Context) {

    fun write(name: String, plaintext: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        file(name).writeBytes(cipher.iv + cipher.doFinal(plaintext))
    }

    fun read(name: String): ByteArray? {
        val blob = file(name).takeIf { it.exists() }?.readBytes() ?: return null
        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
                doFinal(blob.copyOfRange(12, blob.size))
            }
        } catch (e: Exception) {
            null   // corrupt or key lost (e.g. restored onto another device) — treat as absent
        }
    }

    fun delete(name: String) {
        file(name).delete()
    }

    private fun file(name: String) = File(context.filesDir, name)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)   // background sync must work while locked
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "mbpulse_auth_store_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** supabase-kt session persistence, sealed under the Keystore instead of plain preferences. */
internal class SealedSessionManager(context: Context) : SessionManager {
    private val store = SealedFileStore(context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun saveSession(session: UserSession) = withContext(Dispatchers.IO) {
        store.write(FILE, json.encodeToString(UserSession.serializer(), session).toByteArray(Charsets.UTF_8))
    }

    override suspend fun loadSession(): UserSession? = withContext(Dispatchers.IO) {
        store.read(FILE)?.let { runCatching { json.decodeFromString(UserSession.serializer(), it.toString(Charsets.UTF_8)) }.getOrNull() }
    }

    override suspend fun deleteSession() = withContext(Dispatchers.IO) { store.delete(FILE) }

    private companion object { const val FILE = "mbpulse_auth_session.bin" }
}

/**
 * The participant's account credentials (login email + derived auth secret —
 * never the passphrase), kept sealed for **silent re-login**: if the session
 * is lost (refresh token revoked, storage cleared by an update), background
 * sync signs in again without involving the participant. Written only after
 * enrolment or recovery succeeded; cleared on withdrawal and reset.
 */
internal class ParticipantCredentialStore(context: Context) {
    private val store = SealedFileStore(context)

    @Serializable
    data class Credentials(val email: String, val authSecret: String)

    suspend fun save(credentials: Credentials) = withContext(Dispatchers.IO) {
        store.write(FILE, Json.encodeToString(Credentials.serializer(), credentials).toByteArray(Charsets.UTF_8))
    }

    suspend fun load(): Credentials? = withContext(Dispatchers.IO) {
        store.read(FILE)?.let { runCatching { Json.decodeFromString(Credentials.serializer(), it.toString(Charsets.UTF_8)) }.getOrNull() }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { store.delete(FILE) }

    private companion object { const val FILE = "mbpulse_auth_credentials.bin" }
}

/**
 * This phone's **device key** (`crypto/DeviceBindingAuth`): the random secret
 * that lets a new login of this phone attach to the participant (backend
 * `bind_session`). Registered at enrolment / recovery / first binding; a
 * recovery on another phone replaces it on the server, which is what locks
 * this phone out. Sealed like the credentials; cleared on withdrawal and reset.
 */
internal class DeviceKeyStore(context: Context) {
    private val store = SealedFileStore(context)

    suspend fun save(key: ByteArray) = withContext(Dispatchers.IO) { store.write(FILE, key) }

    suspend fun load(): ByteArray? = withContext(Dispatchers.IO) { store.read(FILE) }

    suspend fun clear() = withContext(Dispatchers.IO) { store.delete(FILE) }

    private companion object { const val FILE = "mbpulse_device_key.bin" }
}
