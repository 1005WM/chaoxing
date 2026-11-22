// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.store

import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingClientPreset
import com.caochaoxing.core.model.ChaoxingCustomClientProfile
import com.caochaoxing.core.model.ChaoxingLocationPreset
import com.caochaoxing.core.model.ChaoxingModelDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.time.Instant

val ChaoxingSnapshotJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
    prettyPrint = true
}

@Serializable
data class ChaoxingSignHistoryEntry(
    val id: String = ChaoxingModelDefaults.newUuidString(),
    val timestamp: String = ChaoxingModelDefaults.nowIso8601(),
    val activeId: Long,
    val activityName: String,
    val courseName: String,
    val signTypeRaw: String,
    val successCount: Int,
    val totalCount: Int,
    val summary: String,
    // Older records combined accepted and previously signed accounts in successCount.
    val alreadySignedCount: Int? = null,
) {
    fun timestampInstant(): Instant? = ChaoxingModelDefaults.parseIso8601OrNull(timestamp)
}

@Serializable
data class ChaoxingAppStateSnapshot(
    val accounts: List<ChaoxingAccountSession> = emptyList(),
    val locationPresets: List<ChaoxingLocationPreset> = emptyList(),
    val selectedClientPreset: String = ChaoxingClientPreset.DEFAULT.rawValue,
    val customUserAgent: String = "",
    val customClientProfiles: List<ChaoxingCustomClientProfile> = emptyList(),
    val selectedCustomClientId: String? = null,
    val enableLocationJitter: Boolean = true,
    val batchSignDelayMs: Int = 1200,
    val enableImageObfuscation: Boolean = true,
    val autoSolveCaptcha: Boolean = true,
    val signHistory: List<ChaoxingSignHistoryEntry> = emptyList(),
    val courseLocationBindings: Map<String, ChaoxingLocationPreset> = emptyMap(),
    val starredCourseIds: List<Long> = emptyList(),
) {
    fun encodedBackupJson(includeAccounts: Boolean): String {
        val payload = if (includeAccounts) this else copy(
            accounts = emptyList(),
            signHistory = signHistory.map { it.copy(summary = "") },
        )
        return ChaoxingSnapshotJson.encodeToString(ChaoxingAppStateSnapshot.serializer(), payload)
    }

    fun encodedBackupData(includeAccounts: Boolean): ByteArray =
        encodedBackupJson(includeAccounts).toByteArray(Charsets.UTF_8)

    companion object {
        fun decodedBackupJson(raw: String): ChaoxingAppStateSnapshot =
            ChaoxingSnapshotJson.decodeFromString(ChaoxingAppStateSnapshot.serializer(), raw)

        fun decodedBackupData(data: ByteArray): ChaoxingAppStateSnapshot {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val raw = decoder.decode(ByteBuffer.wrap(data)).toString()
            return decodedBackupJson(raw)
        }
    }
}

interface PersistenceStore {
    suspend fun load(): ChaoxingAppStateSnapshot
    suspend fun save(snapshot: ChaoxingAppStateSnapshot)
    suspend fun archiveAndReset(): File
}

class FilePersistenceStore(
    private val stateFile: File,
) : PersistenceStore {
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
        val path = stateFile.toPath().toAbsolutePath()
        if (Files.isDirectory(path)) {
            throw IOException("State path is a directory: $path")
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return ChaoxingAppStateSnapshot()
        }
        val bytes = Files.readAllBytes(path)
        return ChaoxingAppStateSnapshot.decodedBackupData(bytes)
    }

    private fun saveUnlocked(snapshot: ChaoxingAppStateSnapshot) {
        val path = stateFile.toPath().toAbsolutePath()
        if (Files.isDirectory(path)) {
            throw IOException("State path is a directory: $path")
        }
        // Do not turn a failed decode into silent destruction of the existing state file.
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            val existingBytes = Files.readAllBytes(path)
            ChaoxingAppStateSnapshot.decodedBackupData(existingBytes)
        }
        writeUnlocked(snapshot)
    }

    private fun archiveAndResetUnlocked(): File {
        val targetPath = stateFile.toPath().toAbsolutePath()
        if (Files.isDirectory(targetPath)) {
            throw IOException("State path is a directory: $targetPath")
        }
        if (!Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("State file does not exist: $targetPath")
        }
        val parentDir = targetPath.parent
            ?: throw IOException("Cannot resolve parent directory for $targetPath")
        Files.createDirectories(parentDir)
        val backupPath = parentDir.resolve("state.recovery-${ChaoxingModelDefaults.newUuidString()}.json")
        Files.copy(targetPath, backupPath)
        writeUnlocked(ChaoxingAppStateSnapshot())
        return backupPath.toFile()
    }

    private fun writeUnlocked(snapshot: ChaoxingAppStateSnapshot) {
        val targetPath = stateFile.toPath().toAbsolutePath()
        val parentDir = targetPath.parent
            ?: throw IOException("Cannot resolve parent directory for $targetPath")
        Files.createDirectories(parentDir)
        val bytes = snapshot.encodedBackupData(includeAccounts = true)
        val tempPath = Files.createTempFile(parentDir, ".${targetPath.fileName}.", ".tmp")
        try {
            Files.write(tempPath, bytes)
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
            try {
                Files.deleteIfExists(tempPath)
            } catch (_: IOException) {
                // Best-effort cleanup of temp file.
            }
        }
    }
}
