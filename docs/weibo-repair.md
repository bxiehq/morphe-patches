# Weibo repair details

The target is intentionally pinned to Weibo 16.9.0, version code 8150, arm64-v8a.
The original certificate SHA-256 is declared in `WeiboCompatibility.kt`; the original-file
option is checked for package, version, intact APK signing, and the expected certificate.
Unrelated versions, unsigned inputs, and already re-signed inputs fail during patching.

## Native loading and identity

`android:extractNativeLibs=true` gives Weibo's protected `libslib.so` payload a normal library
path. In the original failure, loading from the merged APK returned “Not found library”.
`SLib.init()` must still assign the application context, invoke `nativeInit`, and initialize
local storage. Marking `sInit=true` without doing that causes missing JNI methods or invalid
request identity.

The extension adapts package-info certificate lookups inside the Weibo process. It also uses
Weibo's own bundled ShadowHook library to adapt `__open_2`, `fopen`, and `readlink` while
`SLib.nativeCheck()` runs. A thread-local flag and exact path comparisons scope that adapter
to the installed APK being verified. Reads from other threads, other APKs, and ordinary asset
loads are unaffected. The Android package itself retains the patch manager's signing key.

The original signed APK is streamed intact from the selected APK/APKM. Runtime extraction
checks the copy's length and SHA-256 and publishes it under a cross-process file lock, so the
main and remote processes cannot read a partially written copy. The native helper is built
from source and loads the target's ShadowHook dynamically; no proprietary native binary is
committed.

The app's original `checkV2()` still invokes its native check and returns its actual result.
The patch does not suppress WBSecurity's initialization thread or force verification success.

## Splash presentation

The older Bestweb patch only finished two legacy ad activities. Weibo also renders splash ads
inside a `FlashAd` dialog, including on return from the background. This patch disables
background-ad eligibility and skips both direct presentation methods. The existing
`onSkip(false)` path advances any pending intent and sends dismissal callbacks; the direct
load-and-show path reports `NO_FILL` through the normal listener.

Do not make `FlashAd.isReady()` or `loadAdWithLoadManager()` always return false. Device
validation showed that doing so can strand startup on its branding page while it waits for
loading callbacks. Those methods remain unchanged.

## Validation provenance

The original device repair passed native identity verification (`true`, error code `0`) in
both the main and remote processes. The user confirmed that the client-identity error was
resolved, and later confirmed that neither cold start nor return to the app showed splash
ads. The patches here port those verified control-flow changes into a reusable bundle.

Repository checks cover lossless APK/APKM base copying, copy limits and error cleanup,
exact native path matching, inactive-hook behavior, Kotlin/Java/JNI compilation, and bundle
contents. A fresh bundle should additionally be applied to stock input and checked on a
device before expanding the compatibility declaration beyond the tested target.
