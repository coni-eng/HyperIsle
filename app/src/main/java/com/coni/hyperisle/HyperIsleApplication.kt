package com.coni.hyperisle

import android.app.Application
import com.coni.hyperisle.util.DiagnosticsManager

class HyperIsleApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize diagnostics manager (debug builds only)
        if (BuildConfig.DEBUG) {
            DiagnosticsManager.init(this)
        }
    }
}
