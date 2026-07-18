package org.telegram.ui.vpn

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.telegram.vpncore.VpnConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme

class VpnConfigAdapter(
    context: Context,
    private val configs: List<VpnConfig>,
    private val onConnect: (VpnConfig) -> Unit,
    private val onDelete: (VpnConfig) -> Unit
) : ArrayAdapter<VpnConfig>(context, 0, configs) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val config = configs[position]
        val dp8 = AndroidUtilities.dp(8f)
        val dp10 = AndroidUtilities.dp(10f)
        val dp12 = AndroidUtilities.dp(12f)

        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp12, dp10, dp12, dp10)
        row.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))

        val textContainer = LinearLayout(context)
        textContainer.orientation = LinearLayout.VERTICAL
        val textParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        textContainer.layoutParams = textParams

        val nameView = TextView(context)
        nameView.text = config.displayName
        nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        nameView.textSize = 14f
        textContainer.addView(nameView)

        val infoView = TextView(context)
        infoView.text = "${config.protocolLabel} · ${config.address}:${config.port}"
        infoView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        infoView.textSize = 12f
        textContainer.addView(infoView)

        row.addView(textContainer)

        val connectBtn = Button(context)
        connectBtn.text = "Use"
        connectBtn.textSize = 12f
        connectBtn.setPadding(dp8, 0, dp8, 0)
        connectBtn.setOnClickListener { onConnect(config) }
        row.addView(connectBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val deleteBtn = Button(context)
        deleteBtn.text = "✕"
        deleteBtn.textSize = 12f
        deleteBtn.setPadding(dp8, 0, dp8, 0)
        deleteBtn.setTextColor(0xFFF44336.toInt())
        deleteBtn.setOnClickListener { onDelete(config) }
        row.addView(deleteBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        return row
    }
}
