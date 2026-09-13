package com.inversionadvisor

import android.app.Application
import com.inversionadvisor.work.MarketRefreshWorker

class InversionAdvisorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MarketRefreshWorker.schedule(this)
    }
}
