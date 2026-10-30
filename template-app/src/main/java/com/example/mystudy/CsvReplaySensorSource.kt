package com.example.mystudy

import android.content.Context
import android.util.Log
import pt.ua.mbpulse.collection.SensorSample
import pt.ua.mbpulse.collection.SensorSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.BufferedReader
import java.io.InputStreamReader

private const val TAG = "CsvReplaySensorSource"

/**
 * Simulated [SensorSource]: replays a CSV from the app's assets as if it were a
 * live sensor. This is how a study app is developed and verified **before** the
 * real device driver exists — the entire library pipeline (processing, consent
 * filtering, encryption, sync, UI) runs unmodified on replayed data.
 *
 * Lives in the host app on purpose: the library is completely sensor-agnostic —
 * every signal source, simulated or real, is an implementation the host
 * provides.
 *
 * ## CSV format
 * Header-driven: the first line names the columns. One column (default
 * `timestamp_ms`) is the sample timestamp; every other column becomes a signal
 * in [SensorSample.signals] under its header name (customisable via
 * [columnToSignal]). Blank/unparseable cells emit `null` for that signal.
 *
 * ```csv
 * timestamp_ms,hr,acc_x,acc_y,acc_z,temp,eda
 * 0,66.2,0.01,-0.02,0.98,33.1,0.31
 * 1000,66.8,0.02,-0.01,0.99,33.1,0.32
 * ```
 *
 * ## Behaviour
 * - Emission is paced by the **timestamp deltas** in the file divided by
 *   [speedFactor] (gaps capped at [maxDelayMs] so dataset holes don't stall
 *   the replay).
 * - [rebaseToNow] (default) shifts all timestamps so the first sample is "now",
 *   keeping deltas intact — replayed data lands under "Today" in the history
 *   screens instead of at the dataset's original date.
 * - [loop] restarts the file when exhausted, shifting timestamps forward so
 *   they stay strictly increasing (epoch windows keep working). With
 *   [rebaseToNow], each new pass also re-syncs with the wall clock: it waits
 *   until the clock reaches the pass's first timestamp, or starts at "now"
 *   after a pause — so looping never drifts into the future.
 * - [speedFactor] above 1 replays faster than real time, so *within one pass*
 *   timestamps run ahead of the clock (a 30 min file at 30× ends ~29 min in
 *   the future). Fine for a quick demo; keep 1.0 for passive collection, which
 *   loops for as long as the app runs.
 *
 * @param assetFileName        CSV file under `src/main/assets/`.
 * @param timestampColumn      Header name of the timestamp column.
 * @param timestampMultiplier  Multiplier to convert the column to milliseconds
 *                             (1.0 for ms, 1000.0 for seconds).
 * @param speedFactor          Playback speed. 1.0 = real time, 30.0 = 30× faster.
 * @param loop                 Restart from the top when the file ends.
 * @param rebaseToNow          Shift timestamps so playback starts at wall-clock now.
 * @param maxDelayMs           Cap on the pause between two samples (post-speedFactor).
 * @param columnToSignal       Maps a header name to a signal name; return null
 *                             to skip that column. Default: header name as-is.
 */
class CsvReplaySensorSource(
    private val context: Context,
    private val assetFileName: String,
    private val timestampColumn: String = "timestamp_ms",
    private val timestampMultiplier: Double = 1.0,
    private val speedFactor: Double = 1.0,
    private val loop: Boolean = false,
    private val rebaseToNow: Boolean = true,
    private val maxDelayMs: Long = 1_000L,
    private val columnToSignal: (columnName: String) -> String? = { it }
) : SensorSource {

    override fun samples(): Flow<SensorSample> = flow {
        var passShiftMs = 0L      // keeps timestamps increasing across loop passes
        var rebaseOffsetMs = 0L   // maps the file's first timestamp to "now"
        var fileFirstRawMs = 0L
        var firstPass = true

        do {
            var firstRawMs: Long? = null
            var lastRawMs = 0L
            var lastDeltaMs = 1_000L
            var emitted = 0

            BufferedReader(InputStreamReader(context.assets.open(assetFileName))).use { br ->
                if (firstPass) Log.d(TAG, "Replaying $assetFileName at ${speedFactor}×")

                for (raw in readSamples(br, assetFileName, timestampColumn, timestampMultiplier, columnToSignal)) {
                    val rawMs = raw.timestampMs
                    if (firstRawMs == null) {
                        firstRawMs = rawMs
                        if (firstPass) {
                            fileFirstRawMs = rawMs
                            if (rebaseToNow) rebaseOffsetMs = System.currentTimeMillis() - rawMs
                        }
                    } else {
                        val deltaMs = (rawMs - lastRawMs).coerceAtLeast(0)
                        if (deltaMs > 0) lastDeltaMs = deltaMs
                        delay((deltaMs / speedFactor).toLong().coerceIn(0, maxDelayMs))
                    }
                    lastRawMs = rawMs

                    emit(SensorSample(rawMs + rebaseOffsetMs + passShiftMs, raw.signals))
                    emitted++
                }
            }

            Log.d(TAG, "Pass complete — $emitted samples" + if (loop) " (looping)" else "")
            // Next pass continues right after this one on the emitted timeline.
            passShiftMs += (lastRawMs - (firstRawMs ?: lastRawMs)) + lastDeltaMs
            if (loop && rebaseToNow) {
                // Re-sync with the wall clock: never start a pass in the future
                // (a fast replay would otherwise drift further every pass), and
                // after a pause start at "now" rather than in the past.
                val aheadMs = fileFirstRawMs + rebaseOffsetMs + passShiftMs - System.currentTimeMillis()
                if (aheadMs > 0) delay(aheadMs) else passShiftMs -= aheadMs
            }
            firstPass = false
        } while (loop)
    }

    companion object {
        /**
         * The rows of a CSV in this format as samples, with the file's own
         * timestamps (no pacing, rebasing or looping) — what one replay pass
         * emits before its time shift. Unparseable rows are skipped. Pure: also
         * used by HandoffConformanceTest to run the processor on a CSV.
         *
         * @param name for error messages (the file name).
         */
        fun readSamples(
            reader: BufferedReader,
            name: String,
            timestampColumn: String = "timestamp_ms",
            timestampMultiplier: Double = 1.0,
            columnToSignal: (columnName: String) -> String? = { it }
        ): Sequence<SensorSample> = sequence {
            val header = reader.readLine() ?: error("$name is empty — no header row")
            val columns = header.split(",").map { it.trim() }
            val tsIndex = columns.indexOf(timestampColumn)
            require(tsIndex >= 0) { "$name has no '$timestampColumn' column (header: $header)" }
            // column index -> signal name, for every mapped non-timestamp column
            val signalColumns = columns.mapIndexedNotNull { i, column ->
                if (i == tsIndex) null else columnToSignal(column)?.let { signal -> i to signal }
            }
            require(signalColumns.isNotEmpty()) { "$name maps to no signals — check columnToSignal" }

            while (true) {
                val cells = (reader.readLine() ?: break).split(",")
                val rawMs = cells.getOrNull(tsIndex)?.trim()?.toDoubleOrNull()
                    ?.let { (it * timestampMultiplier).toLong() }
                    ?: continue   // skip unparseable rows
                val signals = buildMap {
                    signalColumns.forEach { (i, signal) -> put(signal, cells.getOrNull(i)?.trim()?.toFloatOrNull()) }
                }
                yield(SensorSample(rawMs, signals))
            }
        }
    }
}
