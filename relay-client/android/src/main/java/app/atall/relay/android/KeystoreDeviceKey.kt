package app.atall.relay.android

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.atall.relay.DeviceKey
import app.atall.relay.RelayStore
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The phone's relay key: P-256 in the Android Keystore, signing only, never exportable. Made on first use and kept
 * until the app is uninstalled (or [reset]). The private key never leaves the Keystore; only the public SPKI is read.
 */
class KeystoreDeviceKey(private val alias: String = DEFAULT_ALIAS) : DeviceKey {
    private val ks: KeyStore get() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    @Synchronized
    private fun ensure() {
        if (ks.containsAlias(alias)) return
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).run { initialize(spec); generateKeyPair() }
    }

    override val publicKeySpki: ByteArray
        get() { ensure(); return ks.getCertificate(alias).publicKey.encoded }

    override fun sign(data: ByteArray): ByteArray {
        ensure()
        val key = ks.getKey(alias, null) as PrivateKey
        return Signature.getInstance("SHA256withECDSA").run { initSign(key); update(data); sign() }
    }

    /** Deletes the key (a fresh one is made on next use). Pair again afterwards. */
    fun reset() { runCatching { ks.deleteEntry(alias) } }

    /** Whether the key lives in secure hardware (TEE or StrongBox). For the debug log only. */
    fun hardwareBacked(): Boolean = runCatching {
        ensure()
        val key = ks.getKey(alias, null) as PrivateKey
        val info = java.security.KeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, android.security.keystore.KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= 31) info.securityLevel != KeyProperties.SECURITY_LEVEL_SOFTWARE else @Suppress("DEPRECATION") info.isInsideSecureHardware
    }.getOrDefault(false)

    companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "atall_relay_device_v1"
    }
}

/** device_id, the relay's public key and the events cursor, in app-private SharedPreferences (none of it is secret). */
class PrefsRelayStore(context: Context) : RelayStore {
    private val sp = context.getSharedPreferences("atall_relay", Context.MODE_PRIVATE)
    override var deviceId: String?
        get() = sp.getString("device_id", null)
        set(v) { sp.edit().putString("device_id", v).apply() }
    override var relayPublicKeySpkiB64: String?
        get() = sp.getString("relay_public_key_spki_b64", null)
        set(v) { sp.edit().putString("relay_public_key_spki_b64", v).apply() }
    override var cursorJson: String?
        get() = sp.getString("cursor", null)
        set(v) { sp.edit().putString("cursor", v).apply() }

    fun clear() { sp.edit().clear().apply() }
}
