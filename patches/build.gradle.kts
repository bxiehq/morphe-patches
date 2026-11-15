import org.gradle.language.jvm.tasks.ProcessResources
import java.util.zip.ZipFile

group = "app.bxiehq"

patches {
    about {
        name = "BX Weibo Patches"
        description = "Weibo client identity repair and splash-ad blocking"
        source = "https://github.com/bxiehq/morphe-patches"
        author = "bxiehq"
        contact = "na"
        website = "https://github.com/bxiehq/morphe-patches"
        license = "GPLv3"
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    compileOnly(libs.arsclib)
    compileOnly(libs.gson)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.morphe.patcher)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.arsclib)
    patchListGeneratorClasspath(libs.gson)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}

tasks.test { useJUnitPlatform() }

// The template's .mpe artifact contains DEX only. Stage the JNI library separately.
tasks.named<ProcessResources>("processResources") {
    dependsOn(":extensions:extension:mergeReleaseNativeLibs")
    from(project(":extensions:extension").layout.buildDirectory.dir(
        "intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib"
    )) {
        include("arm64-v8a/libweiboidentity.so")
        into("weibo-native")
    }
}

tasks.register("verifyWeiboBundle") {
    dependsOn("buildAndroid")
    doLast {
        val bundle = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        ZipFile(bundle).use { zip ->
            val dex = zip.getEntry("extensions/weibo.mpe") ?: error("Identity DEX is missing")
            zip.getInputStream(dex).use { check(it.readNBytes(4).contentEquals("dex\n".toByteArray())) }
            val native = zip.getEntry("weibo-native/arm64-v8a/libweiboidentity.so")
                ?: error("Native identity library is missing")
            zip.getInputStream(native).use {
                check(it.readNBytes(4).contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46)))
            }
            check(zip.entries().asSequence().none { it.name.endsWith(".apk") || it.name.endsWith(".keystore") })
        }
    }
}
