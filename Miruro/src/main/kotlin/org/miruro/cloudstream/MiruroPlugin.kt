package org.miruro.cloudstream

import android.content.Context
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MiruroPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MiruroProvider())
    }
}
