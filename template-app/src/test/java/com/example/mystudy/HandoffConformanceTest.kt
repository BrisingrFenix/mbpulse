package com.example.mystudy

import android.content.ContextWrapper
import org.junit.AfterClass
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Checks this app against `handoff.json`, the setup wizard's machine-readable
 * copy of AGENT_BRIEF.md (written into this folder in step 4):
 *
 *     .\gradlew.bat testDebugUnitTest
 *
 * One test per area; a failure names what to fix. Every result, including the
 * lines to read in the audit, is written to `build/reports/handoff-check.md` —
 * hand that to the person who reviews the app.
 *
 * Never edit handoff.json or loosen a check to make it pass: a failure means
 * the app does not do what the study asked. Regenerate the brief in the wizard
 * if the study itself changed.
 */
class HandoffConformanceTest {

    companion object {
        // TODO(agent): the CSV the processor check replays. Keep the bundled one
        // while developing on simulated data; for the real sensor, add a short
        // recording in the same format (timestamp column + one column per signal)
        // and point this at it — the processor must emit every study schema from it.
        val REPLAY = ReplaySpec(path = "src/main/assets/sample_data.csv")

        // TODO(agent): schemas the study maps that the processor does NOT emit,
        // each with the reason (e.g. "imported from Health Connect by
        // StepsSource"). Leave empty unless that is true — the report lists
        // every entry for the reviewer.
        val NOT_FROM_PROCESSOR: Map<String, String> = emptyMap()

        private const val TEMPLATE_APPLICATION_ID = "com.example.mystudy"

        private val handoffFile = File(System.getProperty("mbpulse.handoff") ?: "handoff.json")
        private val projectDir = File(System.getProperty("mbpulse.projectDir") ?: ".")

        private val findings: List<Finding> by lazy {
            HandoffCheck(
                handoff = Handoff.read(handoffFile),
                // The context is only captured by the factories; the check never starts the real sensor.
                config = studyConfig(ContextWrapper(null)),
                projectDir = projectDir,
                replay = REPLAY,
                notFromProcessor = NOT_FROM_PROCESSOR,
                applicationId = BuildConfig.APPLICATION_ID,
                appPackage = BuildConfig::class.java.`package`!!.name
            ).run()
        }

        @AfterClass @JvmStatic
        fun writeReport() {
            if (!handoffFile.exists()) return
            val report = File(projectDir, "build/reports/handoff-check.md")
            report.parentFile.mkdirs()
            report.writeText(buildString {
                appendLine("# Handoff check — ${BuildConfig.APPLICATION_ID}")
                appendLine()
                appendLine("Against `${handoffFile.name}`. FAIL fails the build; WARN is for the reviewer.")
                val counts = findings.groupingBy { it.level }.eachCount()
                appendLine("**${counts[Level.FAIL] ?: 0} FAIL, ${counts[Level.WARN] ?: 0} WARN, ${counts[Level.OK] ?: 0} OK.**")
                Area.entries.forEach { area ->
                    appendLine(); appendLine("## ${area.title}"); appendLine()
                    findings.filter { it.area == area }.forEach { appendLine("- **${it.level}** ${it.message}") }
                }
            })
        }
    }

    private fun check(area: Area) {
        if (!handoffFile.exists()) {
            // The template itself has no study; a study app always has one.
            assumeTrue("no handoff.json — the template is checked against a study's handoff",
                BuildConfig.APPLICATION_ID != TEMPLATE_APPLICATION_ID)
            fail("handoff.json is missing — the setup wizard writes it into the app folder (step 4: write the brief)")
        }
        val failures = findings.filter { it.area == area && it.level == Level.FAIL }
        if (failures.isNotEmpty()) fail("${area.title}:\n" + failures.joinToString("\n") { " - ${it.message}" })
    }

    @Test fun identity() = check(Area.IDENTITY)
    @Test fun backend() = check(Area.BACKEND)
    @Test fun pinnedKeys() = check(Area.KEYS)
    @Test fun collectionMode() = check(Area.COLLECTION)
    @Test fun schemaToConsentType() = check(Area.MAPPING)
    @Test fun customSchemas() = check(Area.SCHEMAS)
    @Test fun signalProcessor() = check(Area.PROCESSOR)
    @Test fun manifest() = check(Area.MANIFEST)
}
