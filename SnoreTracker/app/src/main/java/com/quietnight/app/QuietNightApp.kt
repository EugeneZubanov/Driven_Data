package com.quietnight.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.quietnight.app.data.SessionRepository
import com.quietnight.app.data.Settings

class QuietNightApp : Application() {
    private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var repository: SessionRepository
        private set
    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        repository = SessionRepository(filesDir).also { it.loadInBackground() }
        settings = Settings(this)
        // Remove old clips and whole-night audio if the user asked for it (off the main thread).
        com.quietnight.app.data.StorageCleanup.runInBackground(repository, settings.prefs.value.clipCleanupDays)
        // Keep the home-screen widget in step with saved nights.
        // Waits for the first load so it never shows "No nights yet" while nights are still loading,
        // and a failed refresh never ends the collector.
        widgetScope.launch {
            repository.loaded.first { it }
            repository.sessions.collect { runCatching { LastNightWidget.refresh(this@QuietNightApp, it) } }
        }
    }
}
