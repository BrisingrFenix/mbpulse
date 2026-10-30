package com.clinicalTests

import android.content.Context
import android.util.Log
import pt.ua.mbpulse.collection.SensorSample
import pt.ua.mbpulse.collection.SensorSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.BufferedReader
import java.io.InputStreamReader

private const val TAG = "DreamtSensorSource"

/**
 * Reference [SensorSource] implementation that replays the DREAMT dataset CSV.
 *
 * Expected CSV format (column order):
 *   timestamp, bvp, acc_x, acc_y, acc_z, temp, eda, hr, ibi, sleep_stage, ...
 *
 * Timestamps are in seconds (float). The source converts to milliseconds.
 * Place the file at: app/src/main/assets/dreamt_patient.csv
 *
 * @param speedFactor Playback speed multiplier. 1.0 = real time, 10.0 = 10× faster.
 */
class DreamtSensorSource(
    private val context: Context,
    private val assetFileName: String = "dreamt_patient.csv",
    private val speedFactor: Double   = 10.0
) : SensorSource {

    private val emitDelayMs = ((1000.0 / 64.0) / speedFactor).toLong().coerceAtLeast(1L)

    override fun samples(): Flow<SensorSample> = flow {
        Log.d(TAG, "Opening $assetFileName at ${speedFactor}× speed")

        val reader = BufferedReader(
            InputStreamReader(context.assets.open(assetFileName))
        )

        reader.use { br ->
            val header = br.readLine()
            Log.d(TAG, "Header: $header")

            var totalLines  = 0
            var parsedLines = 0
            var line: String?

            while (br.readLine().also { line = it } != null) {
                totalLines++
                val sample = parseLine(line!!) ?: continue
                parsedLines++

                if (parsedLines == 1) Log.d(TAG, "First sample: $sample")
                if (parsedLines % 640 == 0) Log.d(TAG, "Emitted $parsedLines samples")

                emit(sample)
                delay(emitDelayMs)
            }

            Log.d(TAG, "Complete — total: $totalLines, parsed: $parsedLines")
        }
    }

    private fun parseLine(line: String): SensorSample? {
        val cols = line.split(",")
        if (cols.size < 10) return null

        return try {
            SensorSample(
                timestampMs = (cols[0].trim().toDouble() * 1000).toLong(),
                signals     = mapOf(
                    "bvp"   to cols[1].trim().toFloatOrNull(),
                    "acc_x" to cols[2].trim().toFloatOrNull(),
                    "acc_y" to cols[3].trim().toFloatOrNull(),
                    "acc_z" to cols[4].trim().toFloatOrNull(),
                    "temp"  to cols[5].trim().toFloatOrNull(),
                    "eda"   to cols[6].trim().toFloatOrNull(),
                    "hr"    to cols[7].trim().toFloatOrNull(),
                    "ibi"   to cols[8].trim().toFloatOrNull()
                )
            )
        } catch (e: NumberFormatException) {
            null
        }
    }
}