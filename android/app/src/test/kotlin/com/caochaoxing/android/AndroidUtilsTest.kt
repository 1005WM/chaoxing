// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android

import com.caochaoxing.android.location.ExternalMapLauncher
import com.caochaoxing.android.location.LocationAccessLevel
import com.caochaoxing.android.location.LocationCandidate
import com.caochaoxing.android.location.SystemLocationProvider
import com.caochaoxing.android.media.AndroidImageObfuscator
import com.caochaoxing.android.media.QrCodeDecoder
import com.caochaoxing.android.state.AesGcmCipher
import com.caochaoxing.android.state.EncryptedFilePersistenceStore
import com.caochaoxing.core.crypto.CoordinateConverter
import com.caochaoxing.core.crypto.ExternalCoordinateParser
import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingLocationPreset
import com.caochaoxing.core.model.CoordinateSystem
import com.caochaoxing.core.store.ChaoxingAppStateSnapshot
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AndroidUtilsTest {
    @Rule
    @JvmField
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private fun generateAes256Key(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun aesGcmCipherRoundtripsAndUsesUniqueIvPerEncryption() {
        val key = generateAes256Key()

        val emptyEncrypted = AesGcmCipher.encrypt(ByteArray(0), key)
        assertEquals(
            AesGcmCipher.GCM_IV_LENGTH_BYTES + AesGcmCipher.GCM_TAG_LENGTH_BYTES,
            emptyEncrypted.size,
        )
        assertArrayEquals(ByteArray(0), AesGcmCipher.decrypt(emptyEncrypted, key))

        val plaintext = """{"phone":"13800000001","note":"测试中文 AES-GCM 加密"}"""
            .toByteArray(Charsets.UTF_8)
        val enc1 = AesGcmCipher.encrypt(plaintext, key)
        val enc2 = AesGcmCipher.encrypt(plaintext, key)

        assertEquals(
            AesGcmCipher.GCM_IV_LENGTH_BYTES + plaintext.size + AesGcmCipher.GCM_TAG_LENGTH_BYTES,
            enc1.size,
        )
        assertFalse(
            enc1.copyOfRange(0, AesGcmCipher.GCM_IV_LENGTH_BYTES)
                .contentEquals(enc2.copyOfRange(0, AesGcmCipher.GCM_IV_LENGTH_BYTES)),
        )
        assertFalse(enc1.contentEquals(enc2))
        assertArrayEquals(plaintext, AesGcmCipher.decrypt(enc1, key))
        assertArrayEquals(plaintext, AesGcmCipher.decrypt(enc2, key))
    }

    @Test
    fun aesGcmCipherRejectsTamperedTruncatedAndWrongKeyPayloads() {
        val key = generateAes256Key()
        val wrongKey = generateAes256Key()
        val plaintext = "sensitive-cookie-and-session".toByteArray(Charsets.UTF_8)
        val encrypted = AesGcmCipher.encrypt(plaintext, key)

        // Tamper IV byte
        val badIv = encrypted.copyOf().apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
        assertThrows(AEADBadTagException::class.java) {
            AesGcmCipher.decrypt(badIv, key)
        }

        // Tamper ciphertext byte
        val badBody = encrypted.copyOf().apply {
            val idx = AesGcmCipher.GCM_IV_LENGTH_BYTES + 2
            this[idx] = (this[idx].toInt() xor 0x80).toByte()
        }
        assertThrows(AEADBadTagException::class.java) {
            AesGcmCipher.decrypt(badBody, key)
        }

        // Tamper GCM tag byte
        val badTag = encrypted.copyOf().apply {
            this[lastIndex] = (this[lastIndex].toInt() xor 0xFF).toByte()
        }
        assertThrows(AEADBadTagException::class.java) {
            AesGcmCipher.decrypt(badTag, key)
        }

        // Wrong key
        assertThrows(AEADBadTagException::class.java) {
            AesGcmCipher.decrypt(encrypted, wrongKey)
        }

        // Truncated payload (< 12-byte IV + 16-byte tag)
        assertThrows(AEADBadTagException::class.java) {
            AesGcmCipher.decrypt(encrypted.copyOfRange(0, 27), key)
        }
    }

    @Test
    fun encryptedStoreRoundtripsProtectsCorruptFilesAndMigratesLegacyPlaintext() = runTest {
        val dir = tempFolder.newFolder("encrypted-store")
        val stateFile = File(dir, "state.enc")
        val legacyFile = File(dir, "state.json")
        val key = generateAes256Key()
        val store = EncryptedFilePersistenceStore(
            stateFile = stateFile,
            legacyPlaintextFile = legacyFile,
            keyProvider = { key },
        )

        // Missing files -> returns defaults without creating files on disk
        assertEquals(ChaoxingAppStateSnapshot(), store.load())
        assertFalse(stateFile.exists())
        assertFalse(legacyFile.exists())

        val snapshot = ChaoxingAppStateSnapshot(
            accounts = listOf(
                ChaoxingAccountSession(
                    phoneNumber = "13900001111",
                    encryptedPassword = "cipher-password-secret",
                    name = "张三",
                    deviceCode = "test-device-code",
                    tags = listOf("主力"),
                ),
            ),
            locationPresets = listOf(
                ChaoxingLocationPreset(
                    id = "550e8400-e29b-41d4-a716-446655440099",
                    name = "第一教学楼",
                    address = "中关村大街1号",
                    latitude = 39.961234,
                    longitude = 116.312345,
                ),
            ),
            starredCourseIds = listOf(1001L, 2002L),
        )

        store.save(snapshot)
        assertTrue(stateFile.exists())
        val rawDiskText = stateFile.readBytes().toString(Charsets.UTF_8)
        assertFalse(rawDiskText.contains("13900001111"))
        assertFalse(rawDiskText.contains("cipher-password-secret"))
        assertEquals(snapshot, store.load())

        // Corrupt state.enc -> load & save refuse to destroy original bytes
        val corruptBytes = stateFile.readBytes().apply {
            this[lastIndex] = (this[lastIndex].toInt() xor 0x55).toByte()
        }
        stateFile.writeBytes(corruptBytes)
        assertThrowsSuspend<AEADBadTagException> { store.load() }
        assertThrowsSuspend<AEADBadTagException> { store.save(ChaoxingAppStateSnapshot()) }
        assertArrayEquals(corruptBytes, stateFile.readBytes())

        val recoveredEnc = store.archiveAndReset()
        assertTrue(recoveredEnc.exists())
        assertTrue(recoveredEnc.name.startsWith("state.recovery-"))
        assertTrue(recoveredEnc.name.endsWith(".enc"))
        assertArrayEquals(corruptBytes, recoveredEnc.readBytes())
        assertEquals(ChaoxingAppStateSnapshot(), store.load())

        // Legacy state.json plaintext migration when state.enc does not exist
        val migrationDir = tempFolder.newFolder("legacy-migration")
        val migratedEncFile = File(migrationDir, "state.enc")
        val oldPlaintextFile = File(migrationDir, "state.json")
        oldPlaintextFile.writeBytes(snapshot.encodedBackupData(includeAccounts = true))
        val migrationStore = EncryptedFilePersistenceStore(
            stateFile = migratedEncFile,
            legacyPlaintextFile = oldPlaintextFile,
            keyProvider = { key },
        )

        val migratedLoaded = migrationStore.load()
        assertEquals(snapshot, migratedLoaded)
        assertTrue(migratedEncFile.exists())
        assertFalse(oldPlaintextFile.exists())
        assertEquals(snapshot, migrationStore.load())

        // Corrupt legacy state.json -> refuses to overwrite and archives as .json before resetting to .enc
        val corruptLegacyDir = tempFolder.newFolder("corrupt-legacy")
        val corruptLegacyEnc = File(corruptLegacyDir, "state.enc")
        val corruptLegacyJson = File(corruptLegacyDir, "state.json")
        corruptLegacyJson.writeText("{corrupt-legacy-json")
        val corruptLegacyStore = EncryptedFilePersistenceStore(
            stateFile = corruptLegacyEnc,
            legacyPlaintextFile = corruptLegacyJson,
            keyProvider = { key },
        )
        assertThrowsSuspend<Exception> { corruptLegacyStore.load() }
        assertThrowsSuspend<Exception> { corruptLegacyStore.save(ChaoxingAppStateSnapshot()) }
        assertFalse(corruptLegacyEnc.exists())
        assertEquals("{corrupt-legacy-json", corruptLegacyJson.readText())
        val recoveredLegacy = corruptLegacyStore.archiveAndReset()
        assertTrue(recoveredLegacy.name.startsWith("state.recovery-"))
        assertTrue(recoveredLegacy.name.endsWith(".json"))
        assertEquals("{corrupt-legacy-json", recoveredLegacy.readText())
        assertFalse(corruptLegacyJson.exists())
        assertTrue(corruptLegacyEnc.exists())
        assertEquals(ChaoxingAppStateSnapshot(), corruptLegacyStore.load())

        // Directory at stateFile path throws IOException
        val badDir = tempFolder.newFolder("dir-as-file")
        val dirStore = EncryptedFilePersistenceStore(
            stateFile = badDir,
            legacyPlaintextFile = File(badDir, "legacy.json"),
            keyProvider = { key },
        )
        assertThrowsSuspend<IOException> { dirStore.load() }
        assertThrowsSuspend<IOException> { dirStore.save(ChaoxingAppStateSnapshot()) }
        assertThrowsSuspend<IOException> { dirStore.archiveAndReset() }
    }

    @Test
    fun qrCodeDecoderDecodesNormalInvertedAndStridedYuvQrCodes() {
        val payload = "SIGNIN:aid=42&enc=test"
        val size = 256
        val matrix = QRCodeWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2,
            ),
        )

        // 1. Standard dark-on-light RGB luminance source
        val normalPixels = IntArray(size * size) { idx ->
            val x = idx % size
            val y = idx / size
            if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        assertEquals(
            payload,
            QrCodeDecoder.decodeLuminanceSource(RGBLuminanceSource(size, size, normalPixels)),
        )

        // 2. Inverted light-on-dark RGB luminance source
        val invertedPixels = IntArray(size * size) { idx ->
            val x = idx % size
            val y = idx / size
            if (matrix.get(x, y)) 0xFFF5F5F7.toInt() else 0xFF121214.toInt()
        }
        assertEquals(
            payload,
            QrCodeDecoder.decodeLuminanceSource(RGBLuminanceSource(size, size, invertedPixels)),
        )

        // 3. Simulated CameraX YUV_420_888 plane with pixelStride = 2 and trailing row padding
        val pixelStride = 2
        val rowStride = size * pixelStride + 16
        val yuvBacking = ByteArray(size * rowStride) { 0x7F.toByte() }
        for (y in 0 until size) {
            val rowOffset = y * rowStride
            for (x in 0 until size) {
                yuvBacking[rowOffset + x * pixelStride] =
                    if (matrix.get(x, y)) 0x10.toByte() else 0xEB.toByte()
            }
        }
        val planeBuffer = ByteBuffer.wrap(yuvBacking).apply { position(11) }
        val extractedY = QrCodeDecoder.extractLuminanceBytes(
            planeBuffer = planeBuffer,
            width = size,
            height = size,
            rowStride = rowStride,
            pixelStride = pixelStride,
        )
        assertNotNull(extractedY)
        assertEquals(size * size, extractedY!!.size)

        val yuvSource = PlanarYUVLuminanceSource(
            extractedY,
            size,
            size,
            0,
            0,
            size,
            size,
            false,
        )
        assertEquals(payload, QrCodeDecoder.decodeLuminanceSource(yuvSource))

        // 4. Blank uniform buffer returns null
        val blankPixels = IntArray(size * size) { 0xFFFFFFFF.toInt() }
        assertNull(QrCodeDecoder.decodeLuminanceSource(RGBLuminanceSource(size, size, blankPixels)))
    }

    @Test
    fun externalMapLauncherBuildsProperlyEncodedAndConvertedUris() {
        val bdLat = 39.904200
        val bdLon = 116.407400
        val label = "北京大学 东门 (1)"

        // Coordinate-present URIs + percent-encoding (%20 instead of '+')
        val baiduUri = ExternalMapLauncher.buildBaiduMapUri(bdLat, bdLon, label)
        assertTrue(baiduUri.startsWith("baidumap://map/marker?"))
        assertTrue(baiduUri.contains("coord_type=bd09ll"))
        assertTrue(baiduUri.contains("location=39.904200,116.407400"))
        assertTrue(baiduUri.contains("%20"))
        assertFalse(baiduUri.contains("+"))
        val parsedBaidu = ExternalCoordinateParser.parse(baiduUri, CoordinateSystem.WGS84)
        assertNotNull(parsedBaidu)
        assertEquals(CoordinateSystem.BD09, parsedBaidu!!.sourceSystem)
        assertEquals(bdLat, parsedBaidu.latitude, 1e-5)
        assertEquals(bdLon, parsedBaidu.longitude, 1e-5)
        assertEquals(label, parsedBaidu.label)

        val expectedGcj = CoordinateConverter.convert(bdLat, bdLon, CoordinateSystem.BD09, CoordinateSystem.GCJ02)
        val amapUri = ExternalMapLauncher.buildAmapUri(bdLat, bdLon, label)
        assertTrue(amapUri.startsWith("amapuri://viewMap?"))
        assertTrue(amapUri.contains("dev=0"))
        assertTrue(amapUri.contains("%20"))
        assertFalse(amapUri.contains("+"))
        val parsedAmap = ExternalCoordinateParser.parse(amapUri, CoordinateSystem.WGS84)
        assertNotNull(parsedAmap)
        assertEquals(CoordinateSystem.GCJ02, parsedAmap!!.sourceSystem)
        assertEquals(expectedGcj.latitude, parsedAmap.latitude, 1e-5)
        assertEquals(expectedGcj.longitude, parsedAmap.longitude, 1e-5)
        assertEquals(label, parsedAmap.label)

        val tencentUri = ExternalMapLauncher.buildTencentMapUri(bdLat, bdLon, label)
        assertTrue(tencentUri.startsWith("qqmap://map/marker?"))
        assertTrue(tencentUri.contains("%20"))
        assertFalse(tencentUri.contains("+"))
        val parsedTencent = ExternalCoordinateParser.parse(tencentUri, CoordinateSystem.WGS84)
        assertNotNull(parsedTencent)
        assertEquals(CoordinateSystem.GCJ02, parsedTencent!!.sourceSystem)
        assertEquals(expectedGcj.latitude, parsedTencent.latitude, 1e-5)
        assertEquals(expectedGcj.longitude, parsedTencent.longitude, 1e-5)
        assertEquals(label, parsedTencent.label)

        val expectedWgs = CoordinateConverter.toWgs84(bdLat, bdLon, CoordinateSystem.BD09)
        val geoUri = ExternalMapLauncher.buildGeoFallbackUri(bdLat, bdLon, label)
        assertTrue(geoUri.startsWith("geo:"))
        assertFalse(geoUri.contains("+"))
        val parsedGeo = ExternalCoordinateParser.parse(geoUri, CoordinateSystem.BD09)
        assertNotNull(parsedGeo)
        assertEquals(CoordinateSystem.WGS84, parsedGeo!!.sourceSystem)
        assertEquals(expectedWgs.latitude, parsedGeo.latitude, 1e-5)
        assertEquals(expectedWgs.longitude, parsedGeo.longitude, 1e-5)
        assertEquals(label, parsedGeo.label)

        // Null / invalid coordinates fall back cleanly to search/home URIs with encoded Chinese labels
        val searchLabel = "清华大学 主楼"
        val baiduSearch = ExternalMapLauncher.buildBaiduMapUri(null, null, searchLabel)
        assertTrue(baiduSearch.startsWith("baidumap://map/place/search?query="))
        assertTrue(baiduSearch.contains("%20"))
        assertFalse(baiduSearch.contains("+"))
        assertEquals("baidumap://map/show?src=andr.caochaoxing.android", ExternalMapLauncher.buildBaiduMapUri(null, null, "  "))

        val amapSearch = ExternalMapLauncher.buildAmapUri(null, bdLon, searchLabel)
        assertTrue(amapSearch.startsWith("amapuri://poi?sourceApplication=Caochaoxing&keywords="))
        assertFalse(amapSearch.contains("+"))
        assertEquals("amapuri://viewMap?sourceApplication=Caochaoxing&dev=0", ExternalMapLauncher.buildAmapUri(Double.NaN, 116.4, null))

        val tencentSearch = ExternalMapLauncher.buildTencentMapUri(95.0, 116.4, searchLabel)
        assertTrue(tencentSearch.startsWith("qqmap://map/search?keyword="))
        assertFalse(tencentSearch.contains("+"))
        assertEquals("qqmap://map/search?referer=Caochaoxing", ExternalMapLauncher.buildTencentMapUri(null, null, null))

        val geoSearch = ExternalMapLauncher.buildGeoFallbackUri(null, null, searchLabel)
        assertTrue(geoSearch.startsWith("geo:0,0?q="))
        assertFalse(geoSearch.contains("+"))
        assertEquals("geo:0,0", ExternalMapLauncher.buildGeoFallbackUri(null, null, null))
    }

    @Test
    fun systemLocationPermissionAndProviderSelectionRespectCoarseAndFineGrants() {
        assertEquals(
            LocationAccessLevel.NONE,
            SystemLocationProvider.resolveLocationAccess(fineGranted = false, coarseGranted = false),
        )
        assertEquals(
            LocationAccessLevel.COARSE_ONLY,
            SystemLocationProvider.resolveLocationAccess(fineGranted = false, coarseGranted = true),
        )
        assertEquals(
            LocationAccessLevel.FINE,
            SystemLocationProvider.resolveLocationAccess(fineGranted = true, coarseGranted = false),
        )
        assertEquals(
            LocationAccessLevel.FINE,
            SystemLocationProvider.resolveLocationAccess(fineGranted = true, coarseGranted = true),
        )

        assertTrue(
            SystemLocationProvider.selectCandidateProviders(LocationAccessLevel.NONE, supportsFused = true)
                .isEmpty(),
        )

        val coarseProviders = SystemLocationProvider.selectCandidateProviders(
            LocationAccessLevel.COARSE_ONLY,
            supportsFused = true,
        )
        assertFalse(SystemLocationProvider.PROVIDER_GPS in coarseProviders)
        assertFalse(SystemLocationProvider.PROVIDER_PASSIVE in coarseProviders)
        assertEquals(
            listOf(SystemLocationProvider.PROVIDER_FUSED, SystemLocationProvider.PROVIDER_NETWORK),
            coarseProviders,
        )

        val fineProvidersLegacy = SystemLocationProvider.selectCandidateProviders(
            LocationAccessLevel.FINE,
            supportsFused = false,
        )
        assertEquals(
            listOf(
                SystemLocationProvider.PROVIDER_GPS,
                SystemLocationProvider.PROVIDER_NETWORK,
                SystemLocationProvider.PROVIDER_PASSIVE,
            ),
            fineProvidersLegacy,
        )

        val best = SystemLocationProvider.selectBestLocationCandidate(
            listOf(
                LocationCandidate(0.0, 0.0, timeMs = 99_999L, accuracyMeters = 1f),
                LocationCandidate(Double.NaN, 116.4, timeMs = 99_999L, accuracyMeters = 1f),
                LocationCandidate(39.9001, 116.4001, timeMs = 1_000L, accuracyMeters = 5f),
                LocationCandidate(39.9042, 116.4074, timeMs = 2_000L, accuracyMeters = 45f),
                LocationCandidate(39.9050, 116.4080, timeMs = 2_000L, accuracyMeters = 12f),
            ),
        )
        assertNotNull(best)
        assertEquals(39.9050, best!!.latitude, 1e-6)
        assertEquals(116.4080, best.longitude, 1e-6)
    }

    @Test
    fun imageObfuscatorHelpersBoundSampleSizeAndPreserveAlpha() {
        assertEquals(1, AndroidImageObfuscator.calculateInSampleSize(4096, 3072))
        assertEquals(2, AndroidImageObfuscator.calculateInSampleSize(6000, 4000))
        assertEquals(4, AndroidImageObfuscator.calculateInSampleSize(10000, 8000))

        val original = (0x80 shl 24) or (255 shl 16) or (128 shl 8) or 0
        val perturbed = AndroidImageObfuscator.perturbArgbPixel(
            argb = original,
            rBias = 2,
            gBias = -1,
            bBias = -2,
            rJitter = 1,
            gJitter = 0,
            bJitter = -1,
        )
        assertEquals(0x80, (perturbed ushr 24) and 0xFF)
        assertEquals(255, (perturbed ushr 16) and 0xFF)
        assertEquals(127, (perturbed ushr 8) and 0xFF)
        assertEquals(0, perturbed and 0xFF)
    }

    private suspend inline fun <reified T : Throwable> assertThrowsSuspend(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (e is T) {
                return e
            }
            throw AssertionError(
                "Expected ${T::class.java.name} but threw ${e::class.java.name}: ${e.message}",
                e,
            )
        }
        throw AssertionError("Expected ${T::class.java.name} to be thrown, but completed normally")
    }
}
