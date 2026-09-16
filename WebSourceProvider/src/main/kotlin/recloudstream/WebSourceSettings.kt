package recloudstream

import android.content.Context
import android.content.SharedPreferences
import java.net.URLEncoder

object WebSourceSettings {
    private const val PREFS = "web_source_settings"
    private const val KEY_PROXY_ENABLED = "proxy_enabled"
    private const val KEY_PROXY_URL = "proxy_url"
    private const val KEY_USER_AGENT = "user_agent"
    private const val KEY_QUALITY = "quality"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    var proxyEnabled: Boolean
        get() = prefs?.getBoolean(KEY_PROXY_ENABLED, false) ?: false
        set(value) { prefs?.edit()?.putBoolean(KEY_PROXY_ENABLED, value)?.apply() }

    var proxyUrl: String
        get() = prefs?.getString(KEY_PROXY_URL, "") ?: ""
        set(value) { prefs?.edit()?.putString(KEY_PROXY_URL, value)?.apply() }

    var userAgent: String
        get() = prefs?.getString(KEY_USER_AGENT, "Mozilla/5.0 (Android) CloudStream")
            ?: "Mozilla/5.0 (Android) CloudStream"
        set(value) { prefs?.edit()?.putString(KEY_USER_AGENT, value)?.apply() }

    var preferredQuality: String
        get() = prefs?.getString(KEY_QUALITY, "Auto") ?: "Auto"
        set(value) { prefs?.edit()?.putString(KEY_QUALITY, value)?.apply() }

    fun clear() {
        prefs?.edit()?.clear()?.apply()
    }

    fun applyProxy(url: String): String {
        if (!proxyEnabled) return url
        val template = proxyUrl.trim()
        if (!template.startsWith("http://") && !template.startsWith("https://")) return url
        if (!template.contains("{url}")) return url
        return template.replace("{url}", URLEncoder.encode(url, "UTF-8"))
    }
}
