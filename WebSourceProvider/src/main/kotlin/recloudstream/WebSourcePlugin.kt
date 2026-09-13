package recloudstream

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WebSourcePlugin : Plugin() {
    private lateinit var provider: WebSourceProvider

    override fun load(context: Context) {
        WebSourceSettings.init(context)
        provider = WebSourceProvider()
        registerMainAPI(provider)
        openSettings = { settingsContext -> showSettings(settingsContext) }
    }

    private fun showSettings(context: Context) {
        val density = context.resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val spacing = (8 * density).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
        }

        val proxyEnabled = CheckBox(context).apply {
            text = "Enable custom proxy"
            isChecked = WebSourceSettings.proxyEnabled
        }
        val proxyUrl = EditText(context).apply {
            hint = "https://your-authorized-proxy.example/?url={url}"
            setText(WebSourceSettings.proxyUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val proxyHelp = TextView(context).apply {
            text = "Use only a proxy endpoint you own or are authorized to use. The URL must contain {url}."
            setPadding(0, 0, 0, spacing)
        }
        val userAgent = EditText(context).apply {
            hint = "User-Agent"
            setText(WebSourceSettings.userAgent)
        }
        val qualityLabel = TextView(context).apply {
            text = "Preferred video quality"
            setPadding(0, spacing, 0, 0)
        }
        val qualityValues = arrayOf("Auto", "2160p", "1440p", "1080p", "720p", "480p")
        val qualitySpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, qualityValues)
            setSelection(qualityValues.indexOf(WebSourceSettings.preferredQuality).coerceAtLeast(0))
        }

        container.addView(proxyEnabled)
        container.addView(proxyUrl, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(proxyHelp)
        container.addView(userAgent, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(qualityLabel)
        container.addView(qualitySpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        AlertDialog.Builder(context)
            .setTitle("Web Sources Settings")
            .setView(container)
            .setNegativeButton("Reset") { _, _ ->
                WebSourceSettings.clear()
                Toast.makeText(context, "Settings reset", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Save") { _, _ ->
                val enteredProxy = proxyUrl.text.toString().trim()
                if (proxyEnabled.isChecked && enteredProxy.isNotEmpty() &&
                    ((!enteredProxy.startsWith("http://") && !enteredProxy.startsWith("https://")) || !enteredProxy.contains("{url}"))) {
                    Toast.makeText(context, "Proxy URL must start with http:// or https:// and contain {url}", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                WebSourceSettings.proxyEnabled = proxyEnabled.isChecked
                WebSourceSettings.proxyUrl = enteredProxy
                WebSourceSettings.userAgent = userAgent.text.toString().trim().ifBlank { "Mozilla/5.0 (Android) CloudStream" }
                WebSourceSettings.preferredQuality = qualityValues[qualitySpinner.selectedItemPosition]
                Toast.makeText(context, "Web Sources settings saved", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
