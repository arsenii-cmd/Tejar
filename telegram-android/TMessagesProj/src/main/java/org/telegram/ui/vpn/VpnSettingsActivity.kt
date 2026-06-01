package org.telegram.ui.vpn

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.telegram.vpncore.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme

class VpnSettingsActivity : BaseFragment() {

    private lateinit var manager: VpnProxyManager
    private lateinit var repository: VpnConfigRepository
    private lateinit var subscriptionRepository: VpnSubscriptionRepository
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var subscriptionsContainer: LinearLayout? = null

    // ── Hero card views ───────────────────────────────────────────
    private lateinit var heroCard: LinearLayout
    private lateinit var heroCardBg: GradientDrawable          // stored to update border+fill
    private lateinit var heroStatusDot: View
    private lateinit var heroStatusLabel: TextView
    private lateinit var heroRightLabel: TextView               // uptime / TLS label
    private lateinit var heroHintText: TextView                 // idle: hint to add a config
    private lateinit var heroErrorRow: LinearLayout             // error: warn dot + reason text
    private lateinit var heroErrorText: TextView
    private lateinit var heroServerRow: LinearLayout            // connecting/connected: flag + name + host
    private lateinit var heroFlagView: TextView
    private lateinit var heroServerName: TextView
    private lateinit var heroServerHost: TextView
    private lateinit var heroPingContainer: LinearLayout        // connected: ping pips + ms
    private val heroPingPips = mutableListOf<View>()
    private lateinit var heroPingLabel: TextView
    private lateinit var heroStatsRow: LinearLayout             // connected: DOWN / UP / AUTO
    private lateinit var heroProgressContainer: LinearLayout    // connecting: progress bar
    private lateinit var heroActionBtn: LinearLayout
    private lateinit var heroActionLabel: TextView

    // ── Auto-reconnect ────────────────────────────────────────────
    private lateinit var autoReconnectRow: LinearLayout
    private lateinit var autoReconnectSwitch: Switch
    private lateinit var autoReconnectDot: View

    // ── Add config ────────────────────────────────────────────────
    private lateinit var addInputCard: LinearLayout
    private lateinit var linkInput: EditText
    private lateinit var inlinePreview: LinearLayout
    private lateinit var addConnectBtn: TextView

    // ── Saved configs ─────────────────────────────────────────────
    private var configsContainer: LinearLayout? = null
    private val configs = mutableListOf<VpnConfig>()
    private val pingMap = mutableMapOf<String, Int?>()          // configId → TCP ping ms (null = timeout)

    // ── Uptime timer ──────────────────────────────────────────────
    private var connectedSince: Long = 0L
    private val uptimeHandler = Handler(Looper.getMainLooper())
    private val uptimeRunnable = object : Runnable {
        override fun run() {
            if (::heroRightLabel.isInitialized && heroRightLabel.visibility == View.VISIBLE) {
                val ms = System.currentTimeMillis() - connectedSince
                val h = ms / 3600000
                val m = (ms % 3600000) / 60000
                val s = (ms % 60000) / 1000
                heroRightLabel.text = "%02d:%02d:%02d".format(h, m, s)
                uptimeHandler.postDelayed(this, 1000)
            }
        }
    }

    private var currentState: VpnProxyManager.ProxyState = VpnProxyManager.ProxyState.Idle

    private val PREFS_NAME = "vpn_settings"
    private val KEY_AUTO_RECONNECT = "auto_reconnect"

    // ── AxiOm brand tokens ────────────────────────────────────────
    private val BLUE            = 0xFF5BA3FF.toInt()
    private val BLUE_TINT       = 0x1F5BA3FF.toInt()
    private val GREEN           = 0xFF5DCAA5.toInt()
    private val GREEN_TINT      = 0x215DCAA5.toInt()
    private val GOLD            = 0xFFE8B339.toInt()
    private val GOLD_TINT       = 0x24E8B339.toInt()
    private val RED             = 0xFFE55E5E.toInt()
    private val RED_TINT        = 0x21E55E5E.toInt()
    private val SURFACE         = 0xFF17212B.toInt()
    private val SURFACE_CT      = 0xFF1E2C3A.toInt()
    private val SURFACE_CT_HIGH = 0xFF243447.toInt()
    private val INK             = 0xFFFFFFFF.toInt()
    private val INK_DIM         = 0xFFA8B7C7.toInt()
    private val INK_MUTE        = 0xFF6E8195.toInt()
    private val INK_VERY_MUTE   = 0xFF4A5C6E.toInt()
    private val OUTLINE         = 0xFF2A3947.toInt()

    // ──────────────────────────────────────────────────────────────
    //  createView
    // ──────────────────────────────────────────────────────────────

    override fun createView(context: Context): View {
        manager = VpnProxyManager.getInstance(context)
        repository = VpnConfigRepository(context)
        subscriptionRepository = VpnSubscriptionRepository(context)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedAutoReconnect = prefs.getBoolean(KEY_AUTO_RECONNECT, false)
        manager.autoReconnect = savedAutoReconnect

        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back)
        actionBar.setTitle("VPN Proxy")
        actionBar.setSubtitle("Not connected")
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
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }

        // ════════ HERO CARD ════════
        root.addView(buildHeroCard(context, dp))

        // ════════ AUTO-RECONNECT ════════
        root.addView(buildAutoReconnectRow(context, dp, savedAutoReconnect, prefs))

        // ════════ ENERGY SAVING ════════
        root.addView(buildEnergySavingRow(context, dp))

        // ════════ SUBSCRIPTIONS SECTION ════════
        root.addView(buildSectionLabel(context, dp, "Subscriptions", "Add") { showAddSubscriptionDialog(context) })
        subscriptionsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(subscriptionsContainer)

        // ════════ ADD CONFIG SECTION ════════
        root.addView(buildSectionLabel(context, dp, "Add configuration", "Scan QR") { /* QR future */ })
        root.addView(buildAddConfigSection(context, dp))

        // ════════ SAVED CONFIGS SECTION ════════
        root.addView(buildSectionLabel(context, dp, "Saved configurations"))

        configsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(configsContainer)

        scroll.addView(root)
        fragmentView = scroll

        // Wire text watcher for inline parse preview
        linkInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val t = s?.toString()?.trim() ?: ""
                if (t.length > 20) showInlinePreview(t) else hideInlinePreview()
                addConnectBtn.text = if (t.isNotBlank()) "Save & Connect" else "Connect"
                addConnectBtn.alpha = if (t.isNotBlank()) 1f else 0.5f
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        scope.launch { manager.state.collectLatest { updateUi(it) } }

        configs.addAll(repository.getAll())
        rebuildConfigsList(context, dp)
        launchPings()
        rebuildSubscriptionsList(context)

        return scroll
    }

    // ──────────────────────────────────────────────────────────────
    //  Hero card
    // ──────────────────────────────────────────────────────────────

    private fun buildHeroCard(context: Context, dp: (Int) -> Int): LinearLayout {
        heroCardBg = roundedBg(dp(18), SURFACE_CT)
        heroCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = heroCardBg
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = marginParams(bottom = dp(10))
        }

        // ── Status row: dot + label + [flex] + right label ──
        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = fullWidthWrap()
        }

        heroStatusDot = View(context).apply {
            val sz = dp(9)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = dp(10) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(INK_MUTE)
            }
        }
        statusRow.addView(heroStatusDot)

        heroStatusLabel = TextView(context).apply {
            text = "NOT CONNECTED"
            setTextColor(INK_MUTE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        statusRow.addView(heroStatusLabel)

        statusRow.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })

        heroRightLabel = TextView(context).apply {
            setTextColor(GREEN)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
        }
        statusRow.addView(heroRightLabel)

        heroCard.addView(statusRow)

        // ── Idle hint text ──
        heroHintText = TextView(context).apply {
            text = "Paste a config link below or select a saved configuration."
            setTextColor(INK_DIM)
            textSize = 13f
            setLineSpacing(0f, 1.4f)
            layoutParams = fullWidthWrap().also {
                (it as LinearLayout.LayoutParams).topMargin = dp(10)
            }
            visibility = View.GONE
        }
        heroCard.addView(heroHintText)

        // ── Error reason row: red dot + error text ──
        heroErrorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = fullWidthWrap().also {
                (it as LinearLayout.LayoutParams).topMargin = dp(10)
            }
            visibility = View.GONE
        }
        val warnDot = View(context).apply {
            val sz = dp(8)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = dp(10)
                topMargin = dp(5)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(RED)
            }
        }
        heroErrorRow.addView(warnDot)
        heroErrorText = TextView(context).apply {
            text = "Connection failed. Check your internet or try a different server."
            setTextColor(INK)
            textSize = 13f
            setLineSpacing(0f, 1.4f)
            maxLines = 3
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        heroErrorRow.addView(heroErrorText)
        heroCard.addView(heroErrorRow)

        // ── Server row: flag tile + name + host + [ping] ──
        heroServerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = fullWidthWrap().also { (it as LinearLayout.LayoutParams).topMargin = dp(14) }
            visibility = View.GONE
        }

        heroFlagView = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(INK_DIM)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            val sz = dp(50)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = dp(12) }
            background = roundedBg(dp(10), SURFACE_CT_HIGH).also {
                (it as GradientDrawable).setStroke(dp(1), OUTLINE)
            }
        }
        heroServerRow.addView(heroFlagView)

        val serverInfoBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        heroServerName = TextView(context).apply {
            setTextColor(INK)
            textSize = 17f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        serverInfoBlock.addView(heroServerName)

        heroServerHost = TextView(context).apply {
            setTextColor(INK_DIM)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }
        serverInfoBlock.addView(heroServerHost)
        heroServerRow.addView(serverInfoBlock)

        // Ping pips + ms — shown only when connected
        heroPingContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            visibility = View.GONE
        }
        val pipsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.END; bottomMargin = dp(2) }
        }
        listOf(dp(5), dp(8), dp(11)).forEachIndexed { i, h ->
            val pip = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(3), h).apply {
                    if (i > 0) marginStart = dp(2)
                }
                background = roundedBg(dp(1), INK_VERY_MUTE)
            }
            pipsRow.addView(pip)
            heroPingPips.add(pip)
        }
        heroPingContainer.addView(pipsRow)
        heroPingLabel = TextView(context).apply {
            setTextColor(GREEN)
            textSize = 12f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.END
        }
        heroPingContainer.addView(heroPingLabel)
        heroServerRow.addView(heroPingContainer)

        heroCard.addView(heroServerRow)

        // ── Stats row: DOWN / UP / AUTO (connected only) ──
        heroStatsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBg(dp(10), SURFACE_CT_HIGH)
            layoutParams = fullWidthWrap().also { (it as LinearLayout.LayoutParams).topMargin = dp(12) }
            visibility = View.GONE
        }

        listOf("DOWN" to "—", "UP" to "—", "AUTO" to "ON").forEachIndexed { i, (lbl, value) ->
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, dp(10), 0, dp(10))
            }
            val labelTv = TextView(context).apply {
                text = lbl
                setTextColor(INK_MUTE)
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.12f
                gravity = Gravity.CENTER
            }
            val valueTv = TextView(context).apply {
                text = value
                setTextColor(if (lbl == "AUTO") GREEN else INK)
                textSize = 13f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            }
            cell.addView(labelTv)
            cell.addView(valueTv)

            if (i < 2) {
                val wrapper = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                wrapper.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val div = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                        topMargin = dp(8); bottomMargin = dp(8)
                    }
                    setBackgroundColor(OUTLINE)
                }
                wrapper.addView(div)
                heroStatsRow.addView(wrapper)
            } else {
                heroStatsRow.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        heroCard.addView(heroStatsRow)

        // ── Progress bar (connecting) ──
        heroProgressContainer = LinearLayout(context).apply {
            background = roundedBg(dp(2), 0x33000000.toInt())
            layoutParams = fullWidthWrap().also { (it as LinearLayout.LayoutParams).topMargin = dp(12) }
            visibility = View.GONE
        }
        val progressFill = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(4), 0.6f)
            background = roundedBg(dp(2), GOLD)
        }
        heroProgressContainer.addView(progressFill)
        heroProgressContainer.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(4), 0.4f)
        })
        heroCard.addView(heroProgressContainer)

        // ── Primary action button ──
        heroActionBtn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundedBg(dp(12), BLUE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = fullWidthWrap().also { (it as LinearLayout.LayoutParams).topMargin = dp(16) }
        }
        heroActionLabel = TextView(context).apply {
            text = "Connect"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        heroActionBtn.addView(heroActionLabel)
        heroActionBtn.setOnClickListener { onHeroActionClicked() }
        heroCard.addView(heroActionBtn)

        return heroCard
    }

    // ──────────────────────────────────────────────────────────────
    //  Auto-reconnect row
    // ──────────────────────────────────────────────────────────────

    private fun buildAutoReconnectRow(
        context: Context, dp: (Int) -> Int,
        savedAutoReconnect: Boolean,
        prefs: android.content.SharedPreferences
    ): LinearLayout {
        autoReconnectRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(dp(14), SURFACE_CT)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = marginParams(bottom = dp(10))
        }

        autoReconnectDot = View(context).apply {
            val sz = dp(9)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = dp(12) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (savedAutoReconnect) GREEN else INK_MUTE)
            }
        }
        autoReconnectRow.addView(autoReconnectDot)

        val textBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textBlock.addView(TextView(context).apply {
            text = "Auto-reconnect"
            setTextColor(INK)
            textSize = 14f
        })
        textBlock.addView(TextView(context).apply {
            text = "Restart on network change"
            setTextColor(INK_DIM)
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        })
        autoReconnectRow.addView(textBlock)

        autoReconnectSwitch = Switch(context).apply {
            isChecked = savedAutoReconnect
            setOnCheckedChangeListener { _, isChecked ->
                manager.autoReconnect = isChecked
                prefs.edit().putBoolean(KEY_AUTO_RECONNECT, isChecked).apply()
                animateDotColor(autoReconnectDot, if (isChecked) GREEN else INK_MUTE)
                updateActionBarSubtitle()
            }
        }
        autoReconnectRow.addView(autoReconnectSwitch)

        return autoReconnectRow
    }

    // ──────────────────────────────────────────────────────────────
    //  Energy saving row
    // ──────────────────────────────────────────────────────────────

    private fun buildEnergySavingRow(context: Context, dp: (Int) -> Int): LinearLayout {
        val savedEnergySaving = repository.isEnergySaving()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(dp(14), SURFACE_CT)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = marginParams(bottom = dp(10))
        }

        val dot = View(context).apply {
            val sz = dp(9)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = dp(12) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (savedEnergySaving) GREEN else INK_MUTE)
            }
        }
        row.addView(dot)

        val textBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textBlock.addView(TextView(context).apply {
            text = "Energy saving"
            setTextColor(INK)
            textSize = 14f
        })
        textBlock.addView(TextView(context).apply {
            text = "Pause VPN when app is in background"
            setTextColor(INK_DIM)
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        })
        row.addView(textBlock)

        row.addView(Switch(context).apply {
            isChecked = savedEnergySaving
            setOnCheckedChangeListener { _, isChecked ->
                repository.setEnergySaving(isChecked)
                animateDotColor(dot, if (isChecked) GREEN else INK_MUTE)
            }
        })

        return row
    }

    // ──────────────────────────────────────────────────────────────
    //  Section label
    // ──────────────────────────────────────────────────────────────

    private fun buildSectionLabel(
        context: Context, dp: (Int) -> Int,
        title: String, actionText: String? = null, onAction: (() -> Unit)? = null
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = marginParams(top = dp(16), bottom = dp(8))
        }
        row.addView(TextView(context).apply {
            text = title.uppercase()
            setTextColor(BLUE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
        })
        row.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        if (actionText != null && onAction != null) {
            row.addView(TextView(context).apply {
                text = actionText
                setTextColor(BLUE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setOnClickListener { onAction() }
            })
        }
        return row
    }

    // ──────────────────────────────────────────────────────────────
    //  Add config section
    // ──────────────────────────────────────────────────────────────

    private fun buildAddConfigSection(context: Context, dp: (Int) -> Int): LinearLayout {
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = marginParams(bottom = dp(4))
        }

        // Input card (contains EditText + inline preview strip)
        addInputCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(dp(12), SURFACE_CT)
            layoutParams = marginParams(bottom = dp(10))
        }

        linkInput = EditText(context).apply {
            hint = "vless://  vmess://  ss://  trojan://"
            setTextColor(INK)
            setHintTextColor(INK_VERY_MUTE)
            background = null
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            isSingleLine = false
        }
        addInputCard.addView(linkInput)

        // Inline preview strip (initially hidden)
        inlinePreview = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = marginParams()
            visibility = View.GONE
        }
        addInputCard.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            )
            setBackgroundColor(OUTLINE)
            visibility = View.GONE
            tag = "preview_divider"
        })
        addInputCard.addView(inlinePreview)

        wrapper.addView(addInputCard)

        // Button row
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val pasteBtn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundedBg(dp(12), SURFACE_CT_HIGH)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }
        pasteBtn.addView(TextView(context).apply {
            text = "Paste"
            setTextColor(INK)
            textSize = 14f
        })
        pasteBtn.setOnClickListener { pasteFromClipboard() }
        btnRow.addView(pasteBtn)

        addConnectBtn = TextView(context).apply {
            text = "Connect"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            alpha = 0.5f
            background = roundedBg(dp(12), BLUE)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        addConnectBtn.setOnClickListener { onConnectClicked() }
        btnRow.addView(addConnectBtn)

        wrapper.addView(btnRow)
        return wrapper
    }

    // ──────────────────────────────────────────────────────────────
    //  Actions
    // ──────────────────────────────────────────────────────────────

    private fun onHeroActionClicked() {
        when (currentState) {
            is VpnProxyManager.ProxyState.Idle, is VpnProxyManager.ProxyState.Error -> {
                val active = repository.getActive()
                if (active != null) {
                    repository.setVpnRunning(true)   // remember intent for restore / energy-saving resume
                    manager.startProxy(active)
                    injectProxy()
                } else {
                    showToast("Paste a config link below to connect")
                }
            }
            is VpnProxyManager.ProxyState.Connecting -> {
                repository.setVpnRunning(false)      // user cancelled — stay off on re-entry
                manager.stopProxy()
                clearProxy()
            }
            is VpnProxyManager.ProxyState.Connected -> {
                repository.setVpnRunning(false)      // user disconnected — stay off on re-entry
                manager.stopProxy()
                clearProxy()
            }
        }
    }

    private fun onConnectClicked() {
        val text = linkInput.text.toString().trim()
        if (text.isBlank()) {
            showToast("Paste a config link first")
            return
        }
        manager.parseLink(text)
            .onSuccess { config ->
                repository.save(config)
                repository.setActive(config.id)
                repository.setVpnRunning(true)
                manager.startProxy(config)
                configs.clear()
                configs.addAll(repository.getAll())
                val ctx = context ?: return@onSuccess
                val dp = { v: Int -> AndroidUtilities.dp(v.toFloat()) }
                rebuildConfigsList(ctx, dp)
                launchPings()
                injectProxy()
                linkInput.setText("")
            }
            .onFailure { e ->
                showToast("Invalid link: ${e.message}")
            }
    }

    private fun pasteFromClipboard() {
        val cb = context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cb?.primaryClip?.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank()) {
            showToast("Clipboard is empty")
            return
        }
        linkInput.setText(text)
    }

    private fun showInlinePreview(text: String) {
        manager.parseLink(text)
            .onSuccess { config ->
                inlinePreview.removeAllViews()
                val ctx = context ?: return@onSuccess

                fun chip(t: String, color: Int, bg: Int) = TextView(ctx).apply {
                    this.text = t
                    setTextColor(color)
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = 0.06f
                    background = roundedBg(AndroidUtilities.dp(5f), bg)
                    setPadding(
                        AndroidUtilities.dp(7f), AndroidUtilities.dp(3f),
                        AndroidUtilities.dp(7f), AndroidUtilities.dp(3f)
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = AndroidUtilities.dp(6f) }
                }

                inlinePreview.addView(chip(config.protocolLabel.uppercase(), BLUE, BLUE_TINT))

                inlinePreview.addView(TextView(ctx).apply {
                    this.text = "${config.address}:${config.port}"
                    setTextColor(INK_DIM)
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                })

                if (config.security != SecurityType.NONE) {
                    inlinePreview.addView(chip("TLS", GREEN, GREEN_TINT))
                }

                val divider = (addInputCard.getChildAt(1) as? View)
                divider?.visibility = View.VISIBLE
                inlinePreview.visibility = View.VISIBLE
            }
            .onFailure { hideInlinePreview() }
    }

    private fun hideInlinePreview() {
        val divider = (addInputCard.getChildAt(1) as? View)
        divider?.visibility = View.GONE
        inlinePreview.visibility = View.GONE
    }

    private fun showToast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    // ──────────────────────────────────────────────────────────────
    //  UI state machine
    // ──────────────────────────────────────────────────────────────

    private fun updateUi(state: VpnProxyManager.ProxyState) {
        if (fragmentView == null) return
        currentState = state

        when (state) {
            is VpnProxyManager.ProxyState.Idle -> {
                applyHeroStyle(INK_MUTE, SURFACE_CT, 0x00000000, "NOT CONNECTED")
                heroHintText.visibility = View.VISIBLE
                heroErrorRow.visibility = View.GONE
                heroServerRow.visibility = View.GONE
                heroStatsRow.visibility = View.GONE
                heroProgressContainer.visibility = View.GONE
                heroRightLabel.visibility = View.GONE
                setHeroButton("Connect", BLUE, Color.WHITE)
                uptimeHandler.removeCallbacks(uptimeRunnable)
                connectedSince = 0L
            }

            is VpnProxyManager.ProxyState.Connecting -> {
                applyHeroStyle(GOLD, GOLD_TINT, colorWithAlpha(GOLD, 0x22), "CONNECTING")
                heroHintText.visibility = View.GONE
                heroErrorRow.visibility = View.GONE
                repository.getActive()?.let { showServerRow(it) }
                heroStatsRow.visibility = View.GONE
                heroProgressContainer.visibility = View.VISIBLE
                heroRightLabel.text = "TLS HANDSHAKE"
                heroRightLabel.setTextColor(GOLD)
                heroRightLabel.visibility = View.VISIBLE
                heroPingContainer.visibility = View.GONE
                setHeroButton("Cancel", GOLD_TINT, GOLD)
                uptimeHandler.removeCallbacks(uptimeRunnable)
                connectedSince = 0L
            }

            is VpnProxyManager.ProxyState.Connected -> {
                applyHeroStyle(GREEN, GREEN_TINT, colorWithAlpha(GREEN, 0x22), "CONNECTED")
                heroHintText.visibility = View.GONE
                heroErrorRow.visibility = View.GONE
                showServerRow(state.config)
                // Show ping for active config
                val activePing = pingMap[state.config.id]
                updateHeroPing(activePing)
                heroStatsRow.visibility = View.VISIBLE
                heroProgressContainer.visibility = View.GONE
                heroRightLabel.setTextColor(GREEN)
                heroRightLabel.visibility = View.VISIBLE
                if (connectedSince == 0L) connectedSince = System.currentTimeMillis()
                uptimeHandler.removeCallbacks(uptimeRunnable)
                uptimeHandler.post(uptimeRunnable)
                setHeroButton("Disconnect", RED_TINT, RED, bordered = true)
            }

            is VpnProxyManager.ProxyState.Error -> {
                val reason = state.message.ifBlank { "Check your internet or try a different server." }
                applyHeroStyle(RED, RED_TINT, colorWithAlpha(RED, 0x22), "CONNECTION FAILED")
                heroHintText.visibility = View.GONE
                heroErrorText.text = reason
                heroErrorRow.visibility = View.VISIBLE
                heroServerRow.visibility = View.GONE
                heroStatsRow.visibility = View.GONE
                heroProgressContainer.visibility = View.GONE
                heroRightLabel.visibility = View.GONE
                setHeroButton("Retry", BLUE, Color.WHITE)
                uptimeHandler.removeCallbacks(uptimeRunnable)
                connectedSince = 0L
            }
        }

        updateActionBarSubtitle()

        val ctx = context ?: return
        val dp = { v: Int -> AndroidUtilities.dp(v.toFloat()) }
        rebuildConfigsList(ctx, dp)
    }

    private fun updateActionBarSubtitle() {
        val count = configs.size
        val subtitle = when (currentState) {
            is VpnProxyManager.ProxyState.Connected ->
                "${(currentState as VpnProxyManager.ProxyState.Connected).config.displayName} · Connected"
            is VpnProxyManager.ProxyState.Connecting ->
                "Establishing tunnel…"
            is VpnProxyManager.ProxyState.Error ->
                "$count saved · Disconnected"
            else ->
                "$count saved · Not connected"
        }
        actionBar.setSubtitle(subtitle)
    }

    private fun applyHeroStyle(dotColor: Int, cardBg: Int, borderColor: Int, label: String) {
        animateDotColor(heroStatusDot, dotColor)
        animateCardFill(heroCard, heroCardBg, cardBg)
        if (borderColor != 0x00000000) {
            heroCardBg.setStroke(AndroidUtilities.dp(1f), borderColor)
        } else {
            heroCardBg.setStroke(0, 0)
        }
        heroStatusLabel.text = label
        heroStatusLabel.setTextColor(dotColor)
    }

    private fun showServerRow(config: VpnConfig) {
        heroFlagView.text = countryCode(config.displayName)
        heroServerName.text = config.displayName
        heroServerHost.text = "${config.address}:${config.port}"
        heroServerRow.visibility = View.VISIBLE
    }

    private fun updateHeroPing(ping: Int?) {
        if (ping == null || ping <= 0 || ping >= 65000) {
            heroPingContainer.visibility = View.GONE
            return
        }
        val litCount = if (ping < 60) 3 else if (ping < 150) 2 else 1
        val pipColor = pingColor(ping)
        heroPingPips.forEachIndexed { i, pip ->
            (pip.background as? GradientDrawable)?.setColor(if (i < litCount) pipColor else INK_VERY_MUTE)
        }
        heroPingLabel.text = "${ping}ms"
        heroPingLabel.setTextColor(pipColor)
        heroPingContainer.visibility = View.VISIBLE
    }

    private fun setHeroButton(label: String, bg: Int, textColor: Int, bordered: Boolean = false) {
        heroActionLabel.text = label
        heroActionLabel.setTextColor(textColor)
        val drawable = roundedBg(AndroidUtilities.dp(12f), bg)
        if (bordered) {
            (drawable as GradientDrawable).setStroke(AndroidUtilities.dp(1f), textColor and 0x55FFFFFF.toInt())
        }
        heroActionBtn.background = drawable
    }

    // ──────────────────────────────────────────────────────────────
    //  Ping probes
    // ──────────────────────────────────────────────────────────────

    private fun launchPings() {
        configs.forEach { config ->
            scope.launch {
                val ping = tcpPing(config.address, config.port)
                pingMap[config.id] = ping
                val ctx = context ?: return@launch
                val dp = { v: Int -> AndroidUtilities.dp(v.toFloat()) }
                rebuildConfigsList(ctx, dp)
                // Also update hero if this is the active connected config
                if (currentState is VpnProxyManager.ProxyState.Connected &&
                    (currentState as VpnProxyManager.ProxyState.Connected).config.id == config.id) {
                    updateHeroPing(ping)
                }
            }
        }
    }

    private suspend fun tcpPing(host: String, port: Int): Int? = withContext(Dispatchers.IO) {
        try {
            val start = System.currentTimeMillis()
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), 3000)
            }
            (System.currentTimeMillis() - start).toInt()
        } catch (e: Exception) {
            null
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Subscriptions
    // ──────────────────────────────────────────────────────────────

    private fun rebuildSubscriptionsList(context: Context) {
        val container = subscriptionsContainer ?: return
        val dp = { v: Int -> AndroidUtilities.dp(v.toFloat()) }
        container.removeAllViews()

        val subs = subscriptionRepository.getAll()
        if (subs.isEmpty()) {
            container.addView(TextView(context).apply {
                text = "No subscriptions yet — tap Add to import a server-list URL"
                setTextColor(INK_DIM)
                textSize = 12f
                setPadding(dp(4), dp(2), dp(4), dp(8))
            })
            return
        }

        for (sub in subs) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBg(dp(12), SURFACE_CT)
                setPadding(dp(14), dp(10), dp(8), dp(10))
                layoutParams = marginParams(bottom = dp(8))
            }

            val textBlock = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textBlock.addView(TextView(context).apply {
                text = sub.name
                setTextColor(INK)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
            })
            textBlock.addView(TextView(context).apply {
                text = if (sub.lastUpdated > 0L) {
                    val ago = (System.currentTimeMillis() - sub.lastUpdated) / 60000
                    "${sub.configIds.size} servers · updated ${ago}m ago"
                } else {
                    sub.url.take(40) + if (sub.url.length > 40) "…" else ""
                }
                setTextColor(INK_MUTE)
                textSize = 11f
                typeface = Typeface.MONOSPACE
                maxLines = 1
            })
            row.addView(textBlock)

            // Refresh
            row.addView(TextView(context).apply {
                text = "↻"
                setTextColor(BLUE)
                textSize = 20f
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { fetchSubscription(context, sub) }
            })
            // Ping all + connect fastest
            row.addView(TextView(context).apply {
                text = "⚡"
                setTextColor(GREEN)
                textSize = 18f
                setPadding(dp(4), dp(4), dp(8), dp(4))
                setOnClickListener {
                    val subConfigs = sub.configIds.mapNotNull { id -> configs.firstOrNull { it.id == id } }
                    if (subConfigs.isEmpty()) showToast("Update subscription first")
                    else pingSubscription(context, subConfigs)
                }
            })
            // Delete (also removes its imported configs)
            row.addView(TextView(context).apply {
                text = "✕"
                setTextColor(RED)
                textSize = 14f
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setOnClickListener {
                    sub.configIds.forEach { repository.delete(it) }
                    subscriptionRepository.delete(sub.id)
                    configs.clear()
                    configs.addAll(repository.getAll())
                    rebuildConfigsList(context, dp)
                    rebuildSubscriptionsList(context)
                }
            })

            container.addView(row)
        }
    }

    private fun showAddSubscriptionDialog(context: Context) {
        val dp = { v: Int -> AndroidUtilities.dp(v.toFloat()) }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val nameInput = EditText(context).apply {
            hint = "Name (e.g. My Sub)"
            setTextColor(INK)
            setHintTextColor(INK_VERY_MUTE)
        }
        val urlInput = EditText(context).apply {
            hint = "Subscription URL"
            setTextColor(INK)
            setHintTextColor(INK_VERY_MUTE)
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
            val dp = { v: Int -> AndroidUtilities.dp(v.toFloat()) }
            try {
                val fetched = SubscriptionFetcher.fetch(sub.url)
                if (fetched.isEmpty()) {
                    showToast("No servers found in subscription")
                    return@launch
                }
                // Replace configs previously imported from this subscription
                subscriptionRepository.getById(sub.id)?.configIds?.forEach { repository.delete(it) }
                fetched.forEach { repository.save(it) }
                subscriptionRepository.save(
                    sub.copy(lastUpdated = System.currentTimeMillis(), configIds = fetched.map { it.id })
                )
                configs.clear()
                configs.addAll(repository.getAll())
                rebuildConfigsList(context, dp)
                rebuildSubscriptionsList(context)
                showToast("Loaded ${fetched.size} servers")
                pingSubscription(context, fetched)
            } catch (e: Exception) {
                showToast("Failed to fetch: ${e.message}")
            }
        }
    }

    private fun pingSubscription(context: Context, configsToPing: List<VpnConfig>) {
        scope.launch {
            val dp = { v: Int -> AndroidUtilities.dp(v.toFloat()) }
            showToast("Pinging ${configsToPing.size} servers…")
            val pairs = configsToPing.map { c -> async { c to tcpPing(c.address, c.port) } }.awaitAll()
            pairs.forEach { (c, p) -> pingMap[c.id] = p }
            rebuildConfigsList(context, dp)

            val fastest = pairs.mapNotNull { (c, p) -> p?.let { c to it } }.minByOrNull { it.second }
            if (fastest != null) {
                val (cfg, ms) = fastest
                repository.setActive(cfg.id)
                repository.setVpnRunning(true)
                if (manager.isRunning()) manager.stopProxy()
                manager.startProxy(cfg)
                injectProxy()
                showToast("Connected to ${cfg.displayName} (${ms}ms)")
            } else {
                showToast("No reachable servers found")
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Saved configs list
    // ──────────────────────────────────────────────────────────────

    private fun rebuildConfigsList(context: Context, dp: (Int) -> Int) {
        val container = configsContainer ?: return
        container.removeAllViews()
        val activeId = manager.getCurrentConfig()?.id

        if (configs.isEmpty()) {
            // Empty state — dashed card
            val emptyCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(SURFACE_CT)
                    setStroke(dp(2), OUTLINE)
                }
                setPadding(dp(16), dp(32), dp(16), dp(32))
                layoutParams = marginParams(bottom = dp(8))
            }
            val iconCircle = LinearLayout(context).apply {
                gravity = Gravity.CENTER
                val sz = dp(44)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(10)
                }
                background = roundedBg(dp(22), SURFACE_CT_HIGH)
            }
            iconCircle.addView(TextView(context).apply {
                text = "○"
                setTextColor(INK_MUTE)
                textSize = 22f
                gravity = Gravity.CENTER
            })
            emptyCard.addView(iconCircle)
            emptyCard.addView(TextView(context).apply {
                text = "No saved configurations"
                setTextColor(INK)
                textSize = 14f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            })
            emptyCard.addView(TextView(context).apply {
                text = "Paste a config link above to save and connect."
                setTextColor(INK_DIM)
                textSize = 12f
                gravity = Gravity.CENTER
            })
            container.addView(emptyCard)
            return
        }

        configs.forEach { config ->
            val isActive = config.id == activeId
            container.addView(buildConfigRow(context, dp, config, isActive))
        }
    }

    private fun buildConfigRow(
        context: Context, dp: (Int) -> Int,
        config: VpnConfig, isActive: Boolean
    ): LinearLayout {
        val ping = pingMap[config.id]
        val isOffline = ping == null && pingMap.containsKey(config.id) // probed but timed out

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(dp(14), if (isActive) GREEN_TINT else SURFACE_CT).also {
                if (isActive) (it as GradientDrawable).setStroke(dp(1), GREEN and 0x33FFFFFF.toInt())
            }
            setPadding(dp(14), dp(12), dp(12), dp(12))
            layoutParams = marginParams(bottom = dp(8))
        }

        // Flag tile
        val flagTile = TextView(context).apply {
            text = countryCode(config.displayName)
            gravity = Gravity.CENTER
            setTextColor(INK_DIM)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            val sz = dp(36)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = dp(12) }
            background = roundedBg(dp(8), SURFACE_CT_HIGH).also {
                (it as GradientDrawable).setStroke(dp(1), OUTLINE)
            }
        }
        card.addView(flagTile)

        // Middle: protocol badge + name + host
        val middle = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val badge = TextView(context).apply {
            text = config.protocolLabel.uppercase()
            setTextColor(if (isActive) GREEN else BLUE)
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.07f
            background = roundedBg(dp(5), if (isActive) GREEN_TINT else BLUE_TINT).also {
                (it as GradientDrawable).setStroke(dp(1), (if (isActive) GREEN else BLUE) and 0x33FFFFFF.toInt())
            }
            setPadding(dp(7), dp(3), dp(7), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }
        topRow.addView(badge)

        topRow.addView(TextView(context).apply {
            text = config.displayName
            setTextColor(INK)
            textSize = 14f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        middle.addView(topRow)

        middle.addView(TextView(context).apply {
            text = "${config.address}:${config.port}"
            setTextColor(INK_DIM)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        })
        card.addView(middle)

        // Right column: ping + active/connect + delete
        val rightCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Ping row (pips + ms or OFFLINE or skeleton dash)
        val pingRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(5) }
        }
        if (isOffline) {
            pingRow.addView(TextView(context).apply {
                text = "OFFLINE"
                setTextColor(RED)
                textSize = 10f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                letterSpacing = 0.07f
            })
        } else if (ping != null && ping > 0) {
            // Pips
            val litCount = if (ping < 60) 3 else if (ping < 150) 2 else 1
            val pipC = pingColor(ping)
            val pipsLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(5) }
            }
            listOf(dp(5), dp(8), dp(11)).forEachIndexed { i, h ->
                pipsLayout.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(3), h).apply {
                        if (i > 0) marginStart = dp(2)
                    }
                    background = roundedBg(dp(1), if (i < litCount) pipC else INK_VERY_MUTE)
                })
            }
            pingRow.addView(pipsLayout)
            pingRow.addView(TextView(context).apply {
                text = "${ping}ms"
                setTextColor(pipC)
                textSize = 12f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            })
        } else {
            // Still probing — show faint dash
            pingRow.addView(TextView(context).apply {
                text = "—"
                setTextColor(INK_VERY_MUTE)
                textSize = 12f
                typeface = Typeface.MONOSPACE
            })
        }
        rightCol.addView(pingRow)

        // Active badge or Connect button
        if (isActive) {
            rightCol.addView(TextView(context).apply {
                text = "ACTIVE"
                setTextColor(GREEN)
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
                background = roundedBg(dp(4), GREEN_TINT)
                setPadding(dp(7), dp(3), dp(7), dp(3))
                gravity = Gravity.CENTER
            })
        } else {
            val connectEnabled = !isOffline
            rightCol.addView(TextView(context).apply {
                text = "Connect"
                setTextColor(Color.WHITE)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                alpha = if (connectEnabled) 1f else 0.4f
                background = roundedBg(dp(12), BLUE)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                if (connectEnabled) {
                    setOnClickListener {
                        repository.setActive(config.id)
                        repository.setVpnRunning(true)
                        if (manager.isRunning()) manager.stopProxy()
                        manager.startProxy(config)
                        injectProxy()
                        configs.clear()
                        configs.addAll(repository.getAll())
                        val ctx = context ?: return@setOnClickListener
                        rebuildConfigsList(ctx, { v -> AndroidUtilities.dp(v.toFloat()) })
                    }
                }
            })
        }

        // Delete button
        rightCol.addView(TextView(context).apply {
            text = "✕"
            textSize = 15f
            setTextColor(RED)
            setPadding(dp(8), dp(6), dp(4), dp(2))
            setOnClickListener {
                if (manager.getCurrentConfig()?.id == config.id) {
                    repository.setVpnRunning(false)
                    manager.stopProxy(); clearProxy()
                }
                repository.delete(config.id)
                configs.clear()
                configs.addAll(repository.getAll())
                pingMap.remove(config.id)
                val ctx = context ?: return@setOnClickListener
                rebuildConfigsList(ctx, { v -> AndroidUtilities.dp(v.toFloat()) })
                updateActionBarSubtitle()
            }
        })

        card.addView(rightCol)
        return card
    }

    // ──────────────────────────────────────────────────────────────
    //  Animations
    // ──────────────────────────────────────────────────────────────

    private fun animateDotColor(dot: View, toColor: Int) {
        val bg = dot.background as? GradientDrawable ?: return
        val from = dot.tag as? Int ?: INK_MUTE
        ValueAnimator.ofObject(ArgbEvaluator(), from, toColor).apply {
            duration = 350
            addUpdateListener { bg.setColor(it.animatedValue as Int) }
            start()
        }
        dot.tag = toColor
    }

    private fun animateCardFill(card: LinearLayout, bg: GradientDrawable, toColor: Int) {
        val from = card.tag as? Int ?: SURFACE_CT
        ValueAnimator.ofObject(ArgbEvaluator(), from, toColor).apply {
            duration = 240
            addUpdateListener { bg.setColor(it.animatedValue as Int) }
            start()
        }
        card.tag = toColor
    }

    // ──────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────

    private fun pingColor(ping: Int): Int =
        if (ping < 60) GREEN else if (ping < 150) GOLD else RED

    private fun colorWithAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    private fun countryCode(name: String): String {
        val n = name.lowercase()
        return when {
            n.contains("frankfurt") || n.contains("berlin") || n.contains("german") || n.contains("de-") || n.contains("-de") -> "DE"
            n.contains("amsterdam") || n.contains("netherlands") || n.contains("nl-") || n.contains("-nl") -> "NL"
            n.contains("london") || n.contains("britain") || n.contains("gb-") || n.contains("-gb") || n.contains("uk-") -> "GB"
            n.contains("paris") || n.contains("france") || n.contains("fr-") || n.contains("-fr") -> "FR"
            n.contains("seoul") || n.contains("korea") || n.contains("kr-") || n.contains("-kr") -> "KR"
            n.contains("tokyo") || n.contains("japan") || n.contains("jp-") || n.contains("-jp") -> "JP"
            n.contains("singapore") || n.contains("sg-") || n.contains("-sg") -> "SG"
            n.contains("mumbai") || n.contains("india") || n.contains("in-") || n.contains("-in") -> "IN"
            n.contains("new york") || n.contains("dallas") || n.contains("chicago") || n.contains("us-") || n.contains("-us") -> "US"
            n.contains("toronto") || n.contains("canada") || n.contains("ca-") || n.contains("-ca") -> "CA"
            n.contains("sydney") || n.contains("australia") || n.contains("au-") || n.contains("-au") -> "AU"
            n.contains("moscow") || n.contains("russia") || n.contains("ru-") || n.contains("-ru") -> "RU"
            else -> name.take(2).uppercase()
        }
    }

    private fun roundedBg(radius: Int, color: Int): GradientDrawable =
        GradientDrawable().apply { cornerRadius = radius.toFloat(); setColor(color) }

    private fun fullWidthWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun marginParams(
        bottom: Int = 0, top: Int = 0, start: Int = 0, end: Int = 0
    ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        bottomMargin = bottom; topMargin = top; marginStart = start; marginEnd = end
    }

    private fun injectProxy() =
        TelegramProxyBridge.enableProxy(VpnProxyManager.LOCAL_HOST, VpnProxyManager.LOCAL_PORT)

    private fun clearProxy() = TelegramProxyBridge.disableProxy()

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        uptimeHandler.removeCallbacks(uptimeRunnable)
        scope.cancel()
    }
}
