package com.example.mystudy

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import pt.ua.mbpulse.MbPulseLibrary
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Study-app template. The mbPulse library owns crypto, consent, storage, sync
 * and the UI; [studyConfig] (StudyConfig.kt) is where a specific study wires
 * in its own pieces.
 *
 * Out of the box it runs end-to-end on **simulated data** (a bundled CSV
 * replayed as if it were a live sensor), so the whole app can be exercised —
 * enrolment, consent, collection, history, withdrawal — before any real
 * sensor code exists.
 */
@HiltAndroidApp
class TemplateApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()

        // Credentials come from local.properties (mbpulse.supabaseUrl /
        // mbpulse.supabaseKey) via BuildConfig — never hardcode them here.
        check(BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_KEY.isNotBlank()) {
            "Missing backend credentials: add mbpulse.supabaseUrl and mbpulse.supabaseKey " +
                "to local.properties, then rebuild."
        }

        // The study's wiring lives in StudyConfig.kt (checked by
        // HandoffConformanceTest against the wizard's handoff.json).
        MbPulseLibrary.init(studyConfig(applicationContext))
    }

    override val workManagerConfiguration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
