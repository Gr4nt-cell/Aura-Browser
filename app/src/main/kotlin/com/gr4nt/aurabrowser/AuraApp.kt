package com.gr4nt.aurabrowser

import android.app.Application
import com.gr4nt.aurabrowser.dl.Downloads

class AuraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Blocker.init(this)
        Downloads.init(this)
    }
}
