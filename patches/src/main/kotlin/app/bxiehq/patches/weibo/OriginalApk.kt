package app.bxiehq.patches.weibo

import app.morphe.patcher.apk.ApkUtils
import app.morphe.patcher.patch.PatchException
import com.reandroid.apk.ApkModule
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

internal const val ORIGINAL_ASSET = "weibo-repair-original.apk"
internal const val MAX_ORIGINAL_APK_BYTES = 512L * 1024 * 1024

/** Streams the untouched base, including its signing block; never re-zips its entries. */
internal fun copyOriginalBaseApk(source: File, destination: File) {
    if (!source.isFile) throw PatchException("Select the original Weibo APK or APKM in the Original APK option.")
    if (source.canonicalFile == destination.canonicalFile) throw PatchException("Original APK and output must differ.")
    destination.parentFile?.mkdirs()
    try {
        ZipFile(source).use { archive ->
            if (archive.getEntry("AndroidManifest.xml") != null) {
                source.inputStream().use { input -> destination.outputStream().use { copyBounded(input, it) } }
            } else {
                val bases = archive.entries().asSequence().filter { it.name == "base.apk" && !it.isDirectory }.toList()
                if (bases.size != 1) throw PatchException("The APKM must contain exactly one untouched base.apk.")
                archive.getInputStream(bases.single()).use { input ->
                    destination.outputStream().use { copyBounded(input, it) }
                }
            }
        }
    } catch (failure: Exception) {
        destination.delete()
        throw failure
    }
}

internal fun copyBounded(input: InputStream, output: OutputStream, limit: Long = MAX_ORIGINAL_APK_BYTES) {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return
        total += count
        if (total > limit) throw PatchException("Original base APK exceeds the supported size limit.")
        output.write(buffer, 0, count)
    }
}

internal fun sha256(file: File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun verifiedOriginalCertificate(original: File): X509Certificate {
    ApkModule.loadApkFile(original).use { module ->
        val manifest = module.androidManifest
        if (manifest.packageName != WEIBO_PACKAGE || manifest.versionName != WEIBO_VERSION ||
            manifest.versionCode.toString() != WEIBO_VERSION_CODE) {
            throw PatchException("Original APK must be Weibo $WEIBO_VERSION ($WEIBO_VERSION_CODE).")
        }
    }
    val certificates = ApkUtils.verifiedSigningCertificates(original, 36)
        ?: throw PatchException("Could not read the original APK signing certificate.")
    return certificates.firstOrNull { certificate ->
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) } == WEIBO_SIGNER_SHA256
    } ?: throw PatchException("Original APK is unsigned, modified, or does not have Weibo's expected signing certificate.")
}
