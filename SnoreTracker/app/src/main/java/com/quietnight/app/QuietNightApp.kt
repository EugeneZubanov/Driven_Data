package com.quietnight.app

import android.app.Application
import com.quietnight.app.data.SessionRepository
import com.quietnight.app.data.Settings

class QuietNightApp : Application() {
    lateinit var repository: SessionRepository
        private set
    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        repository = SessionRepository(filesDir).also { it.loadInBackground() }
        settings = Settings(this)
    }
}
