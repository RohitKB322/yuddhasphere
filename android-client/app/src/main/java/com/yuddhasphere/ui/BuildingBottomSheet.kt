package com.yuddhasphere.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.yuddhasphere.R
import com.yuddhasphere.assets.AssetRegistry
import com.yuddhasphere.models.Structure
import com.yuddhasphere.state.LocalGameState

class BuildingBottomSheet(
    private val context: Context,
    private val gameState: LocalGameState,
    private val onOpenMilitarySheet: (() -> Unit)? = null,
    private val onDismissCallback: (() -> Unit)? = null
) {
    private var dialog: BottomSheetDialog? = null
    private val handler = Handler(Looper.getMainLooper())
    private var activeStructureId: String? = null

    private val countdownRunnable = object : Runnable {
        override fun run() {
            updateUpgradeCountdown()
            handler.postDelayed(this, 1000)
        }
    }

    fun show(structureId: String) {
        val s = gameState.structures.find { it.id == structureId } ?: return
        activeStructureId = structureId

        dialog = BottomSheetDialog(context, R.style.Theme_YuddhaSphere)
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_structure, null)
        dialog?.setContentView(view)

        // Prevent map and top HUD from dimming black - keep map 100% visible
        dialog?.window?.apply {
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.0f)
        }

        bindViews(view, s)

        dialog?.setOnDismissListener {
            handler.removeCallbacks(countdownRunnable)
            activeStructureId = null
            onDismissCallback?.invoke()
        }

        handler.post(countdownRunnable)
        dialog?.show()
    }

    private fun bindViews(view: View, s: Structure) {
        val ivIcon = view.findViewById<ImageView>(R.id.ivDetailIcon)
        val tvName = view.findViewById<TextView>(R.id.tvDetailName)
        val tvLevel = view.findViewById<TextView>(R.id.tvDetailLevel)
        val tvStatus = view.findViewById<TextView>(R.id.tvDetailStatus)
        val tvHp = view.findViewById<TextView>(R.id.tvDetailHp)
        val pbHp = view.findViewById<ProgressBar>(R.id.pbDetailHp)
        val tvRadius = view.findViewById<TextView>(R.id.tvDetailRadius)
        val tvIncome = view.findViewById<TextView>(R.id.tvDetailIncome)
        val tvPop = view.findViewById<TextView>(R.id.tvDetailPop)
        val btnUpgrade = view.findViewById<Button>(R.id.btnUpgradeStructure)
        val btnRepair = view.findViewById<Button>(R.id.btnRepairStructure)
        val btnSell = view.findViewById<Button>(R.id.btnSellStructure)
        val btnSpecific = view.findViewById<Button>(R.id.btnSpecificAction)
        val upgradeContainer = view.findViewById<View>(R.id.upgradeContainer)
        val tvUpgradeHeader = view.findViewById<TextView>(R.id.tvUpgradeHeader)
        val tvUpgradeTimer = view.findViewById<TextView>(R.id.tvUpgradeTimer)
        val tvUpgradePreview = view.findViewById<TextView>(R.id.tvUpgradePreview)

        ivIcon.setImageResource(AssetRegistry.getDrawableForStructure(s.type, s.level))
        tvName.text = s.name
        tvLevel.text = "Level ${s.level} • ${s.radiusType.replaceFirstChar { it.uppercase() }} Structure"

        // Status
        if (s.upgradeFinishedAt != null) {
            tvStatus.text = "UPGRADING"
            tvStatus.setTextColor(context.getColor(R.color.accent_orange))
        } else if (s.online) {
            tvStatus.text = "ONLINE"
            tvStatus.setTextColor(context.getColor(R.color.accent_green))
        } else {
            tvStatus.text = "OFFLINE"
            tvStatus.setTextColor(context.getColor(R.color.accent_red))
        }

        // HP
        tvHp.text = "${s.hp} / ${s.maxHp}"
        pbHp.max = s.maxHp
        pbHp.progress = s.hp

        // Stats
        tvRadius.text = "${s.radiusMeters.toInt()} m"
        val cat = gameState.catalogBuildings[s.type]
        tvIncome.text = if (cat != null && cat.incomePerHour > 0) "+$${cat.incomePerHour}/hr" else "$0/hr"
        tvPop.text = if (cat != null && cat.category == "settlement") "${s.level * 100} cap" else "N/A"

        // Upgrade Section
        val nextType = cat?.nextLevelType
        if (nextType != null && cat.upgradeCost > 0) {
            upgradeContainer.visibility = View.VISIBLE
            val nextCat = gameState.catalogBuildings[nextType]
            tvUpgradeHeader.text = "Upgrade to ${nextCat?.name ?: "Next Tier"} (L${s.level + 1})"
            tvUpgradePreview.text = "Expands radius to ${nextCat?.radiusMeters?.toInt()}m • Income +$${nextCat?.incomePerHour}/hr • HP ${nextCat?.hp}"
            btnUpgrade.text = "UPGRADE ($${cat.upgradeCost})"

            btnUpgrade.setOnClickListener {
                val res = gameState.upgradeStructure(s.id)
                if (res.first) {
                    dialog?.dismiss()
                }
            }
        } else {
            upgradeContainer.visibility = View.GONE
        }

        // Specific actions for Airbase and SAM
        if (s.type == "airbase") {
            btnSpecific.visibility = View.VISIBLE
            btnSpecific.text = "✈ Open Hangar & Jet Catalog"
            btnSpecific.setOnClickListener {
                dialog?.dismiss()
                onOpenMilitarySheet?.invoke()
            }
        } else if (s.type == "sam") {
            btnSpecific.visibility = View.VISIBLE
            btnSpecific.text = "🚀 SAM Battery & Interceptors"
            btnSpecific.setOnClickListener {
                dialog?.dismiss()
                onOpenMilitarySheet?.invoke()
            }
        } else {
            btnSpecific.visibility = View.GONE
        }

        // Repair
        btnRepair.setOnClickListener {
            gameState.repairStructure(s.id)
            dialog?.dismiss()
        }

        // Sell
        btnSell.setOnClickListener {
            gameState.sellStructure(s.id)
            dialog?.dismiss()
        }
    }

    private fun updateUpgradeCountdown() {
        val id = activeStructureId ?: return
        val s = gameState.structures.find { it.id == id } ?: return
        val dialogView = dialog?.findViewById<View>(R.id.upgradeContainer) ?: return

        val tvTimer = dialogView.findViewById<TextView>(R.id.tvUpgradeTimer)
        val btnUpgrade = dialogView.findViewById<Button>(R.id.btnUpgradeStructure)

        val finishTime = s.upgradeFinishedAt
        if (finishTime != null) {
            val remainingSec = ((finishTime - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            tvTimer?.text = "Timer: ${remainingSec}s remaining"
            btnUpgrade?.isEnabled = false
            btnUpgrade?.text = "CONSTRUCTING..."
        }
    }
}
