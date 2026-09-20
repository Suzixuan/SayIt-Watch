package com.sayit.watch.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Delivery 1C Repair 1 + Repair 2 requirement 2 - source-level guards for the
 * invariants that keep an unauthenticated address from ever becoming an upload
 * target and keep exactly one resolution mode active.
 *
 * 1. `currentDestination()` returns ONLY the probe-authenticated target (there is
 *    no fallback to a saved value that merely passed format validation);
 * 2. both recording entry points (`recordButtonPressed` / `startRecording`) refuse
 *    to start without such a target;
 * 3. every resolution entry point goes through `ResolverBridge`, and Ready entry
 *    delegates to `onReadyEntered()` instead of unconditionally restarting
 *    discovery (Repair 2).
 *
 * These live at source level because constructing the ViewModel requires a
 * Context-bound `SettingsStore`, which this JVM unit-test environment cannot
 * provide (declared in the handoff's open-questions section). The behaviour of the
 * resolution chain itself is covered by `DiscoveryRepairTest` against the real
 * `ResolverBridge`/`ResolverEngine` and the production coordinator.
 */
class RecordingEntryGuardTest {

    private val viewModelSource: String by lazy {
        File("src/main/java/com/sayit/watch/ui/RecordingViewModel.kt").readText()
    }

    private val screenSource: String by lazy {
        File("src/main/java/com/sayit/watch/ui/RecordingScreen.kt").readText()
    }

    /** Extracts the body of the declaration starting at `start`. */
    private fun functionBody(source: String, start: Int): String {
        val open = source.indexOf('{', start)
        val lineEnd = source.indexOf('\n', start)
        // Single-expression declarations (`= expression`) have no brace of their own;
        // scanning forward would silently return the NEXT function's body.
        if (open < 0 || (lineEnd in 0 until open && source.substring(start, lineEnd).contains('='))) {
            return source.substring(start, lineEnd.coerceAtLeast(0))
        }
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
        }
        error("unbalanced braces")
    }

    /**
     * Finds a declaration by name. Anchoring on the declaration keyword matters: the
     * bare name also occurs in KDoc prose (`[currentDestination]`) and at call
     * sites, and neither may be mistaken for the declaration itself.
     */
    private fun bodyOf(signature: String): String {
        val name = signature.removePrefix("private fun ").removePrefix("fun ")
        val pattern = "\\bfun\\s+" + Regex.escape(name) + "(?![A-Za-z0-9_])"
        val match = Regex(pattern).find(viewModelSource)
        assertTrue("missing declaration in RecordingViewModel.kt: $signature", match != null)
        return functionBody(viewModelSource, match!!.range.first)
    }

    @Test
    fun `currentDestination returns only the verified destination`() {
        // Match the declaration exactly: the bare name also occurs inside `send()`.
        val body = bodyOf("fun currentDestination")
        assertTrue(
            "currentDestination must return exactly the probed target: $body",
            body.contains("verifiedDestination"),
        )
        // No fallback: it must not re-validate saved settings into a target.
        assertFalse(
            "currentDestination must not fall back to saved settings: $body",
            body.contains("DestinationValidator") || body.contains("settings.receiverIp"),
        )
        assertFalse(
            "currentDestination must not read the coordinator either: $body",
            body.contains("coordinator"),
        )
    }

    @Test
    fun `both record entry points require a verified destination`() {
        val pressed = bodyOf("fun recordButtonPressed")
        assertTrue(
            "recordButtonPressed must refuse without a verified target: $pressed",
            pressed.contains("_canRecord.value"),
        )
        val start = bodyOf("fun startRecording")
        assertTrue(
            "startRecording must refuse without a verified target: $start",
            start.contains("verifiedDestination == null"),
        )
        assertTrue(
            "startRecording must also respect the UI gate: $start",
            start.contains("_canRecord.value"),
        )
    }

    @Test
    fun `the verified destination is only set from an authenticated result`() {
        val body = bodyOf("private fun setVerifiedDestination")
        assertTrue(body.contains("verifiedDestination = selection"))
        assertTrue(body.contains("_canRecord.value = selection != null"))

        val applySettings = bodyOf("fun applySettings")
        assertFalse(
            "applySettings must never persist a manual address before the probe: $applySettings",
            applySettings.contains("settings.receiverIp =") || applySettings.contains("settings.receiverPort ="),
        )
        assertTrue(
            "applySettings must route a valid manual address through the resolver",
            applySettings.contains("resolver.onManualRequested"),
        )
    }

    @Test
    fun `every resolution entry point goes through the resolver bridge`() {
        // Repair 2 必修 2: one owner decides which mode resolves.
        assertTrue(
            "the ViewModel must own a ResolverBridge",
            viewModelSource.contains("ResolverBridge.forCoordinator("),
        )
        val ready = bodyOf("fun onReadyEntered")
        assertTrue("Ready entry must ask the resolver: $ready", ready.contains("resolver."))
        assertTrue(
            "Ready entry must not start a run unconditionally: $ready",
            ready.contains("acceptsAutomatic"),
        )

        val start = bodyOf("fun startDiscovery")
        assertTrue(
            "explicit re-resolution must release the old target first: $start",
            start.contains("onTargetInvalidated"),
        )

        val config = bodyOf("fun openConfig")
        assertTrue("opening Config must release the automatic run: $config", config.contains("onConfigEntered"))

        val stop = bodyOf("fun stopDiscovery")
        assertTrue("teardown must invalidate the run: $stop", stop.contains("onStopped"))
    }

    @Test
    fun `a failed manual probe does not write settings and clears the target`() {
        val applySettings = bodyOf("fun applySettings")
        assertTrue(applySettings.contains("resolver.onManualRequested"))
        val manual = applySettings.substring(applySettings.indexOf("resolver.onManualRequested"))
        assertTrue(
            "only a verified target may be accepted: $manual",
            manual.contains("resolver.verifiedTarget == candidate"),
        )
        assertFalse(
            "applySettings must not write the destination itself: $manual",
            manual.contains("settings.receiverIp ="),
        )
    }

    @Test
    fun `a failed upload drops the target and never re-sends the wav`() {
        val body = bodyOf("fun send")
        assertTrue(body.contains("setVerifiedDestination(null)"))
        assertTrue("the target must be invalidated for the next round", body.contains("onTargetInvalidated"))
        assertFalse(
            "send() must never loop or re-send the same audio: $body",
            body.contains("while (") || body.contains("retry("),
        )
    }

    @Test
    fun `the Ready screen delegates its entry decision to the ViewModel`() {
        assertTrue(
            "the screen must ask onReadyEntered instead of starting a run itself",
            screenSource.contains("viewModel.onReadyEntered()"),
        )
        assertFalse(
            "the screen must no longer call startDiscovery on every Ready entry",
            screenSource.contains("viewModel.startDiscovery()"),
        )
        assertTrue(
            "leaving the screen must still stop the resolution",
            screenSource.contains("viewModel.stopDiscovery()"),
        )
        assertTrue(
            "opening Config from the screen must release the automatic run",
            screenSource.contains("viewModel.openConfig()"),
        )
    }
}
