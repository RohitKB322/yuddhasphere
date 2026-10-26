package com.yuddhasphere.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.yuddhasphere.R
import com.yuddhasphere.assets.AssetRegistry
import com.yuddhasphere.state.LocalGameState

class MilitarySheet(
    private val context: Context,
    private val gameState: LocalGameState,
    private val onSortieClicked: (String) -> Unit
) {
    private var dialog: BottomSheetDialog? = null

    fun show() {
        dialog = BottomSheetDialog(context, R.style.Theme_YuddhaSphere)
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_military, null)
        dialog?.setContentView(view)

        // Keep map and top HUD visible without dim
        dialog?.window?.apply {
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.0f)
        }

        val container = view.findViewById<LinearLayout>(R.id.militaryListContainer)
        container.removeAllViews()

        // 1. Airbase Jets Section
        val tvJetHeader = TextView(context).apply {
            text = "STRIKE AIRCRAFT HANGAR"
            setTextColor(context.getColor(R.color.white))
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 8, 0, 8)
        }
        container.addView(tvJetHeader)

        for (jet in gameState.jetCatalog) {
            val itemView = LayoutInflater.from(context).inflate(R.layout.item_building_catalog, container, false)
            val ivIcon = itemView.findViewById<ImageView>(R.id.ivBuildingIcon)
            val tvName = itemView.findViewById<TextView>(R.id.tvBuildingName)
            val tvStats = itemView.findViewById<TextView>(R.id.tvBuildingStats)
            val tvDesc = itemView.findViewById<TextView>(R.id.tvBuildingDesc)
            val btnBuy = itemView.findViewById<Button>(R.id.btnDeployBuilding)

            ivIcon.setImageResource(AssetRegistry.getDrawableForKey("unit." + jet.id))
            tvName.text = jet.name
            tvStats.text = "${jet.role} • Speed: ${jet.speedMps}m/s • Range: ${jet.rangeMeters / 1000}km"
            tvDesc.text = "Damage: ${jet.damage} • HP: ${jet.hp} • ${jet.description}"
            btnBuy.text = "🚀 Launch ($${jet.cost})"

            btnBuy.setOnClickListener {
                dialog?.dismiss()
                onSortieClicked(jet.id)
            }
            container.addView(itemView)
        }

        // 2. SAM Interceptors Section
        val tvSamHeader = TextView(context).apply {
            text = "SURFACE-TO-AIR INTERCEPTOR MUNITIONS"
            setTextColor(context.getColor(R.color.white))
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 16, 0, 8)
        }
        container.addView(tvSamHeader)

        for (intItem in gameState.interceptorCatalog) {
            val itemView = LayoutInflater.from(context).inflate(R.layout.item_building_catalog, container, false)
            val ivIcon = itemView.findViewById<ImageView>(R.id.ivBuildingIcon)
            val tvName = itemView.findViewById<TextView>(R.id.tvBuildingName)
            val tvStats = itemView.findViewById<TextView>(R.id.tvBuildingStats)
            val tvDesc = itemView.findViewById<TextView>(R.id.tvBuildingDesc)
            val btnBuy = itemView.findViewById<Button>(R.id.btnDeployBuilding)

            ivIcon.setImageResource(AssetRegistry.getDrawableForKey("interceptor." + intItem.id))
            tvName.text = intItem.name
            tvStats.text = "Range: ${intItem.rangeMeters / 1000}km • Hit Chance: ${(intItem.hitChance * 100).toInt()}%"
            tvDesc.text = "Reload: ${intItem.reloadSeconds}s • ${intItem.description}"
            btnBuy.text = "Equip ($${intItem.cost})"

            btnBuy.setOnClickListener {
                dialog?.dismiss()
                onSortieClicked(intItem.id)
            }
            container.addView(itemView)
        }

        dialog?.show()
    }
}
