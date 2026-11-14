package app.atall.chatgpt

import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One registration: an issued client id bound to one ChatGPT account + workspace. `toString` hides tokens. */
@Serializable
data class Account(
    val clientId: String,
    val issuer: String = SiwcConfig.ISSUER,
    val subject: String = "",
    val email: String = "",
    val name: String = "",
    val idToken: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    /** Unix seconds. */
    val expiresAt: Long = 0,
    val earliestRefreshAt: Long = 0,
    val scopes: List<String> = emptyList(),
    val planUsage: Boolean = false,
    /** signed_in | signed_out | ended */
    val state: String = "signed_out",
    val endedReason: String = "",
    val savedAt: Long = 0,
) {
    val hasTokens: Boolean get() = !accessToken.isNullOrEmpty() && !refreshToken.isNullOrEmpty()

    fun clearedTokens(newState: String, reason: String = "") = copy(
        idToken = if (newState == "signed_out") null else idToken, // keep the hint after an *ended* session only
        accessToken = null, refreshToken = null, expiresAt = 0, earliestRefreshAt = 0, state = newState, endedReason = reason,
    )

    override fun toString() = "Account(clientId=$clientId, email=$email, state=$state, planUsage=$planUsage, expiresAt=$expiresAt)"
}

/** Everything persisted: this install's host id, the active registration, and each registration's record. */
@Serializable
data class Vault(
    val version: Int = 1,
    val hostId: String? = null,
    val active: String? = null,
    val accounts: Map<String, Account> = emptyMap(),
) {
    fun activeAccount(): Account? = active?.let { accounts[it] }
    fun with(acc: Account) = copy(accounts = accounts + (acc.clientId to acc))
    override fun toString() = "Vault(hostId=$hostId, active=$active, accounts=${accounts.values})"
}

/** Where the vault lives. Implementations must make [save] atomic (old or new, never half). */
interface CredentialStore {
    fun load(): Vault
    fun save(vault: Vault)
}

class InMemoryCredentialStore(@Volatile private var vault: Vault = Vault()) : CredentialStore {
    override fun load() = vault
    override fun save(vault: Vault) { this.vault = vault }
}

/** Encrypts the vault at rest. On Android back it with an AndroidKeyStore AES key ([AesGcmCipher] takes any SecretKey). */
interface BlobCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(sealed: ByteArray): ByteArray
}

/**
 * AES-256-GCM, 12-byte random IV prepended, fixed AAD. Pure javax.crypto: on Android pass a key from
 * `KeyStore.getInstance("AndroidKeyStore")` (non-exportable, purpose ENCRYPT|DECRYPT, GCM, NoPadding,
 * `setRandomizedEncryptionRequired(true)`); on Android, Keystore keys pick their own IV, so [keystoreManagedIv] = true.
 */
class AesGcmCipher(
    private val key: () -> SecretKey,
    private val keystoreManagedIv: Boolean = false,
    private val aad: ByteArray = "app.atall.chatgpt.vault.v1".toByteArray(),
) : BlobCipher {
    private val random = SecureRandom()

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        if (keystoreManagedIv) c.init(Cipher.ENCRYPT_MODE, key())
        else c.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, ByteArray(12).also { random.nextBytes(it) }))
        c.updateAAD(aad)
        val body = c.doFinal(plain)
        val iv = c.iv
        require(iv.size == 12)
        return byteArrayOf(1) + iv + body
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        require(sealed.size > 13 && sealed[0] == 1.toByte()) { "unknown vault format" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(1, 13)))
        c.updateAAD(aad)
        return c.doFinal(sealed, 13, sealed.size - 13)
    }
}

/**
 * The vault as one file, written atomically (temp file + fsync + rename), owner-only where POSIX permissions
 * exist. With a [cipher] the file is AES-GCM ciphertext. On Android use `context.noBackupFilesDir` and also
 * exclude it in backup / data-extraction rules.
 */
class FileCredentialStore(private val file: File, private val cipher: BlobCipher? = null) : CredentialStore {
    @Synchronized
    override fun load(): Vault {
        if (!file.exists()) return Vault()
        val bytes = file.readBytes()
        val text = String(cipher?.decrypt(bytes) ?: bytes, Charsets.UTF_8)
        return LenientJson.decodeFromString(Vault.serializer(), text)
    }

    @Synchronized
    override fun save(vault: Vault) {
        val dir = file.absoluteFile.parentFile
        dir.mkdirs()
        restrict(dir, dirMode = true)
        val plain = LenientJson.encodeToString(Vault.serializer(), vault).toByteArray(Charsets.UTF_8)
        val bytes = cipher?.encrypt(plain) ?: plain
        val tmp = File(dir, ".${file.name}.tmp")
        tmp.outputStream().use { out -> out.write(bytes); out.fd.sync() }
        restrict(tmp, dirMode = false)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun restrict(f: File, dirMode: Boolean) {
        try {
            Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString(if (dirMode) "rwx------" else "rw-------"))
        } catch (_: Exception) {
            f.setReadable(false, false); f.setReadable(true, true)
            f.setWritable(false, false); f.setWritable(true, true)
        }
    }
}
