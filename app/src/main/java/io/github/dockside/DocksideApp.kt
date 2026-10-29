package io.github.dockside

import android.app.Application
import io.github.dockside.service.MonitorService

class DocksideApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MonitorService.createChannels(this)
    }
}
