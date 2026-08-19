package org.telegram.ui.vpn

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.*
import com.telegram.vpncore.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import android.app.AlertDialog

class VpnSettingsActivity : BaseFragment() {

    private lateinit var manager: VpnProxyManager
    private lateinit var repository: VpnConfigRepository
    private lateinit var subscriptionRepository: VpnSubscriptionRepository
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var subscriptionsContainer: LinearLayout? = null
    private val pingResults = mutableMapOf<String, Long>() // configId -> pingMs

    private lateinit var linkInput: EditText
    private lateinit var statusView: TextView
    private lateinit var statusSubView: TextView
    private lateinit var configInfoView: TextView
    private lateinit var statusDot: View
    private lateinit var statusCard: LinearLayout
    private lateinit var autoReconnectRow: LinearLayout
    private lateinit var autoReconnectSwitch: Switch
    private lateinit var autoReconnectIcon: View
    private lateinit var connectBtn: TextView
    private lateinit var disconnectBtn: TextView
    private lateinit var errorCard: LinearLayout
    private lateinit var errorText: TextView
    private var configsContainer: LinearLayout? = null
    private var btnRow: LinearLayout? = null

    private val configs = mutableListOf<VpnConfig>()

    private val PREFS_NAME = "vpn_settings"
    private val KEY_AUTO_RECONNECT = "auto_reconnect"

    private lateinit var energySavingSwitch: Switch

    // Colors
    private val COLOR_GREEN = 0xFF4CAF50.toInt()
    private val COLOR_GREEN_BG = 0x1A4CAF50.toInt()
    private val COLOR_ORANGE = 0xFFFF9800.toInt()
    private val COLOR_ORANGE_BG = 0x1AFF9800.toInt()
    private val COLOR_RED = 0xFFF44336.toInt()
    private val COLOR_RED_BG = 0x1AF44336.toInt()
    private val COLOR_GRAY = 0xFF9E9E9E.toInt()
    private val COLOR_GRAY_BG = 0x1A9E9E9E.toInt()
    private val COLOR_ACCENT = 0xFF6C5CE7.toInt()
    private val COLOR_ACCENT_BG = 0x1A6C5CE7.toInt()

    override fun createView(context: Context): View {
        manager = VpnProxyManager.getInstance(context)
        repository = VpnConfigRepository(context)
        subscriptionRepository = VpnSubscriptionRepository(context)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedAutoReconnect = prefs.getBoolean(KEY_AUTO_RECONNECT, false)
        manager.autoReconnect = savedAutoReconnect

        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back)
        actionBar.setTitle("VPN Proxy")
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })

        val dp = { v: Int -> AndroidUtilities.dp(v.toFloat()) }

        val scroll = ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = false
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }

        // ════════════════════ STATUS CARD ════════════════════
        statusCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundRect(dp(16), Theme.getColor(Theme.key_windowBackgroundGray))
            setPadding(dp(20), dp(18), dp(20), dp(18))
            layoutParams = marginParams(bottom = dp(12))
        }

        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        statusDot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                marginEnd = dp(12)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_GRAY)
            }
        }
        statusRow.addView(statusDot)

        statusView = TextView(context).apply {
            text = "Not connected"
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
        statusRow.addView(statusView)
        statusCard.addView(statusRow)

        statusSubView = TextView(context).apply {
            visibility = View.GONE
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            textSize = 13f
            setPadding(dp(22), dp(4), 0, 0)
        }
        statusCard.addView(statusSubView)

        root.addView(statusCard)

        // ════════════════════ ERROR CARD ════════════════════
        errorCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeRoundRect(dp(12), COLOR_RED_BG)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = marginParams(bottom = dp(12))
            visibility = View.GONE
        }

        val errorIcon = TextView(context).apply {
            text = "!"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_RED)
            gravity = Gravity.CENTER
            val size = dp(24)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(12) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), COLOR_RED)
            }
        }
        errorCard.addView(errorIcon)

        errorText = TextView(context).apply {
            setTextColor(COLOR_RED)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        errorCard.addView(errorText)

        val dismissBtn = TextView(context).apply {
            text = "OK"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_RED)
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener { errorCard.visibility = View.GONE }
        }
        errorCard.addView(dismissBtn)

        root.addView(errorCard)

        // ════════════════════ AUTO-RECONNECT ════════════════════
        autoReconnectRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeRoundRect(dp(16), if (savedAutoReconnect) COLOR_GREEN_BG else Theme.getColor(Theme.key_windowBackgroundGray))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = marginParams(bottom = dp(12))
        }

        autoReconnectIcon = View(context).apply {
            val size = dp(8)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(14) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (savedAutoReconnect) COLOR_GREEN else COLOR_GRAY)
            }
        }
        autoReconnectRow.addView(autoReconnectIcon)

        val arTextBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val arTitle = TextView(context).apply {
            text = "Auto-reconnect"
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            textSize = 15f
        }
        arTextBlock.addView(arTitle)

        val arSub = TextView(context).apply {
            text = "Restart VPN on network change"
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            textSize = 12f
        }
        arTextBlock.addView(arSub)
        autoReconnectRow.addView(arTextBlock)

        autoReconnectSwitch = Switch(context).apply {
            isChecked = savedAutoReconnect
            setOnCheckedChangeListener { _, isChecked ->
                manager.autoReconnect = isChecked
                prefs.edit().putBoolean(KEY_AUTO_RECONNECT, isChecked).apply()
                animateAutoReconnect(isChecked)
            }
        }
        autoReconnectRow.addView(autoReconnectSwitch)
        root.addView(autoReconnectRow)

        // ════════════════════ ENERGY SAVING ROW ════════════════════
        val savedEnergySaving = repository.isEnergySaving()
        val energySavingRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeRoundRect(dp(16), if (savedEnergySaving) COLOR_GREEN_BG else Theme.getColor(Theme.key_windowBackgroundGray))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = marginParams(bottom = dp(12))
        }

        val energyDot = View(context).apply {
            val size = dp(8)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(14) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (savedEnergySaving) COLOR_GREEN else COLOR_GRAY)
            }
        }
        energySavingRow.addView(energyDot)

        val esTextBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val esTitle = TextView(context).apply {
            text = "Energy Saving"
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            textSize = 15f
        }
        esTextBlock.addView(esTitle)
        val esSub = TextView(context).apply {
            text = "Pause VPN when app is in background"
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            textSize = 12f
        }
        esTextBlock.addView(esSub)
        energySavingRow.addView(esTextBlock)

        energySavingSwitch = Switch(context).apply {
            isChecked = savedEnergySaving
            setOnCheckedChangeListener { _, isChecked ->
                repository.setEnergySaving(isChecked)
                val dotColor = if (isChecked) COLOR_GREEN else COLOR_GRAY
                val bgColor = if (isChecked) COLOR_GREEN_BG else Theme.getColor(Theme.key_windowBackgroundGray)
                (energyDot.background as? GradientDrawable)?.setColor(dotColor)
                energySavingRow.background = makeRoundRect(dp(16), bgColor)
            }
        }
        energySavingRow.addView(energySavingSwitch)
        root.addView(energySavingRow)

        // ════════════════════ SECTION: SUBSCRIPTIONS ════════════════════
        root.addView(sectionLabel(context, "Subscriptions"))

        subscriptionsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(subscriptionsContainer)
        rebuildSubscriptionsList(context)

        // Add subscription button
        val addSubBtn = makeButton(context, "+ Add Subscription", COLOR_ACCENT_BG, COLOR_ACCENT, dp(12))
        addSubBtn.setOnClickListener { showAddSubscriptionDialog(context) }
        addSubBtn.layoutParams = marginParams(bottom = dp(16))
        root.addView(addSubBtn)

        // ════════════════════ SECTION: ADD CONFIG ════════════════════
        root.addView(sectionLabel(context, "Add Configuration"))

        linkInput = EditText(context).apply {
            hint = "vless://  vmess://  ss://  trojan://"
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText))
            background = makeRoundRect(dp(12), Theme.getColor(Theme.key_windowBackgroundGray))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            isSingleLine = false
            layoutParams = marginParams(bottom = dp(8))
        }
        root.addView(linkInput)

        // Config preview
        configInfoView = TextView(context).apply {
            visibility = View.GONE
            setTextColor(COLOR_ACCENT)
            textSize = 12f
            background = makeRoundRect(dp(8), COLOR_ACCENT_BG)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = marginParams(bottom = dp(8))
        }
        root.addView(configInfoView)

        // Buttons
        btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = marginParams(bottom = dp(16))
        }

        val pasteBtn = makeButton(context, "Paste", Theme.getColor(Theme.key_windowBackgroundGray),
            Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), dp(12))
        pasteBtn.setOnClickListener { pasteFromClipboard() }
        (pasteBtn.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8)
        btnRow!!.addView(pasteBtn)

        connectBtn = makeButton(context, "Connect", COLOR_ACCENT, Color.WHITE, dp(12))
        connectBtn.setOnClickListener { onConnectClicked() }
        (connectBtn.layoutParams as LinearLayout.LayoutParams).weight = 1f
        (connectBtn.layoutParams as LinearLayout.LayoutParams).width = 0
        btnRow!!.addView(connectBtn)

        disconnectBtn = makeButton(context, "Disconnect", COLOR_RED_BG, COLOR_RED, dp(12)).apply {
            visibility = View.GONE
        }
        disconnectBtn.setOnClickListener { repository.setVpnRunning(false); manager.stopProxy(); clearProxy() }
        (disconnectBtn.layoutParams as LinearLayout.LayoutParams).weight = 1f
        (disconnectBtn.layoutParams as LinearLayout.LayoutParams).width = 0
        btnRow!!.addView(disconnectBtn)

        root.addView(btnRow)

        // ════════════════════ SECTION: SAVED CONFIGS ════════════════════
        root.addView(sectionLabel(context, "Saved Configurations"))

        configsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(configsContainer)

        scroll.addView(root)
        fragmentView = scroll

        linkInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val t = s?.toString()?.trim() ?: ""
                if (t.length > 20) previewParse(t) else configInfoView.visibility = View.GONE
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // collect (not collectLatest) — чтобы не пропускать состояния при быстром переключении
        scope.launch {
            manager.state.collect { state ->
                updateUi(state)
                // Обновляем список конфигов при каждом изменении состояния
                // чтобы выделение активного конфига и пинги обновлялись сразу
                rebuildConfigsList()
            }
        }

        configs.addAll(repository.getAll())
        rebuildConfigsList()

        return scroll
    }

    // ─────────────────────────── Actions ─────────────────────────

    private fun onConnectClicked() {
        val text = linkInput.text.toString().trim()
        if (text.isBlank()) return
        manager.parseLink(text)
            .onSuccess { config ->
                repository.save(config)
                repository.setActive(config.id)
                repository.setVpnRunning(true)
                manager.startProxy(config)
                configs.clear()
                configs.addAll(repository.getAll())
                rebuildConfigsList()
                errorCard.visibility = View.GONE
            }
            .onFailure { e ->
                showError("Invalid link: ${e.message}")
            }
    }

    private fun pasteFromClipboard() {
        val cb = context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cb?.primaryClip?.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank()) {
            showError("Clipboard is empty")
            return
        }
        linkInput.setText(text)
    }

    private fun previewParse(text: String) {
        manager.parseLink(text)
            .onSuccess { config ->
                configInfoView.text = buildInfo(config)
                configInfoView.visibility = View.VISIBLE
            }
            .onFailure { configInfoView.visibility = View.GONE }
    }

    // ─────────────────────────── UI State ─────────────────────────

    private fun updateUi(state: VpnProxyManager.ProxyState) {
        if (fragmentView == null) return
        when (state) {
            is VpnProxyManager.ProxyState.Idle -> {
                statusView.text = "Not connected"
                statusSubView.visibility = View.GONE
                animateStatusDot(COLOR_GRAY)
                animateStatusCard(COLOR_GRAY_BG)
                connectBtn.visibility = View.VISIBLE
                disconnectBtn.visibility = View.GONE
                connectBtn.isEnabled = true
                connectBtn.alpha = 1f
            }
            is VpnProxyManager.ProxyState.Connecting -> {
                statusView.text = "Connecting..."
                statusSubView.visibility = View.GONE
                animateStatusDot(COLOR_ORANGE)
                animateStatusCard(COLOR_ORANGE_BG)
                connectBtn.visibility = View.VISIBLE
                disconnectBtn.visibility = View.GONE
                connectBtn.isEnabled = false
                connectBtn.alpha = 0.5f
                errorCard.visibility = View.GONE
            }
            is VpnProxyManager.ProxyState.Connected -> {
                statusView.text = "Connected"
                statusSubView.text = state.config.displayName +
                    if (manager.autoReconnect) " \u00B7 Auto-reconnect" else ""
                statusSubView.visibility = View.VISIBLE
                animateStatusDot(COLOR_GREEN)
                animateStatusCard(COLOR_GREEN_BG)
                connectBtn.visibility = View.GONE
                disconnectBtn.visibility = View.VISIBLE
                injectProxy()
                errorCard.visibility = View.GONE
            }
            is VpnProxyManager.ProxyState.Error -> {
                statusView.text = "Connection failed"
                statusSubView.visibility = View.GONE
                animateStatusDot(COLOR_RED)
                animateStatusCard(COLOR_RED_BG)
                connectBtn.visibility = View.VISIBLE
                disconnectBtn.visibility = View.GONE
                connectBtn.isEnabled = true
                connectBtn.alpha = 1f
                showError(state.message)
            }
        }
    }

    private fun showError(msg: String) {
        errorText.text = msg
        if (errorCard.visibility == View.GONE) {
            errorCard.visibility = View.VISIBLE
            errorCard.alpha = 0f
            errorCard.translationY = -AndroidUtilities.dp(8f).toFloat()
            errorCard.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .start()
        }
    }

    // ─────────────────────────── Animations ─────────────────────────

    private fun animateStatusDot(toColor: Int) {
        val bg = statusDot.background as? GradientDrawable ?: return
        val from = statusDot.tag as? Int ?: COLOR_GRAY
        ValueAnimator.ofObject(ArgbEvaluator(), from, toColor).apply {
            duration = 350
            addUpdateListener { bg.setColor(it.animatedValue as Int) }
            start()
        }
        statusDot.tag = toColor

        // Pulse animation for connecting
        if (toColor == COLOR_ORANGE) {
            statusDot.animate().scaleX(1.3f).scaleY(1.3f).setDuration(400)
                .setInterpolator(OvershootInterpolator())
                .withEndAction {
                    statusDot.animate().scaleX(1f).scaleY(1f).setDuration(300).start()
                }.start()
        }
    }

    private fun animateStatusCard(toColor: Int) {
        val bg = statusCard.background as? GradientDrawable ?: return
        val from = statusCard.tag as? Int ?: Theme.getColor(Theme.key_windowBackgroundGray)
        ValueAnimator.ofObject(ArgbEvaluator(), from, toColor).apply {
            duration = 350
            addUpdateListener { bg.setColor(it.animatedValue as Int) }
            start()
        }
        statusCard.tag = toColor
    }

    private fun animateAutoReconnect(enabled: Boolean) {
        val toColor = if (enabled) COLOR_GREEN_BG else Theme.getColor(Theme.key_windowBackgroundGray)
        val fromColor = autoReconnectRow.tag as? Int ?: Theme.getColor(Theme.key_windowBackgroundGray)
        val bg = autoReconnectRow.background as? GradientDrawable ?: return

        ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor).apply {
            duration = 300
            addUpdateListener { bg.setColor(it.animatedValue as Int) }
            start()
        }
        autoReconnectRow.tag = toColor

        val dotBg = autoReconnectIcon.background as? GradientDrawable ?: return
        val dotFrom = autoReconnectIcon.tag as? Int ?: COLOR_GRAY
        val dotTo = if (enabled) COLOR_GREEN else COLOR_GRAY
        ValueAnimator.ofObject(ArgbEvaluator(), dotFrom, dotTo).apply {
            duration = 300
            addUpdateListener { dotBg.setColor(it.animatedValue as Int) }
            start()
        }
        autoReconnectIcon.tag = dotTo

        if (enabled) {
            autoReconnectIcon.animate().scaleX(1.5f).scaleY(1.5f).setDuration(200)
                .withEndAction {
                    autoReconnectIcon.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                }.start()
        }
    }

    // ─────────────────────────── Config List ─────────────────────────

    private fun rebuildConfigsList() {
        val container = configsContainer ?: return
        val ctx = context ?: return
        container.removeAllViews()
        val dp = { v: Int -> AndroidUtilities.dp(v.toFloat()) }
        val activeId = manager.getCurrentConfig()?.id

        if (configs.isEmpty()) {
            val empty = TextView(ctx).apply {
                text = "No saved configurations"
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            }
            container.addView(empty)
            return
        }

        configs.forEach { config ->
            val isActive = config.id == activeId

            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = makeRoundRect(dp(12),
                    if (isActive) COLOR_GREEN_BG else Theme.getColor(Theme.key_windowBackgroundGray))
                setPadding(dp(14), dp(12), dp(10), dp(12))
                layoutParams = marginParams(bottom = dp(8))
            }

            // Protocol badge
            val badge = TextView(ctx).apply {
                text = config.protocolLabel.uppercase()
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isActive) COLOR_GREEN else COLOR_ACCENT)
                background = makeRoundRect(dp(6),
                    if (isActive) 0x334CAF50 else COLOR_ACCENT_BG)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(12) }
            }
            card.addView(badge)

            // Info
            val info = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val name = TextView(ctx).apply {
                text = config.displayName
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
                textSize = 14f
                maxLines = 1
            }
            info.addView(name)

            val detail = TextView(ctx).apply {
                val pingText = pingResults[config.id]?.let {
                    if (it == Long.MAX_VALUE) " · ✕" else " · ${it}ms"
                } ?: ""
                text = "${config.address}:${config.port}$pingText"
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
                textSize = 11f
                maxLines = 1
            }
            info.addView(detail)
            card.addView(info)

            if (isActive) {
                val activeLabel = TextView(ctx).apply {
                    text = "ACTIVE"
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(COLOR_GREEN)
                    setPadding(dp(8), 0, dp(8), 0)
                }
                card.addView(activeLabel)
            } else {
                val useBtn = TextView(ctx).apply {
                    text = "USE"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(COLOR_ACCENT)
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    setOnClickListener {
                        repository.setActive(config.id)
                        repository.setVpnRunning(true)
                        manager.startProxy(config)
                        configs.clear()
                        configs.addAll(repository.getAll())
                        rebuildConfigsList()
                    }
                }
                card.addView(useBtn)
            }

            val delBtn = TextView(ctx).apply {
                text = "\u2715"
                textSize = 16f
                setTextColor(COLOR_RED)
                setPadding(dp(10), dp(4), dp(4), dp(4))
                setOnClickListener {
                    if (manager.getCurrentConfig()?.id == config.id) {
                        repository.setVpnRunning(false)
                        manager.stopProxy(); clearProxy()
                    }
                    repository.delete(config.id)
                    configs.clear()
                    configs.addAll(repository.getAll())
                    rebuildConfigsList()
                }
            }
            card.addView(delBtn)

            container.addView(card)
        }
    }

    // ─────────────────────────── Helpers ─────────────────────────

    private fun buildInfo(c: VpnConfig): String {
        val parts = mutableListOf<String>()
        parts.add(c.protocolLabel.uppercase())
        parts.add("${c.address}:${c.port}")
        if (c.security != SecurityType.NONE) parts.add("TLS: ${c.security.name}")
        if (c.sni.isNotBlank()) parts.add("SNI: ${c.sni}")
        if (c.flow.isNotBlank()) parts.add("Flow: ${c.flow}")
        return parts.joinToString(" \u00B7 ")
    }

    private fun sectionLabel(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = marginParams(bottom = AndroidUtilities.dp(8f), top = AndroidUtilities.dp(4f))
        }
    }

    private fun makeButton(context: Context, label: String, bgColor: Int, textColor: Int, radius: Int): TextView {
        return TextView(context).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            gravity = Gravity.CENTER
            background = makeRoundRect(radius, bgColor)
            setPadding(
                AndroidUtilities.dp(20f), AndroidUtilities.dp(12f),
                AndroidUtilities.dp(20f), AndroidUtilities.dp(12f)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun makeRoundRect(radius: Int, color: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(color)
        }
    }

    private fun marginParams(
        bottom: Int = 0, top: Int = 0, start: Int = 0, end: Int = 0
    ): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = bottom
            topMargin = top
            marginStart = start
            marginEnd = end
        }
    }

    private fun injectProxy() {
        val (user, pass) = manager.getSocksCredentials()
        TelegramProxyBridge.enableProxy(VpnProxyManager.LOCAL_HOST, VpnProxyManager.LOCAL_PORT, user, pass)
    }

    private fun clearProxy() = TelegramProxyBridge.disableProxy()

    // ══════════════════════ SUBSCRIPTIONS ══════════════════════════

    private fun showAddSubscriptionDialog(context: Context) {
        val dp = { v: Int -> AndroidUtilities.dp(v.toFloat()) }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val nameInput = EditText(context).apply {
            hint = "Name (e.g. My Sub)"
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        }
        val urlInput = EditText(context).apply {
            hint = "Subscription URL"
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        }
        layout.addView(nameInput)
        layout.addView(urlInput)

        AlertDialog.Builder(context)
            .setTitle("Add Subscription")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val name = nameInput.text.toString().trim().ifBlank { "Subscription" }
                val url = urlInput.text.toString().trim()
                if (url.isNotEmpty()) {
                    val sub = VpnSubscription(name = name, url = url)
                    subscriptionRepository.save(sub)
                    rebuildSubscriptionsList(context)
                    fetchSubscription(context, sub)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fetchSubscription(context: Context, sub: VpnSubscription) {
        scope.launch {
            try {
                val fetched = SubscriptionFetcher.fetch(sub.url)
                if (fetched.isEmpty()) {
                    Toast.makeText(context, "No servers found in subscription", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                // Remove old configs from this subscription
                val oldSub = subscriptionRepository.getById(sub.id)
                oldSub?.configIds?.forEach { repository.delete(it) }

                // Save new configs
                fetched.forEach { repository.save(it) }
                val updatedSub = sub.copy(
                    lastUpdated = System.currentTimeMillis(),
                    configIds = fetched.map { it.id }
                )
                subscriptionRepository.save(updatedSub)

                configs.clear()
                configs.addAll(repository.getAll())
                rebuildConfigsList()
                rebuildSubscriptionsList(context)

                Toast.makeText(context, "Loaded ${fetched.size} servers", Toast.LENGTH_SHORT).show()

                // Auto-ping after fetch
                pingSubscription(context, fetched)
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to fetch: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun pingSubscription(context: Context, configsToPing: List<VpnConfig>) {
        scope.launch {
            Toast.makeText(context, "Pinging servers…", Toast.LENGTH_SHORT).show()
            val results = PingManager.pingAll(configsToPing) { result ->
                pingResults[result.config.id] = result.pingMs
                rebuildConfigsList()
            }
            pingResults.clear()
            results.forEach { pingResults[it.config.id] = it.pingMs }
            rebuildConfigsList()

            // Auto-connect to fastest reachable server
            val fastest = results.firstOrNull { it.isReachable }
            if (fastest != null) {
                repository.setActive(fastest.config.id)
                repository.setVpnRunning(true)
                if (manager.isRunning()) manager.stopProxy()
                manager.startProxy(fastest.config)
                Toast.makeText(context,
                    "Connected to ${fastest.config.displayName} (${fastest.displayPing})",
                    Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "No reachable servers found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun rebuildSubscriptionsList(context: Context) {
        val container = subscriptionsContainer ?: return
        val dp = { v: Int -> AndroidUtilities.dp(v.toFloat()) }
        container.removeAllViews()

        val subs = subscriptionRepository.getAll()
        if (subs.isEmpty()) {
            val empty = TextView(context).apply {
                text = "No subscriptions yet"
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
                textSize = 13f
                setPadding(dp(4), 0, 0, dp(8))
            }
            container.addView(empty)
            return
        }

        for (sub in subs) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = makeRoundRect(dp(12), Theme.getColor(Theme.key_windowBackgroundGray))
                setPadding(dp(14), dp(10), dp(10), dp(10))
                layoutParams = marginParams(bottom = dp(8))
            }

            val textBlock = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameView = TextView(context).apply {
                text = sub.name
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            }
            textBlock.addView(nameView)
            val urlView = TextView(context).apply {
                text = sub.url.take(40) + if (sub.url.length > 40) "…" else ""
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
                textSize = 11f
            }
            textBlock.addView(urlView)
            if (sub.lastUpdated > 0L) {
                val dateView = TextView(context).apply {
                    val ago = (System.currentTimeMillis() - sub.lastUpdated) / 60000
                    text = "${sub.configIds.size} servers · updated ${ago}m ago"
                    setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
                    textSize = 11f
                }
                textBlock.addView(dateView)
            }
            row.addView(textBlock)

            // Refresh button
            val refreshBtn = TextView(context).apply {
                text = "↻"
                textSize = 20f
                setTextColor(COLOR_ACCENT)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { fetchSubscription(context, sub) }
            }
            row.addView(refreshBtn)

            // Ping & connect button
            val pingBtn = TextView(context).apply {
                text = "⚡"
                textSize = 18f
                setTextColor(COLOR_GREEN)
                setPadding(dp(4), dp(4), dp(8), dp(4))
                setOnClickListener {
                    val subConfigs = sub.configIds.mapNotNull { id ->
                        repository.getAll().firstOrNull { it.id == id }
                    }
                    if (subConfigs.isEmpty()) {
                        Toast.makeText(context, "Update subscription first", Toast.LENGTH_SHORT).show()
                    } else {
                        pingSubscription(context, subConfigs)
                    }
                }
            }
            row.addView(pingBtn)

            // Delete button
            val delBtn = TextView(context).apply {
                text = "✕"
                textSize = 14f
                setTextColor(COLOR_RED)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setOnClickListener {
                    sub.configIds.forEach { repository.delete(it) }
                    subscriptionRepository.delete(sub.id)
                    configs.clear()
                    configs.addAll(repository.getAll())
                    rebuildConfigsList()
                    rebuildSubscriptionsList(context)
                }
            }
            row.addView(delBtn)

            container.addView(row)
        }
    }

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        scope.cancel()
    }
}
