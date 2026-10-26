package com.yuddhasphere.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.yuddhasphere.R
import com.yuddhasphere.models.AllianceItem
import com.yuddhasphere.network.GameClient
import com.yuddhasphere.state.LocalGameState

class AllianceSheet(
    private val context: Context,
    private val gameState: LocalGameState,
    private val gameClient: GameClient
) {
    private var dialog: BottomSheetDialog? = null
    private var currentTab = "clan" // "clan" or "diplomacy"

    fun show() {
        dialog = BottomSheetDialog(context, R.style.Theme_YuddhaSphere)
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_alliance, null)
        dialog?.setContentView(view)

        dialog?.window?.apply {
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.0f)
        }

        val tvBadge = view.findViewById<TextView>(R.id.tvCurrentAllianceBadge)
        val btnTabClan = view.findViewById<Button>(R.id.btnTabMyAlliance)
        val btnTabDiplomacy = view.findViewById<Button>(R.id.btnTabDiplomacy)
        val container = view.findViewById<LinearLayout>(R.id.allianceContentContainer)

        val playerAlliance = gameState.alliances.find { it.tag == gameState.player.allianceTag }
        tvBadge.text = playerAlliance?.let { "${it.tag} ${it.name}" } ?: "${gameState.player.allianceTag} Independent"

        fun renderContent() {
            container.removeAllViews()
            if (currentTab == "clan") {
                btnTabClan.setBackgroundResource(R.drawable.bg_accent_button)
                btnTabClan.setTextColor(context.getColor(R.color.bg_tactical_dark))
                btnTabDiplomacy.setBackgroundResource(R.drawable.bg_secondary_button)
                btnTabDiplomacy.setTextColor(Color.WHITE)

                val myAlliance = gameState.alliances.find { it.tag == gameState.player.allianceTag }
                if (myAlliance != null) {
                    renderMyAllianceView(container, myAlliance)
                } else {
                    renderRecruitmentView(container)
                }
            } else {
                btnTabDiplomacy.setBackgroundResource(R.drawable.bg_accent_button)
                btnTabDiplomacy.setTextColor(context.getColor(R.color.bg_tactical_dark))
                btnTabClan.setBackgroundResource(R.drawable.bg_secondary_button)
                btnTabClan.setTextColor(Color.WHITE)

                renderDiplomacyView(container)
            }
        }

        btnTabClan.setOnClickListener {
            currentTab = "clan"
            renderContent()
        }

        btnTabDiplomacy.setOnClickListener {
            currentTab = "diplomacy"
            renderContent()
        }

        renderContent()
        dialog?.show()
    }

    private fun renderMyAllianceView(container: LinearLayout, alliance: AllianceItem) {
        val density = context.resources.displayMetrics.density

        // Clan Overview Card
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_pill_stat)
            setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = (12 * density).toInt()
            layoutParams = params
        }

        val tvTitle = TextView(context).apply {
            text = "${alliance.name} ${alliance.tag}"
            setTextColor(Color.parseColor(alliance.colorHex))
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        }
        val tvMotto = TextView(context).apply {
            text = "\"${alliance.motto}\""
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 12f
            setPadding(0, (2 * density).toInt(), 0, (6 * density).toInt())
        }
        val tvStats = TextView(context).apply {
            text = "Supreme Leader: ${alliance.leaderCallsign}\nActive Members: ${alliance.memberCount} Commanders\nControlled Territory: ${alliance.territorySqKm} km²\nFederated Wealth: $%,d".format(alliance.totalWealth)
            setTextColor(Color.WHITE)
            textSize = 13f
            setLineSpacing(4f, 1f)
        }

        card.addView(tvTitle)
        card.addView(tvMotto)
        card.addView(tvStats)
        container.addView(card)

        // Member Roster Header
        val tvRosterHeader = TextView(context).apply {
            text = "ALLIANCE COMMAND ROSTER"
            setTextColor(context.getColor(R.color.primary_neon))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        container.addView(tvRosterHeader)

        // Members list
        val members = listOf(
            Triple("Supreme Cmdr. " + alliance.leaderCallsign, "Supreme Commander", "#00E5FF"),
            Triple("General Vane", "Air Defense Marshall", "#FF9F0A"),
            Triple("Commander " + gameState.player.callsign, "Forward Tactical Wing", "#30D158"),
            Triple("Colonel Ray", "Recon Division Leader", "#8E8E93")
        )
        for ((name, role, color) in members) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                setBackgroundResource(R.drawable.bg_secondary_button)
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.bottomMargin = (6 * density).toInt()
                layoutParams = params
            }
            val tvName = TextView(context).apply {
                text = name
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvRole = TextView(context).apply {
                text = role
                setTextColor(Color.parseColor(color))
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
            }
            row.addView(tvName)
            row.addView(tvRole)
            container.addView(row)
        }

        // Leave Alliance Button
        val btnLeave = Button(context).apply {
            text = "LEAVE ALLIANCE"
            setBackgroundResource(R.drawable.bg_danger_button)
            setTextColor(context.getColor(R.color.accent_red))
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (40 * density).toInt())
            params.topMargin = (12 * density).toInt()
            layoutParams = params
            setOnClickListener {
                gameState.leaveAlliance()
                dialog?.dismiss()
            }
        }
        container.addView(btnLeave)
    }

    private fun renderRecruitmentView(container: LinearLayout) {
        val density = context.resources.displayMetrics.density

        // Create Alliance Form Card
        val createCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_pill_stat)
            setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = (14 * density).toInt()
            layoutParams = params
        }

        val tvCreateTitle = TextView(context).apply {
            text = "CHARTER NEW ALLIANCE ($2,500)"
            setTextColor(context.getColor(R.color.primary_neon))
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        val etName = EditText(context).apply {
            hint = "Alliance Name (e.g. Iron Vanguard)"
            setHintTextColor(Color.parseColor("#636366"))
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundResource(R.drawable.bg_secondary_button)
            setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (40 * density).toInt())
            params.topMargin = (8 * density).toInt()
            layoutParams = params
        }
        val etTag = EditText(context).apply {
            hint = "Clan Tag (e.g. [IVG])"
            setHintTextColor(Color.parseColor("#636366"))
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundResource(R.drawable.bg_secondary_button)
            setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (40 * density).toInt())
            params.topMargin = (6 * density).toInt()
            layoutParams = params
        }
        val etMotto = EditText(context).apply {
            hint = "Motto (e.g. Victory through radar control)"
            setHintTextColor(Color.parseColor("#636366"))
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundResource(R.drawable.bg_secondary_button)
            setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (40 * density).toInt())
            params.topMargin = (6 * density).toInt()
            layoutParams = params
        }
        val btnCreate = Button(context).apply {
            text = "ESTABLISH ALLIANCE"
            setBackgroundResource(R.drawable.bg_accent_button)
            setTextColor(context.getColor(R.color.bg_tactical_dark))
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (38 * density).toInt())
            params.topMargin = (10 * density).toInt()
            layoutParams = params
            setOnClickListener {
                val name = etName.text.toString().trim()
                val tag = etTag.text.toString().trim()
                val motto = etMotto.text.toString().trim()
                if (name.isNotEmpty() && tag.isNotEmpty()) {
                    val result = gameState.createAlliance(name, tag, motto, "#00E5FF")
                    if (result.first) {
                        dialog?.dismiss()
                    } else {
                        Toast.makeText(context, result.second, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "Please enter both Alliance Name and Tag.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        createCard.addView(tvCreateTitle)
        createCard.addView(etName)
        createCard.addView(etTag)
        createCard.addView(etMotto)
        createCard.addView(btnCreate)
        container.addView(createCard)

        // Public Alliances List
        val tvListTitle = TextView(context).apply {
            text = "PUBLIC ALLIANCES TO JOIN"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        container.addView(tvListTitle)

        for (all in gameState.alliances) {
            val allRow = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_secondary_button)
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.bottomMargin = (8 * density).toInt()
                layoutParams = params
            }

            val tvRowHeader = TextView(context).apply {
                text = "${all.name} ${all.tag}"
                setTextColor(Color.parseColor(all.colorHex))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            }
            val tvRowDetail = TextView(context).apply {
                text = "${all.memberCount} Members • ${all.territorySqKm} km² territory • Net Worth $%,d".format(all.totalWealth)
                setTextColor(Color.parseColor("#8E8E93"))
                textSize = 11f
            }
            val btnJoin = Button(context).apply {
                text = "ENLIST / JOIN"
                setBackgroundResource(R.drawable.bg_accent_button)
                setTextColor(context.getColor(R.color.bg_tactical_dark))
                typeface = Typeface.DEFAULT_BOLD
                textSize = 11f
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (34 * density).toInt())
                params.topMargin = (6 * density).toInt()
                layoutParams = params
                setOnClickListener {
                    val res = gameState.joinAlliance(all.id)
                    if (res.first) {
                        dialog?.dismiss()
                    }
                }
            }

            allRow.addView(tvRowHeader)
            allRow.addView(tvRowDetail)
            allRow.addView(btnJoin)
            container.addView(allRow)
        }
    }

    private fun renderDiplomacyView(container: LinearLayout) {
        val density = context.resources.displayMetrics.density

        val tvHeader = TextView(context).apply {
            text = "FACTION TREATIES & DIPLOMATIC STANDING"
            setTextColor(context.getColor(R.color.primary_neon))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        container.addView(tvHeader)

        for (treaty in gameState.treaties) {
            val treatyCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_pill_stat)
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.bottomMargin = (10 * density).toInt()
                layoutParams = params
            }

            val tvFactionName = TextView(context).apply {
                text = "${treaty.targetAllianceName} ${treaty.targetAllianceTag}"
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            }

            val statusText = when (treaty.status) {
                "pact" -> "🤝 MUTUAL DEFENSE PACT (Shared Territory & Radar)"
                "war" -> "⚔ STATE OF WAR (Hostile / Shoot on Sight)"
                else -> "⚖ NEUTRAL / CEASEFIRE"
            }
            val statusColor = when (treaty.status) {
                "pact" -> "#30D158"
                "war" -> "#FF453A"
                else -> "#FF9F0A"
            }

            val tvStatus = TextView(context).apply {
                text = statusText
                setTextColor(Color.parseColor(statusColor))
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, (2 * density).toInt(), 0, (8 * density).toInt())
            }

            // Action buttons row: Propose Pact vs Declare War
            val btnRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val btnPact = Button(context).apply {
                text = "Pact (Ally)"
                setBackgroundResource(R.drawable.bg_secondary_button)
                setTextColor(context.getColor(R.color.accent_green))
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, (32 * density).toInt(), 1f).apply { marginEnd = (4 * density).toInt() }
                setOnClickListener {
                    gameState.setTreatyStatus(treaty.allianceIdB, "pact")
                    renderDiplomacyView(container)
                }
            }
            val btnNeutral = Button(context).apply {
                text = "Neutral"
                setBackgroundResource(R.drawable.bg_secondary_button)
                setTextColor(Color.WHITE)
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, (32 * density).toInt(), 1f).apply {
                    marginStart = (2 * density).toInt()
                    marginEnd = (2 * density).toInt()
                }
                setOnClickListener {
                    gameState.setTreatyStatus(treaty.allianceIdB, "neutral")
                    renderDiplomacyView(container)
                }
            }
            val btnWar = Button(context).apply {
                text = "Declare War"
                setBackgroundResource(R.drawable.bg_danger_button)
                setTextColor(context.getColor(R.color.accent_red))
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, (32 * density).toInt(), 1.1f).apply { marginStart = (4 * density).toInt() }
                setOnClickListener {
                    gameState.setTreatyStatus(treaty.allianceIdB, "war")
                    renderDiplomacyView(container)
                }
            }

            btnRow.addView(btnPact)
            btnRow.addView(btnNeutral)
            btnRow.addView(btnWar)

            treatyCard.addView(tvFactionName)
            treatyCard.addView(tvStatus)
            treatyCard.addView(btnRow)
            container.addView(treatyCard)
        }
    }
}
