// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.state

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.caochaoxing.core.model.ChaoxingModelDefaults
import com.caochaoxing.core.store.ChaoxingAppStateSnapshot
import com.caochaoxing.core.store.PersistenceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Pure JCE AES-256-GCM helper (`12`-byte random IV prepended + `128`-bit authentication tag).
 */
internal object AesGcmCipher {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    internal const val GCM_IV_LENGTH_BYTES = 12
    internal const val GCM_TAG_LENGTH_BITS = 128
    internal const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8

    private val secureRandom = SecureRandom()

    fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = try {
            val randomIv = ByteArray(GCM_IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, randomIv))
            randomIv
        } catch (_: InvalidAlgorithmParameterException) {
            // AndroidKeyStore keys enforce randomizedEncryptionRequired = true by default and reject
            // caller-supplied IVs, generating a hardware-backed 12-byte IV internally instead.
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.iv ?: throw IllegalStateException("Cipher did not generate a GCM IV")
        }
        require(iv.size == GCM_IV_LENGTH_BYTES) {
            "Expected $GCM_IV_LENGTH_BYTES-byte GCM IV, got ${iv.size}"
        }
        val ciphertext = cipher.doFinal(plaintext)
        val output = ByteArray(iv.size + ciphertext.size)
        iv.copyInto(output, destinationOffset = 0)
        ciphertext.copyInto(output, destinationOffset = iv.size)
        return output
    }

    fun decrypt(payload: ByteArray, key: SecretKey): ByteArray {
        if (payload.size < GCM_IV_LENGTH_BYTES + GCM_TAG_LENGTH_BYTES) {
            throw AEADBadTagException("AES-GCM payload too short: ${payload.size} bytes")
        }
        val iv = payload.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = payload.copyOfRange(GCM_IV_LENGTH_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}

/**
 * Android Keystore-backed [PersistenceStore] storing [ChaoxingAppStateSnapshot] encrypted as
 * `caochaoxing/state.enc`, with corruption protection, atomic file replacement, and seamless
 * migration from legacy plaintext `caochaoxing/state.json`.
 */
class EncryptedFilePersistenceStore internal constructor(
    private val stateFile: File,
    private val legacyPlaintextFile: File,
    private val keyProvider: () -> SecretKey,
) : PersistenceStore {

    constructor(context: Context) : this(
        stateFile = File(appFilesSubdir(context), ENCRYPTED_FILE_NAME),
        legacyPlaintextFile = File(appFilesSubdir(context), LEGACY_PLAINTEXT_FILE_NAME),
        keyProvider = { getOrCreateKeyStoreSecretKey(KEY_ALIAS) },
    )

    private val mutex = Mutex()

    override suspend fun load(): ChaoxingAppStateSnapshot = mutex.withLock {
        withContext(Dispatchers.IO) {
            loadUnlocked()
        }
    }

    override suspend fun save(snapshot: ChaoxingAppStateSnapshot) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                saveUnlocked(snapshot)
            }
        }
    }

    override suspend fun archiveAndReset(): File = mutex.withLock {
        withContext(Dispatchers.IO) {
            archiveAndResetUnlocked()
        }
    }

    private fun loadUnlocked(): ChaoxingAppStateSnapshot {
        val targetPath = resolveNonDirectoryPath(stateFile)
        val legacyPath = resolveNonDirectoryPath(legacyPlaintextFile)

        if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) {
            return readAndDecryptUnlocked(targetPath)
        }
        if (Files.exists(legacyPath, LinkOption.NOFOLLOW_LINKS)) {
            val migrated = readPlaintextSnapshot(legacyPath)
            writeUnlocked(migrated)
            deleteIfExistsBestEffort(legacyPath)
            return migrated
        }
        return ChaoxingAppStateSnapshot()
    }

    private fun saveUnlocked(snapshot: ChaoxingAppStateSnapshot) {
        val targetPath = resolveNonDirectoryPath(stateFile)
        val legacyPath = resolveNonDirectoryPath(legacyPlaintextFile)

        // Never silently overwrite an existing unreadable/corrupt state file.
        if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) {
            readAndDecryptUnlocked(targetPath)
            writeUnlocked(snapshot)
            return
        }
        if (Files.exists(legacyPath, LinkOption.NOFOLLOW_LINKS)) {
            readPlaintextSnapshot(legacyPath)
            writeUnlocked(snapshot)
            deleteIfExistsBestEffort(legacyPath)
            return
        }
        writeUnlocked(snapshot)
    }

    private fun archiveAndResetUnlocked(): File {
        val targetPath = resolveNonDirectoryPath(stateFile)
        val legacyPath = resolveNonDirectoryPath(legacyPlaintextFile)

        if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) {
            val parentDir = targetPath.parent
                ?: throw IOException("Cannot resolve parent directory for $targetPath")
            Files.createDirectories(parentDir)
            val backupPath = parentDir.resolve(
                "state.recovery-${ChaoxingModelDefaults.newUuidString()}.enc",
            )
            Files.copy(targetPath, backupPath)
            writeUnlocked(ChaoxingAppStateSnapshot())
            return backupPath.toFile()
        }

        if (Files.exists(legacyPath, LinkOption.NOFOLLOW_LINKS)) {
            val parentDir = legacyPath.parent
                ?: throw IOException("Cannot resolve parent directory for $legacyPath")
            Files.createDirectories(parentDir)
            val backupPath = parentDir.resolve(
                "state.recovery-${ChaoxingModelDefaults.newUuidString()}.json",
            )
            Files.copy(legacyPath, backupPath)
            writeUnlocked(ChaoxingAppStateSnapshot())
            deleteIfExistsBestEffort(legacyPath)
            return backupPath.toFile()
        }

        throw IOException("State file does not exist: $targetPath")
    }

    private fun readAndDecryptUnlocked(path: Path): ChaoxingAppStateSnapshot {
        val encryptedBytes = Files.readAllBytes(path)
        val plainBytes = AesGcmCipher.decrypt(encryptedBytes, keyProvider())
        return ChaoxingAppStateSnapshot.decodedBackupData(plainBytes)
    }

    private fun readPlaintextSnapshot(path: Path): ChaoxingAppStateSnapshot {
        val plainBytes = Files.readAllBytes(path)
        return ChaoxingAppStateSnapshot.decodedBackupData(plainBytes)
    }

    private fun writeUnlocked(snapshot: ChaoxingAppStateSnapshot) {
        val targetPath = resolveNonDirectoryPath(stateFile)
        val parentDir = targetPath.parent
            ?: throw IOException("Cannot resolve parent directory for $targetPath")
        Files.createDirectories(parentDir)

        val plainBytes = snapshot.encodedBackupData(includeAccounts = true)
        val encryptedBytes = AesGcmCipher.encrypt(plainBytes, keyProvider())
        val tempPath = Files.createTempFile(parentDir, ".${targetPath.fileName}.", ".tmp")
        try {
            Files.write(tempPath, encryptedBytes)
            try {
                Files.move(
                    tempPath,
                    targetPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tempPath,
                    targetPath,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            deleteIfExistsBestEffort(tempPath)
        }
    }

    private fun resolveNonDirectoryPath(file: File): Path {
        val path = file.toPath().toAbsolutePath()
        if (Files.isDirectory(path)) {
            throw IOException("State path is a directory: $path")
        }
        return path
    }

    private fun deleteIfExistsBestEffort(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: IOException) {
            // Best-effort cleanup; encrypted state is already safely committed.
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "com.caochaoxing.android.state.aesgcm.v1"
        private const val STATE_DIR_NAME = "caochaoxing"
        private const val ENCRYPTED_FILE_NAME = "state.enc"
        private const val LEGACY_PLAINTEXT_FILE_NAME = "state.json"

        private fun appFilesSubdir(context: Context): File {
            val appContext = context.applicationContext ?: context
            return File(appContext.filesDir, STATE_DIR_NAME)
        }

        private fun getOrCreateKeyStoreSecretKey(alias: String): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(alias)) {
                val existing = keyStore.getKey(alias, null) as? SecretKey
                if (existing != null) {
                    return existing
                }
            }
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE,
            )
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        }
    }
}
