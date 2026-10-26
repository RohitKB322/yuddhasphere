package com.yuddhasphere.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.yuddhasphere.R
import com.yuddhasphere.models.CombatReportItem
import com.yuddhasphere.network.GameClient
import com.yuddhasphere.state.LocalGameState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReportsSheet(
    private val context: Context,
    private val gameState: LocalGameState,
    private val gameClient: GameClient
) {
    private var dialog: BottomSheetDialog? = null
    private var currentTab = "logs" // "logs" or "leaderboards"
    private var currentLogFilter = "all" // "all", "combat", "economy", "radar"
    private var currentMetric = "wealth" // "wealth", "territory", "xp"

    fun show() {
        dialog = BottomSheetDialog(context, R.style.Theme_YuddhaSphere)
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_reports, null)
        dialog?.setContentView(view)

        dialog?.window?.apply {
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.0f)
        }

        val btnTabLogs = view.findViewById<Button>(R.id.btnTabCombatLogs)
        val btnTabLeaderboards = view.findViewById<Button>(R.id.btnTabLeaderboards)
        val layoutFilterBar = view.findViewById<LinearLayout>(R.id.layoutFilterBar)
        val layoutMetricBar = view.findViewById<LinearLayout>(R.id.layoutLeaderboardMetricBar)
        val container = view.findViewById<LinearLayout>(R.id.reportsContentContainer)
        val tvCountBadge = view.findViewById<TextView>(R.id.tvReportCountBadge)

        val btnFilterAll = view.findViewById<Button>(R.id.btnFilterAll)
        val btnFilterCombat = view.findViewById<Button>(R.id.btnFilterCombat)
        val btnFilterEconomy = view.findViewById<Button>(R.id.btnFilterEconomy)
        val btnFilterRadar = view.findViewById<Button>(R.id.btnFilterRadar)

        val btnMetricWealth = view.findViewById<Button>(R.id.btnMetricWealth)
        val btnMetricTerritory = view.findViewById<Button>(R.id.btnMetricTerritory)
        val btnMetricXp = view.findViewById<Button>(R.id.btnMetricXp)

        fun updateFilterButtons() {
            val filterButtons = listOf(
                btnFilterAll to "all",
                btnFilterCombat to "combat",
                btnFilterEconomy to "economy",
                btnFilterRadar to "radar"
            )
            filterButtons.forEach { (btn, cat) ->
                if (cat == currentLogFilter) {
                    btn.setBackgroundResource(R.drawable.bg_accent_button)
                    btn.setTextColor(context.getColor(R.color.bg_tactical_dark))
                } else {
                    btn.setBackgroundResource(R.drawable.bg_secondary_button)
                    btn.setTextColor(Color.WHITE)
                }
            }
        }

        fun updateMetricButtons() {
            val metricButtons = listOf(
                btnMetricWealth to "wealth",
                btnMetricTerritory to "territory",
                btnMetricXp to "xp"
            )
            metricButtons.forEach { (btn, m) ->
                if (m == currentMetric) {
                    btn.setBackgroundResource(R.drawable.bg_accent_button)
                    btn.setTextColor(context.getColor(R.color.bg_tactical_dark))
                } else {
                    btn.setBackgroundResource(R.drawable.bg_secondary_button)
                    btn.setTextColor(Color.WHITE)
                }
            }
        }

        fun renderLogs() {
            container.removeAllViews()
            val density = context.resources.displayMetrics.density
            val list = if (currentLogFilter == "all") {
                gameState.combatReports
            } else {
                gameState.combatReports.filter { it.category == currentLogFilter }
            }

            tvCountBadge.text = "● ${list.size} Events"

            if (list.isEmpty()) {
                val tvEmpty = TextView(context).apply {
                    text = "No tactical entries recorded in this sector."
                    setTextColor(Color.parseColor("#8E8E93"))
                    textSize = 13f
                    setPadding(0, (20 * density).toInt(), 0, (20 * density).toInt())
                }
                container.addView(tvEmpty)
                return
            }

            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            for (item in list) {
                val itemCard = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.bg_pill_stat)
                    setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                    val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    params.bottomMargin = (8 * density).toInt()
                    layoutParams = params
                }

                // Header row: Category & Time
                val topRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                val tvCat = TextView(context).apply {
                    text = item.category.uppercase()
                    setTextColor(when (item.level) {
                        "danger" -> Color.parseColor("#FF453A")
                        "warn" -> Color.parseColor("#FF9F0A")
                        else -> Color.parseColor("#30D158")
                    })
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val tvTime = TextView(context).apply {
                    text = timeFormat.format(Date(item.timestamp))
                    setTextColor(Color.parseColor("#8E8E93"))
                    textSize = 10f
                }
                topRow.addView(tvCat)
                topRow.addView(tvTime)

                val tvTitle = TextView(context).apply {
                    text = item.title
                    setTextColor(Color.WHITE)
                    textSize = 13.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
                }

                val tvDetail = TextView(context).apply {
                    text = item.detail
                    setTextColor(Color.parseColor("#D1D1D6"))
                    textSize = 11.5f
                }

                itemCard.addView(topRow)
                itemCard.addView(tvTitle)
                itemCard.addView(tvDetail)
                container.addView(itemCard)
            }
        }

        fun renderLeaderboards() {
            container.removeAllViews()
            val density = context.resources.displayMetrics.density
            val entries = gameState.getLeaderboard(currentMetric)

            tvCountBadge.text = "Top ${entries.size} Factions"

            for (entry in entries) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.bg_secondary_button)
                    setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                    val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    params.bottomMargin = (6 * density).toInt()
                    layoutParams = params
                }

                val tvRank = TextView(context).apply {
                    text = "#${entry.rank}"
                    setTextColor(when (entry.rank) {
                        1 -> Color.parseColor("#FFD600")
                        2 -> Color.parseColor("#E5E5EA")
                        3 -> Color.parseColor("#FF9F0A")
                        else -> Color.parseColor("#8E8E93")
                    })
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                }

                val infoCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val tvName = TextView(context).apply {
                    text = "${entry.name} ${entry.tag}"
                    setTextColor(Color.WHITE)
                    textSize = 13.5f
                    typeface = Typeface.DEFAULT_BOLD
                }
                val tvDetail = TextView(context).apply {
                    text = entry.detail
                    setTextColor(Color.parseColor("#8E8E93"))
                    textSize = 11f
                }
                infoCol.addView(tvName)
                infoCol.addView(tvDetail)

                val tvScore = TextView(context).apply {
                    text = if (currentMetric == "wealth") "$%,d".format(entry.score) else if (currentMetric == "territory") "${entry.score / 10.0} km²" else "${entry.score} XP"
                    setTextColor(context.getColor(R.color.primary_neon))
                    textSize = 13.5f
                    typeface = Typeface.DEFAULT_BOLD
                }

                row.addView(tvRank)
                row.addView(infoCol)
                row.addView(tvScore)
                container.addView(row)
            }
        }

        fun renderCurrentTab() {
            if (currentTab == "logs") {
                btnTabLogs.setBackgroundResource(R.drawable.bg_accent_button)
                btnTabLogs.setTextColor(context.getColor(R.color.bg_tactical_dark))
                btnTabLeaderboards.setBackgroundResource(R.drawable.bg_secondary_button)
                btnTabLeaderboards.setTextColor(Color.WHITE)

                layoutFilterBar.visibility = View.VISIBLE
                layoutMetricBar.visibility = View.GONE
                updateFilterButtons()
                renderLogs()
            } else {
                btnTabLeaderboards.setBackgroundResource(R.drawable.bg_accent_button)
                btnTabLeaderboards.setTextColor(context.getColor(R.color.bg_tactical_dark))
                btnTabLogs.setBackgroundResource(R.drawable.bg_secondary_button)
                btnTabLogs.setTextColor(Color.WHITE)

                layoutFilterBar.visibility = View.GONE
                layoutMetricBar.visibility = View.VISIBLE
                updateMetricButtons()
                renderLeaderboards()
            }
        }

        btnTabLogs.setOnClickListener {
            currentTab = "logs"
            renderCurrentTab()
        }

        btnTabLeaderboards.setOnClickListener {
            currentTab = "leaderboards"
            renderCurrentTab()
        }

        btnFilterAll.setOnClickListener { currentLogFilter = "all"; updateFilterButtons(); renderLogs() }
        btnFilterCombat.setOnClickListener { currentLogFilter = "combat"; updateFilterButtons(); renderLogs() }
        btnFilterEconomy.setOnClickListener { currentLogFilter = "economy"; updateFilterButtons(); renderLogs() }
        btnFilterRadar.setOnClickListener { currentLogFilter = "radar"; updateFilterButtons(); renderLogs() }

        btnMetricWealth.setOnClickListener { currentMetric = "wealth"; updateMetricButtons(); renderLeaderboards() }
        btnMetricTerritory.setOnClickListener { currentMetric = "territory"; updateMetricButtons(); renderLeaderboards() }
        btnMetricXp.setOnClickListener { currentMetric = "xp"; updateMetricButtons(); renderLeaderboards() }

        renderCurrentTab()
        dialog?.show()
    }
}
