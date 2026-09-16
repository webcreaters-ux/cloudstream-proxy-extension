package recloudstream

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WebSourcePlugin : Plugin() {
    override fun load(context: Context) {
        WebSourceSettings.init(context)

        openSettings = { activityContext ->
            showSettings(activityContext)
        }

        registerMainAPI(WebSourceProvider())
    }

    private fun showSettings(context: Context) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }

        val scroll = ScrollView(context).apply {
            addView(root)
        }

        fun label(text: String): TextView = TextView(context).apply {
            this.text = text
            textSize = 14f
            setPadding(0, dp(10), 0, dp(4))
        }

        fun edit(value: String, hint: String, multiLine: Boolean = false): EditText = EditText(context).apply {
            setText(value)
            this.hint = hint
            setPadding(dp(4), dp(4), dp(4), dp(4))
            if (multiLine) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3
                gravity = Gravity.TOP
            } else {
                inputType = InputType.TYPE_CLASS_TEXT
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val proxySwitch = Switch(context).apply {
            text = "Enable authorized proxy template"
            isChecked = WebSourceSettings.proxyEnabled
        }
        root.addView(proxySwitch)

        root.addView(label("Proxy URL template (must contain {url})"))
        val proxyUrl = edit(
            WebSourceSettings.proxyUrl,
            "https://your-authorized-endpoint.example/?url={url}"
        )
        root.addView(proxyUrl)

        root.addView(label("User-Agent"))
        val userAgent = edit(
            WebSourceSettings.userAgent,
            "Mozilla/5.0 (Android) CloudStream"
        )
        root.addView(userAgent)

        root.addView(label("Preferred video quality"))
        val qualityValues = arrayOf("Auto", "2160p", "1440p", "1080p", "720p", "480p")
        val spinner = Spinner(context)
        spinner.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            qualityValues
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.setSelection(qualityValues.indexOf(WebSourceSettings.preferredQuality).coerceAtLeast(0))
        root.addView(spinner)

        root.addView(label("Settings are stored on this device. Proxy use is intended only for endpoints you are authorized to use."))

        val dialog = AlertDialog.Builder(context)
            .setTitle("Web Sources Settings")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset", null)
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                WebSourceSettings.clear()
                WebSourceSettings.init(context)
                proxySwitch.isChecked = false
                proxyUrl.setText("")
                userAgent.setText("Mozilla/5.0 (Android) CloudStream")
                spinner.setSelection(0)
                Toast.makeText(context, "Web Sources settings reset", Toast.LENGTH_SHORT).show()
            }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val template = proxyUrl.text.toString().trim()
                if (proxySwitch.isChecked &&
                    template.isNotEmpty() &&
                    !template.contains("{url}")
                ) {
                    proxyUrl.error = "Template must contain {url}"
                    return@setOnClickListener
                }

                if (proxySwitch.isChecked &&
                    template.isNotEmpty() &&
                    !template.startsWith("http://") &&
                    !template.startsWith("https://")
                ) {
                    proxyUrl.error = "Use an http:// or https:// endpoint"
                    return@setOnClickListener
                }

                WebSourceSettings.proxyEnabled = proxySwitch.isChecked
                WebSourceSettings.proxyUrl = template
                WebSourceSettings.userAgent = userAgent.text.toString().trim().ifBlank {
                    "Mozilla/5.0 (Android) CloudStream"
                }
                WebSourceSettings.preferredQuality = qualityValues[spinner.selectedItemPosition]
                Toast.makeText(context, "Web Sources settings saved", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.show()
    }
}
