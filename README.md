# BX Weibo Patches

Patches for Weibo **16.9.0 (8150), arm64-v8a**, compatible with Morphe.
The original repairs were verified on Android 16. The extension requires Android 10 or newer.

[Add this source to Morphe](https://morphe.software/add-source?github=bxiehq/morphe-patches)

- **Repair Weibo client identity** enables native-library extraction, preserves `SLib.init()`,
  and supplies the original signing metadata to Weibo's Java and native checks. The real
  `checkV2()` result is retained.
- **Hide splash ads** skips cold-start and return-from-background ads through the shared
  `FlashAd` presentation paths. Loading/readiness callbacks remain intact so startup can
  proceed. The legacy ad activities call `super.onCreate()` before closing.

## Apply

1. Use the untouched Weibo 16.9.0 (8150) APKM as Morphe's input. Let Morphe process the APKM directly.
2. Enable both patches from this source.
3. In **Repair Weibo client identity → Original Weibo APK or APKM**, select that same untouched
   download, or its untouched signed `base.apk`.
4. Patch, then install using your existing Morphe signing key to preserve update compatibility.

The original-file option is required because native identity verification reads the signed APK
file directly; a Java-only signature override does not cover that check. The signed base APK is
copied at patch time into the patched app and extracted into its private files directory at runtime.
This adds approximately the original base APK's size to the patched APK and private storage.
No APK, account data, APK signing key, or phone-specific path is distributed in this repository.

Use fresh stock input. Disable Bestweb's **Bypass signature check** and **Hide splash ads** when
using these replacements. Its other patches can be selected independently; these two patches
preserve initialization and the callbacks that the original bypasses skipped.

## Build and verify

Use JDK 21, Android SDK 36, NDK `27.2.12479018`, and CMake `3.22.1`. Configure GitHub Packages
credentials as required by the Morphe template (`GITHUB_ACTOR` and `GITHUB_TOKEN`, or Gradle's
`gpr.user` / `gpr.key`).

```sh
./gradlew :patches:test :patches:verifyWeiboBundle
cc -std=c11 -Wall -Wextra -Werror tests/native/identity_paths_test.c -o /tmp/identity-paths-test
/tmp/identity-paths-test
```

The `.mpp` is generated under `patches/build/libs/`. The bundle check verifies that the identity
DEX and native library are included and that no APK or keystore has been bundled accidentally.

See [implementation and validation notes](docs/weibo-repair.md). GPLv3; see [LICENSE](LICENSE)
and the retained Morphe template [NOTICE](NOTICE).
