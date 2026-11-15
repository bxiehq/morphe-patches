package app.bxiehq.patches.weibo

import app.morphe.patcher.patch.PatchException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OriginalApkTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { output ->
            entries.forEach { (name, data) ->
                output.putNextEntry(ZipEntry(name))
                output.write(data)
                output.closeEntry()
            }
        }
    }.toByteArray()

    @Test fun `standalone APK is copied byte for byte including signature entries`() {
        val directory = createTempDirectory().toFile()
        try {
            val original = zip("AndroidManifest.xml" to byteArrayOf(1, 2, 3), "META-INF/CERT.RSA" to byteArrayOf(9, 8))
            val source = File(directory, "original.apk").apply { writeBytes(original) }
            val target = File(directory, "assets/original.apk")
            copyOriginalBaseApk(source, target)
            assertContentEquals(original, target.readBytes())
            assertEquals(sha256(source), sha256(target))
        } finally { directory.deleteRecursively() }
    }

    @Test fun `APKM base is streamed intact without split APKs or re-zipping`() {
        val directory = createTempDirectory().toFile()
        try {
            val base = zip("AndroidManifest.xml" to byteArrayOf(1), "payload" to ByteArray(65537) { it.toByte() })
            val source = File(directory, "original.apkm").apply {
                writeBytes(zip("base.apk" to base, "split_config.arm64_v8a.apk" to byteArrayOf(7)))
            }
            val target = File(directory, "assets/original.apk")
            copyOriginalBaseApk(source, target)
            assertContentEquals(base, target.readBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `missing base fails and removes the partial output`() {
        val directory = createTempDirectory().toFile()
        try {
            val source = File(directory, "missing.apkm").apply { writeBytes(zip("split.apk" to byteArrayOf(1))) }
            val target = File(directory, "output.apk")
            assertFailsWith<PatchException> { copyOriginalBaseApk(source, target) }
            assertFalse(target.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `input cannot be overwritten by its own output`() {
        val directory = createTempDirectory().toFile()
        try {
            val source = File(directory, "original.apk").apply { writeBytes(zip("AndroidManifest.xml" to byteArrayOf(1))) }
            assertFailsWith<PatchException> { copyOriginalBaseApk(source, source) }
            assertTrue(source.isFile)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `stream copy enforces its size bound before writing excess data`() {
        val output = ByteArrayOutputStream()
        assertFailsWith<PatchException> { copyBounded(ByteArrayInputStream(ByteArray(5)), output, 4) }
        assertEquals(0, output.size())
    }
}
