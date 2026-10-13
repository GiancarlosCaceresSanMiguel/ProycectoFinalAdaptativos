package com.energiacontexto

import android.app.Application
import com.energiacontexto.client.AppPreferences
import com.energiacontexto.client.SessionEngine
import com.energiacontexto.server.BackendServices
import com.energiacontexto.server.LocalDatabase
import com.energiacontexto.sync.RemoteSync

class EnergiaApp : Application() {
    lateinit var database: LocalDatabase
        private set
    lateinit var backend: BackendServices
        private set
    lateinit var prefs: AppPreferences
        private set
    lateinit var remote: RemoteSync
        private set
    lateinit var sessionEngine: SessionEngine
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        database = LocalDatabase(this)
        backend = BackendServices(database)
        remote = RemoteSync(this, prefs)
        sessionEngine = SessionEngine(backend, remote, prefs)
    }
}
