package com.filestech.sms.sync.client

import com.filestech.sms.sync.DeviceKind
import com.filestech.sms.sync.SyncProtocol
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import timber.log.Timber
import java.io.File
import java.security.SecureRandom

/**
 * Client mode — this device's identity as the hub sees it: the stable
 * UUID, the display name, and the Ed25519 public key (base64url, 32
 * bytes). The private seed never leaves [ClientIdentityStore].
 */
data class ClientIdentity(
    val deviceId: String,
    val displayName: String,
    val publicKeyBase64Url: String,
)

/**
 * Client mode — the kind this device reports in `hello` and in its
 * pairing QR: `tablet` when the smallest width is the platform's own
 * tablet bucket (600 dp+), else `phone`. The kind is display metadata
 * only — the server never branches on it.
 */
fun deviceKindFor(smallestScreenWidthDp: Int): DeviceKind =
    if (smallestScreenWidthDp >= TABLET_SMALLEST_WIDTH_DP) DeviceKind.TABLET else DeviceKind.PHONE

/** The platform's tablet bucket, in dp of smallest screen width. */
private const val TABLET_SMALLEST_WIDTH_DP = 600

/**
 * Client mode — this device's stable identity: a UUID + an Ed25519
 * keypair, generated lazily at first link (not at install, so
 * hub-only installs never pay for it).
 *
 * Storage mirrors the desktop: the 32-byte private seed lives in an
 * app-private file (`device_key.bin` under [clientDir],
 * `filesDir/sync-client/` in production — `MODE_PRIVATE`,
 * 0600-equivalent); the public key is derived from it, so only the
 * seed is stored. The id and display name live in [ClientLinkStore].
 *
 * Why not the Android Keystore: Keystore Ed25519 key generation needs
 * API 33+ (minSdk is 26), and the BouncyCastle lightweight signer
 * needs the raw seed anyway — Keystore keys cannot be extracted for
 * BC signing. The hub's TLS key can use Keystore because it is EC
 * P-256 and the TLS stack signs inside the Keystore; neither
 * condition holds for the client's Ed25519 auth key.
 *
 * **Never silently regenerate.** If the stored seed is corrupt or
 * half-written, [identity] fails honestly with an
 * [IllegalStateException] — a silent regeneration would invalidate
 * the pairing with no explanation (the desktop's rule from
 * `keys.rs`). Writes go through a temp file + rename, so a crash
 * mid-write leaves either the old seed or no seed, never a torn one.
 *
 * Pure file I/O plus [ClientLinkStore]: unit-tested on the JVM
 * against a temp directory, no Android framework needed (the display
 * name default arrives as a constructor argument, not from
 * `android.os.Build`).
 */
class ClientIdentityStore(
    /** `filesDir/sync-client/` in production; a temp dir in tests. */
    private val clientDir: File,
    private val linkStore: ClientLinkStore,
    /** The display-name default (the model name in production). */
    private val displayNameDefault: String,
) {

    private val keyFile: File get() = File(clientDir, KEY_FILE_NAME)

    /** The loaded private key, cached after the first load. */
    @Volatile
    private var cachedPrivateKey: Ed25519PrivateKeyParameters? = null

    private val loadMutex = Mutex()

    /**
     * Loads the identity, generating it on first link. Idempotent and
     * cached: later calls return the same identity without touching
     * the disk again. Throws [IllegalStateException] on corrupt state
     * or on an I/O failure — never silently regenerates.
     */
    suspend fun identity(): ClientIdentity = loadMutex.withLock {
        val deviceId = linkStore.deviceIdOrCreate()
        val displayName = linkStore.displayNameOrCreate(displayNameDefault)
        val privateKey = cachedPrivateKey ?: loadOrCreateKey().also { cachedPrivateKey = it }
        val publicKey = privateKey.generatePublicKey().encoded
        ClientIdentity(
            deviceId = deviceId,
            displayName = displayName,
            publicKeyBase64Url = Base64Url.encode(publicKey),
        )
    }

    /**
     * Signs the raw [message] bytes (the auth challenge) with the
     * device key, returning the 64-byte Ed25519 signature. Loads (or
     * creates) the identity first, so any caller can sign without
     * worrying about order.
     */
    suspend fun sign(message: ByteArray): ByteArray {
        val privateKey = cachedPrivateKey ?: run {
            identity()
            cachedPrivateKey
        } ?: error("ClientIdentityStore: no private key after load")
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    private fun loadOrCreateKey(): Ed25519PrivateKeyParameters {
        val existing = readSeedIfPresent()
        if (existing != null) return Ed25519PrivateKeyParameters(existing)
        return generateAndStoreKey()
    }

    /**
     * Reads the stored seed, or null when there is none yet. A file of
     * the wrong size is corrupt state: fail honestly, never
     * regenerate.
     */
    private fun readSeedIfPresent(): ByteArray? {
        val file = keyFile
        if (!file.exists()) return null
        val seed = runCatching { file.readBytes() }.getOrElse { t ->
            throw IllegalStateException("ClientIdentityStore: cannot read the device key", t)
        }
        if (seed.size != SyncProtocol.PUBLIC_KEY_BYTES) {
            throw IllegalStateException(
                "ClientIdentityStore: device key is ${seed.size} bytes, expected " +
                    SyncProtocol.PUBLIC_KEY_BYTES,
            )
        }
        return seed
    }

    private fun generateAndStoreKey(): Ed25519PrivateKeyParameters {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = generator.generateKeyPair()
        val privateKey = keyPair.private as Ed25519PrivateKeyParameters
        storeSeed(privateKey.encoded)
        Timber.i("ClientIdentityStore: generated a new device key")
        return privateKey
    }

    /** Writes the seed via temp file + rename, so a crash leaves no torn file. */
    private fun storeSeed(seed: ByteArray) {
        runCatching {
            clientDir.mkdirs()
            val tmp = File(clientDir, KEY_FILE_NAME + TMP_SUFFIX)
            tmp.writeBytes(seed)
            if (!tmp.renameTo(keyFile)) {
                tmp.delete()
                error("could not move the device key into place")
            }
        }.onFailure { t ->
            throw IllegalStateException("ClientIdentityStore: cannot store the device key", t)
        }
    }

    companion object {
        /** The 32-byte private seed, next to the TOFU pin file. */
        const val KEY_FILE_NAME = "device_key.bin"

        private const val TMP_SUFFIX = ".tmp"
    }
}
