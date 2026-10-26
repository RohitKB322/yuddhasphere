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
import com.yuddhasphere.models.CatalogBuilding
import com.yuddhasphere.state.LocalGameState

class BuildMenuSheet(
    private val context: Context,
    private val gameState: LocalGameState,
    private val onSelectBuildingForPlacement: (CatalogBuilding) -> Unit
) {
    private var dialog: BottomSheetDialog? = null

    fun show() {
        dialog = BottomSheetDialog(context, R.style.Theme_YuddhaSphere)
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_build, null)
        dialog?.setContentView(view)

        // Keep map and top HUD visible without dim
        dialog?.window?.apply {
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.0f)
        }

        val tvFunds = view.findViewById<TextView>(R.id.tvSheetAvailableFunds)
        tvFunds.text = "Available: $${gameState.player.wealth}"

        val container = view.findViewById<LinearLayout>(R.id.buildingsListContainer)
        container.removeAllViews()

        // Populate building items
        val buildings = gameState.catalogBuildings.values.toList()
        for (b in buildings) {
            val itemView = LayoutInflater.from(context).inflate(R.layout.item_building_catalog, container, false)

            val ivIcon = itemView.findViewById<ImageView>(R.id.ivBuildingIcon)
            val tvName = itemView.findViewById<TextView>(R.id.tvBuildingName)
            val tvStats = itemView.findViewById<TextView>(R.id.tvBuildingStats)
            val tvDesc = itemView.findViewById<TextView>(R.id.tvBuildingDesc)
            val btnDeploy = itemView.findViewById<Button>(R.id.btnDeployBuilding)

            ivIcon.setImageResource(AssetRegistry.getDrawableForStructure(b.type, 1))
            tvName.text = b.name

            val radiusText = "${b.radiusMeters.toInt()}m ${b.radiusType.replaceFirstChar { it.uppercase() }}"
            val incomeText = if (b.incomePerHour > 0) " • +$${b.incomePerHour}/hr" else ""
            tvStats.text = "$radiusText$incomeText"
            tvDesc.text = b.description

            if (b.type == "town_hall") {
                btnDeploy.text = "Starter HQ"
                btnDeploy.isEnabled = false
            } else {
                btnDeploy.text = "$${b.buildCost}"
                btnDeploy.setOnClickListener {
                    dialog?.dismiss()
                    onSelectBuildingForPlacement(b)
                }
            }

            container.addView(itemView)
        }

        dialog?.show()
    }
}
