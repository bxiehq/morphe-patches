package app.bxiehq.patches.weibo

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.SupportedAbi

internal const val WEIBO_PACKAGE = "com.sina.weibo"
internal const val WEIBO_VERSION = "16.9.0"
internal const val WEIBO_VERSION_CODE = "8150"
internal const val WEIBO_SIGNER_SHA256 = "a078c30f0a58e63d06ca5fc7c42195f6a4940215d4bcd23d940b7ea03e86379d"
internal const val IDENTITY = "Lapp/bxiehq/weibo/Identity;"
internal const val SLIB = "Lcom/sina/weibo/security/SLib;"

internal val weiboCompatibility = Compatibility(
    packageName = WEIBO_PACKAGE,
    name = "Weibo",
    apkFileType = ApkFileType.APKM,
    appIconColor = 0xFF6600,
    signatures = setOf(WEIBO_SIGNER_SHA256),
    targets = listOf(AppTarget(
        version = WEIBO_VERSION,
        versionCodes = mapOf(SupportedAbi.ARM64_V8A to 8150),
        minSdk = 29,
        description = "Verified on Android 16 / arm64; other architectures are unsupported.",
    )),
)
