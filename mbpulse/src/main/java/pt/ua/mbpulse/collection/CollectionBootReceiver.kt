package pt.ua.mbpulse.collection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import pt.ua.mbpulse.logging.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Resumes passive collection after a reboot or an app update — passive
 * collection must not depend on the participant opening the app again.
 *
 * Android allows `connectedDevice` / `health` foreground services to start
 * from BOOT_COMPLETED (Android 15+ forbids it for `dataSync`, which is why
 * session mode never auto-starts). No-op in session mode or when no passive
 * session is stored (not enrolled, withdrawn). The Application has already run
 * `MbPulseLibrary.init` by the time this receiver is called.
 */
internal class CollectionBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                PassiveCollection.resume(context.applicationContext)
            } catch (e: Exception) {
                Log.e("CollectionBootReceiver", "Could not resume passive collection: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }
}
