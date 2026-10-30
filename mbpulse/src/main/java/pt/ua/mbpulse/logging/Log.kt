package pt.ua.mbpulse.logging

import android.content.Context
import androidx.startup.Initializer
import pt.ua.mbpulse.collection.CollectionRequirements

/**
 * The library's logcat output — a drop-in for `android.util.Log` (same calls;
 * library code imports this one instead).
 *
 * Debug and info messages name pseudonyms, dump study definitions and server
 * rows: useful while developing, a leak in a participant's phone (bug reports,
 * `adb logcat`). So they are written only when the **host app** is a debuggable
 * build. Warnings and errors are always written — they are developer
 * diagnostics (a mis-mapped schema, a failed upload) and must not contain
 * participant identifiers or server data.
 */
internal object Log {

    /** Debug/info enabled — the host app is debuggable ([LogInitializer], before `Application.onCreate`). */
    @Volatile
    var verbose: Boolean = false

    fun v(tag: String, msg: String): Int = if (verbose) android.util.Log.v(tag, msg) else 0
    fun d(tag: String, msg: String): Int = if (verbose) android.util.Log.d(tag, msg) else 0
    fun d(tag: String, msg: String, tr: Throwable): Int = if (verbose) android.util.Log.d(tag, msg, tr) else 0
    fun i(tag: String, msg: String): Int = if (verbose) android.util.Log.i(tag, msg) else 0
    fun i(tag: String, msg: String, tr: Throwable): Int = if (verbose) android.util.Log.i(tag, msg, tr) else 0

    fun w(tag: String, msg: String): Int = android.util.Log.w(tag, msg)
    fun w(tag: String, msg: String, tr: Throwable): Int = android.util.Log.w(tag, msg, tr)
    fun e(tag: String, msg: String): Int = android.util.Log.e(tag, msg)
    fun e(tag: String, msg: String, tr: Throwable): Int = android.util.Log.e(tag, msg, tr)
}

/**
 * Sets [Log.verbose] from the host app's debuggable flag at process start —
 * every process (UI, collection service, WorkManager, boot receiver), before
 * any library code runs. Registered in the library manifest on androidx.startup's
 * `InitializationProvider`, which host apps keep (they only remove WorkManager's
 * own initializer). Should a host remove the whole provider, debug logging
 * simply stays off — the safe default.
 */
internal class LogInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        Log.verbose = CollectionRequirements.isDebuggableBuild(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
