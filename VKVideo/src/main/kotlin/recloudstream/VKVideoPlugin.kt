package recloudstream

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class VKVideoPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(VKVideoProvider())
    }
}
