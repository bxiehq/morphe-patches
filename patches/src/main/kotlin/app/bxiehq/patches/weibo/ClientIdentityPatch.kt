package app.bxiehq.patches.weibo

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.filePathOption
import app.morphe.patcher.patch.listApkEntries
import app.morphe.patcher.patch.packageMetadata
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceFile
import app.morphe.patcher.patch.resourceXml
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val clientIdentityPatch = patch(
    name = "Repair Weibo client identity",
    description = "Preserves native initialization and the original signing data used by Weibo's client checks.",
    default = true,
) {
    compatibleWith(weiboCompatibility)
    extendWith("extensions/weibo.mpe")

    val originalApk = filePathOption(
        key = "originalApk",
        title = "Original Weibo APK or APKM",
        description = "Select the same untouched 16.9.0 (8150) download used as the patch input. Its signed base is embedded locally for native verification.",
        required = true,
        allowedExtensions = listOf("apk", "apkm"),
    )

    execute {
        if (packageMetadata.packageName != WEIBO_PACKAGE || packageMetadata.versionName != WEIBO_VERSION ||
            packageMetadata.versionCode != WEIBO_VERSION_CODE) {
            throw PatchException("This identity repair supports only Weibo $WEIBO_VERSION ($WEIBO_VERSION_CODE).")
        }
        if ("lib/arm64-v8a/libshadowhook.so" !in listApkEntries("lib/")) {
            throw PatchException("The original arm64-v8a split with Weibo's bundled ShadowHook library is required.")
        }

        val original = resourceFile("assets/$ORIGINAL_ASSET", copy = false)
        copyOriginalBaseApk(originalApk.file ?: throw PatchException("Set the Original Weibo APK or APKM option."), original)
        val certificate = verifiedOriginalCertificate(original)
        // Refuse an APK previously signed by a patch manager; initialization must come from stock input.
        val inputCertificates = packageMetadata.signingCertificates.values.flatten()
        if (inputCertificates.isNotEmpty() && inputCertificates.none { it.encoded.contentEquals(certificate.encoded) }) {
            throw PatchException("Patch a fresh original APKM, not an already patched or re-signed APK.")
        }
        resourceFile("assets/weibo-repair-cert.der", copy = false).apply {
            parentFile.mkdirs()
            writeBytes(certificate.encoded)
        }
        resourceFile("assets/weibo-repair.properties", copy = false).apply {
            parentFile.mkdirs()
            writeText("sha256=${sha256(original)}\nlength=${original.length()}\n")
        }
        val nativeLibrary = resourceFile("lib/arm64-v8a/libweiboidentity.so", copy = false)
        nativeLibrary.parentFile.mkdirs()
        val bundled = object {}.javaClass.classLoader.getResourceAsStream("weibo-native/arm64-v8a/libweiboidentity.so")
            ?: throw PatchException("The native identity extension is missing from this patch bundle.")
        bundled.use { input -> nativeLibrary.outputStream().use { input.copyTo(it) } }
        resourceXml("AndroidManifest.xml").use { manifest ->
            manifest.elements("application").single().setAttribute("android:extractNativeLibs", "true")
        }

        val init = Fingerprint(
            definingClass = SLIB,
            name = "init",
            parameters = listOf("Landroid/app/Application;", "Ljava/lang/String;"),
            returnType = "V",
        ).method
        val initialized = init.implementation!!.instructions.indexOfFirst { instruction ->
            val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
            instruction.opcode == Opcode.SPUT_BOOLEAN && field?.definingClass == SLIB && field?.name == "sInit"
        }
        if (initialized < 0) throw PatchException("SLib.init does not have the expected native initialization sequence.")
        init.addInstructions(initialized + 1, """
            const/4 v0, 0x1
            invoke-static {v0}, $IDENTITY->active(Z)V
            invoke-direct {p0}, $SLIB->nativeCheck()Z
            const/4 v0, 0x0
            invoke-static {v0}, $IDENTITY->active(Z)V
        """.trimIndent())
        init.addInstructions(0, "invoke-static {p1}, $IDENTITY->install(Landroid/content/Context;)V")

        val check = Fingerprint(
            definingClass = SLIB,
            name = "checkV2",
            parameters = emptyList(),
            returnType = "Z",
        ).method
        val instructions = check.implementation!!.instructions
        val call = instructions.indexOfFirst { instruction ->
            val method = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            method?.definingClass == SLIB && method?.name == "nativeCheck"
        }
        if (call < 0 || instructions.getOrNull(call + 1)?.opcode != Opcode.MOVE_RESULT) {
            throw PatchException("SLib.checkV2 must contain the original native verification call.")
        }
        // Preserve the actual native result. Only the APK metadata reads are redirected.
        check.addInstructions(call + 2, """
            const/4 v1, 0x0
            invoke-static {v1}, $IDENTITY->active(Z)V
        """.trimIndent())
        check.addInstructions(call, """
            const/4 v0, 0x1
            invoke-static {v0}, $IDENTITY->active(Z)V
        """.trimIndent())
    }
}
