package com.coni.hyperisle

import android.app.Application
import com.coni.hyperisle.data.AppPreferences
import com.coni.hyperisle.util.DiagnosticsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch



class HyperIsleApplication : Application() {
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize diagnostics manager (debug builds only)
        if (BuildConfig.DEBUG) {
            DiagnosticsManager.init(this)
        }
        
        // v1.0.0: Auto-enable shade cancel for all allowed apps on first setup
        applicationScope.launch {
            val preferences = AppPreferences(applicationContext)
            preferences.setupDefaultShadeCancelIfNeeded(applicationContext)
        }
    }
}
