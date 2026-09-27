package com.sayit.watch.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** Static contract for the single-client Wear OS 3+ compatibility slice. */
class WearOsCompatibilityContractTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun manifestRequiresWatchMicrophoneAndWifi() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        for (feature in listOf(
            "android.hardware.type.watch",
            "android.hardware.microphone",
            "android.hardware.wifi",
        )) {
            assertTrue("manifest must require $feature", manifest.contains("android:name=\"$feature\""))
        }
        assertTrue(
            "all three compatibility features must be required",
            Regex("android:required=\"true\"").findAll(manifest).count() >= 3,
        )
        assertTrue(
            "the Wear OS app must declare that it works without a phone companion",
            Regex(
                "android:name=\"com\\.google\\.android\\.wearable\\.standalone\"[\\s\\S]*?android:value=\"true\"",
            ).containsMatchIn(manifest),
        )
    }

    @Test
    fun oneApkHasNoSamsungSdkDependency() {
        val gradle = File("build.gradle.kts").readText()
        val sourceImports = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        assertFalse("generic APK must not depend on a Samsung SDK", gradle.contains("com.samsung", ignoreCase = true))
        assertFalse(
            "generic APK must not import Samsung-only APIs",
            Regex("(?m)^\\s*import\\s+com\\.samsung\\.").containsMatchIn(sourceImports),
        )
        assertTrue("Wear OS 3+ baseline must remain API 30", gradle.contains("minSdk = 30"))
    }

    @Test
    fun installerFailsClosedBeforeInstallingAnIncompatibleDevice() {
        val installer = File("../../client/scripts/Install-Watch.ps1").absoluteFile.readText()
        val installIndex = installer.indexOf("install -r")

        assertTrue("installer must contain the APK install command", installIndex >= 0)
        for (probe in listOf(
            "ro.build.version.sdk",
            "android.hardware.type.watch",
            "android.hardware.microphone",
            "android.hardware.wifi",
        )) {
            val probeIndex = installer.indexOf(probe)
            assertTrue("installer must check $probe", probeIndex >= 0)
            assertTrue("installer must check $probe before installing", probeIndex < installIndex)
        }
        assertTrue("installer must enforce API 30+", installer.contains("sdk -lt 30"))
    }

    @Test
    fun readmeStatesSupportedAndUnsupportedPlatformsWithoutOverclaiming() {
        val readme = File("../../README.md").absoluteFile.readText()

        assertTrue(readme.contains("Wear OS 3"))
        assertTrue(readme.contains("API 30+"))
        for (unsupported in listOf("Apple Watch", "HarmonyOS", "Zepp OS", "Garmin", "Tizen", "Fitbit OS")) {
            assertTrue("README must identify $unsupported as incompatible", readme.contains(unsupported))
        }
        assertTrue(
            "README must distinguish target compatibility from real-device evidence",
            readme.contains("不能把“同平台可安装”写成“所有型号已经实测”"),
        )
    }

    @Test
    fun installerRejectsAPhoneBeforeRunningAdbInstall() {
        val result = runInstallerWithFakeDevice(isWatch = false)

        assertTrue("incompatible phone must fail:\n${result.output}", result.exitCode != 0)
        assertFalse("installer must stop before adb install", result.adbLog.contains(" install -r "))
    }

    @Test
    fun installerContinuesForApi30WatchWithMicrophoneAndWifi() {
        val result = runInstallerWithFakeDevice(isWatch = true)

        assertEquals("compatible Wear OS fixture must install:\n${result.output}", 0, result.exitCode)
        assertTrue("compatible fixture must run adb install", result.adbLog.contains(" install -r "))
    }

    @Test
    fun setupPreservesReceiverSnakeCaseContract() {
        val fixture = temporaryFolder.newFolder("setup-config")
        val sourceSetup = File("../../client/scripts/Setup-PC.ps1").absoluteFile
        val setup = File(fixture, "Setup-PC.ps1")
        sourceSetup.copyTo(setup)
        val token = "b".repeat(64)
        val config = File(fixture, "watch-receiver.config.json")
        config.writeText("""{"bind_ip":"192.168.12.9","port":18099,"dev_token":"$token"}""")

        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", setup.absolutePath,
            "-NoClipboard",
            "-BindIp", "192.168.12.10",
            "-ConfigPath", config.absolutePath,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue("setup fixture must finish:\n$output", finished)
        assertEquals("setup must accept the receiver's snake_case config:\n$output", 0, process.exitValue())

        val rewritten = config.readText()
        assertTrue("setup must write bind_ip", rewritten.contains("\"bind_ip\":\"192.168.12.10\""))
        assertTrue("setup must preserve dev_token", rewritten.contains("\"dev_token\":\"$token\""))
        assertFalse("setup must not emit legacy bindIp", rewritten.contains("\"bindIp\""))
        assertFalse("setup must not emit legacy devToken", rewritten.contains("\"devToken\""))
    }

    private data class InstallerResult(val exitCode: Int, val output: String, val adbLog: String)

    private fun runInstallerWithFakeDevice(isWatch: Boolean): InstallerResult {
        val fixture = temporaryFolder.newFolder(if (isWatch) "wear-watch" else "android-phone")
        val sourceInstaller = File("../../client/scripts/Install-Watch.ps1").absoluteFile
        val installer = File(fixture, "Install-Watch.ps1")
        sourceInstaller.copyTo(installer)
        File(fixture, "SayIt-Watch.apk").writeBytes(byteArrayOf(0x53, 0x41, 0x59))
        val config = File(fixture, "watch-receiver.config.json")
        config.writeText("""{"dev_token":"${"a".repeat(64)}"}""")
        val adbLog = File(fixture, "adb.log")
        val fakeAdb = File(fixture, "adb.cmd")
        val watchFeature = if (isWatch) "echo feature:android.hardware.type.watch" else "rem phone fixture"
        fakeAdb.writeText(
            """
            @echo off
            echo %*>>"%FAKE_ADB_LOG%"
            if "%3"=="get-state" (echo device& exit /b 0)
            if "%3"=="install" (echo Success& exit /b 0)
            if "%3"=="exec-out" (echo name="dev_token"& exit /b 0)
            if "%3"=="shell" if "%4"=="getprop" if "%5"=="ro.build.version.sdk" (echo 34& exit /b 0)
            if "%3"=="shell" if "%4"=="getprop" if "%5"=="ro.product.manufacturer" (echo TestMaker& exit /b 0)
            if "%3"=="shell" if "%4"=="getprop" if "%5"=="ro.product.model" (echo TestWatch& exit /b 0)
            if "%3"=="shell" if "%4"=="pm" if "%5"=="list" if "%6"=="features" (
              $watchFeature
              echo feature:android.hardware.microphone
              echo feature:android.hardware.wifi
              exit /b 0
            )
            exit /b 0
            """.trimIndent(),
        )

        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", installer.absolutePath,
            "-Device", "fake-watch",
            "-AdbPath", fakeAdb.absolutePath,
            "-ConfigPath", config.absolutePath,
        ).redirectErrorStream(true).apply {
            environment()["FAKE_ADB_LOG"] = adbLog.absolutePath
        }.start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue("installer fixture must finish:\n$output", finished)
        return InstallerResult(process.exitValue(), output, if (adbLog.isFile) adbLog.readText() else "")
    }
}
