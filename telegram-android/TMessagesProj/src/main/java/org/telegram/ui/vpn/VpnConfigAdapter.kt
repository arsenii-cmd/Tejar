package org.telegram.ui.vpn

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.telegram.vpncore.VpnConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

class VpnConfigAdapter(
    context: Context,
    private val configs: List<VpnConfig>,
    private val onConnect: (VpnConfig) -> Unit,
    private val onDelete: (VpnConfig) -> Unit
) : ArrayAdapter<VpnConfig>(context, 0, configs) {

    private class ViewHolder(
        val row: LinearLayout,
        val nameView: TextView,
        val detailView: TextView,
        val connectBtn: Button,
        val deleteBtn: Button
    )

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val config = configs[position]
        val holder: ViewHolder

        val view = if (convertView?.tag is ViewHolder) {
            holder = convertView.tag as ViewHolder
            convertView
        } else {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(
                    AndroidUtilities.dp(12f), AndroidUtilities.dp(10f),
                    AndroidUtilities.dp(12f), AndroidUtilities.dp(10f)
                )
                setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
            }

            val textContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameView = TextView(context).apply {
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
                textSize = 14f
            }
            val detailView = TextView(context).apply {
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
                textSize = 12f
            }
            textContainer.addView(nameView)
            textContainer.addView(detailView)
            row.addView(textContainer)

            val connectBtn = Button(context).apply {
                text = "Use"
                textSize = 12f
                setPadding(AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(8f), 0)
            }
            row.addView(connectBtn, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT
            ))

            val deleteBtn = Button(context).apply {
                text = "\u2715"
                textSize = 12f
                setPadding(AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(8f), 0)
                setTextColor(0xFFF44336.toInt())
            }
            row.addView(deleteBtn, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT
            ))

            holder = ViewHolder(row, nameView, detailView, connectBtn, deleteBtn)
            row.tag = holder
            row
        }

        holder.nameView.text = config.displayName
        holder.detailView.text = "${config.protocolLabel} · ${config.address}:${config.port}"
        holder.connectBtn.setOnClickListener { onConnect(config) }
        holder.deleteBtn.setOnClickListener { onDelete(config) }

        return view
    }
}
