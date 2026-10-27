package com.yuddhasphere.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.yuddhasphere.R
import com.yuddhasphere.assets.AssetRegistry
import com.yuddhasphere.network.GameClient
import com.yuddhasphere.state.LocalGameState

class ProcurementSheet(
    private val context: Context,
    private val gameState: LocalGameState,
    private val gameClient: GameClient? = null,
    private val targetStructureId: String,
    private val category: String, // "airbase", "sam", or "missile_silo"
    private val onSuccess: (() -> Unit)? = null
) {
    private var dialog: BottomSheetDialog? = null

    fun show() {
        dialog = BottomSheetDialog(context, R.style.Theme_YuddhaSphere)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_procurement, null)
        dialog?.setContentView(view)

        // Ensure 80% transparent background displays map underneath
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.0f)
        }

        val tvIcon = view.findViewById<TextView>(R.id.tvProcurementCategoryIcon)
        val tvTitle = view.findViewById<TextView>(R.id.tvProcurementTitle)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvProcurementSubtitle)
        val tvBanner = view.findViewById<TextView>(R.id.tvProcurementCapacityBanner)
        val container = view.findViewById<LinearLayout>(R.id.containerProcureItems)
        val btnClose = view.findViewById<ImageButton>(R.id.btnCloseProcurement)
        val btnBottomCancel = view.findViewById<Button>(R.id.btnBottomCancelProcurement)

        val dismissAction = View.OnClickListener { dialog?.dismiss() }
        btnClose.setOnClickListener(dismissAction)
        btnBottomCancel.setOnClickListener(dismissAction)

        container.removeAllViews()

        when (category) {
            "airbase" -> {
                val base = gameState.structures.find { it.id == targetStructureId }
                    ?: gameState.structures.find { it.type == "airbase" && it.online }

                tvIcon.text = "✈"
                tvTitle.text = "AIR COMBAT HANGAR PROCUREMENT"
                tvSubtitle.text = "Procure fighter jets stationed at Airbase Hangar"

                val maxSlots = base?.maxHangarSlots() ?: 4
                val occupied = base?.hangarJets?.size ?: 0
                val isFull = occupied >= maxSlots

                if (isFull) {
                    tvBanner.text = "⚠️ HANGAR FULL ($occupied / $maxSlots BAYS OCCUPIED) — Upgrade Airbase to expand"
                    tvBanner.setTextColor(context.getColor(R.color.accent_red))
                } else {
                    tvBanner.text = "✓ HANGAR BAYS: $occupied / $maxSlots OCCUPIED"
                    tvBanner.setTextColor(context.getColor(R.color.accent_green))
                }

                for (jet in gameState.jetCatalog) {
                    val itemView = LayoutInflater.from(context).inflate(R.layout.item_procurement, container, false)
                    val ivIcon = itemView.findViewById<ImageView>(R.id.ivProcureIcon)
                    val tvName = itemView.findViewById<TextView>(R.id.tvProcureName)
                    val tvRole = itemView.findViewById<TextView>(R.id.tvProcureRole)
                    val tvCost = itemView.findViewById<TextView>(R.id.tvProcureCost)
                    val tvPayload = itemView.findViewById<TextView>(R.id.tvProcurePayload)
                    val tvRange = itemView.findViewById<TextView>(R.id.tvProcureRange)
                    val tvSpeed = itemView.findViewById<TextView>(R.id.tvProcureSpeed)
                    val btnProcure = itemView.findViewById<Button>(R.id.btnProcureAction)

                    ivIcon.setImageResource(AssetRegistry.getDrawableForKey("unit." + jet.id))
                    tvName.text = jet.name
                    tvRole.text = "${jet.role} • HP: ${jet.hp}"
                    tvCost.text = "$%,d".format(jet.cost)
                    tvPayload.text = "Payload: ${jet.weapons}"
                    tvRange.text = "Range: %,d km (Combat: %,d km)".format(jet.rangeMeters / 1000, jet.rangeMeters / 2200)
                    val mach = (jet.speedMps / 340.0)
                    tvSpeed.text = "Speed: Mach %.2f (%d km/h)".format(mach, (jet.speedMps * 3.6).toInt())

                    if (isFull) {
                        btnProcure.isEnabled = false
                        btnProcure.text = "🔒 HANGAR FULL ($occupied/$maxSlots)"
                        btnProcure.setBackgroundResource(R.drawable.bg_secondary_button)
                        btnProcure.setTextColor(Color.GRAY)
                    } else if (gameState.player.wealth < jet.cost) {
                        btnProcure.isEnabled = false
                        btnProcure.text = "INSUFFICIENT FUNDS ($%,d)".format(jet.cost)
                        btnProcure.setBackgroundResource(R.drawable.bg_secondary_button)
                        btnProcure.setTextColor(context.getColor(R.color.accent_red))
                    } else {
                        btnProcure.isEnabled = true
                        btnProcure.text = "🚀 PROCURE ($%,d)".format(jet.cost)
                        btnProcure.setOnClickListener {
                            if (base != null) {
                                val res = gameState.buyJetForAirbase(base.id, jet.id)
                                if (res.first) {
                                    gameClient?.sendBuyItem(base.id, jet.id)
                                    Toast.makeText(context, "${jet.name} delivered to Bay #${base.hangarJets.size}!", Toast.LENGTH_SHORT).show()
                                    dialog?.dismiss()
                                    onSuccess?.invoke()
                                } else {
                                    Toast.makeText(context, res.second, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }

                    container.addView(itemView)
                }
            }

            "sam" -> {
                val sam = gameState.structures.find { it.id == targetStructureId }
                    ?: gameState.structures.find { it.type == "sam" && it.online }

                tvIcon.text = "🛡️"
                tvTitle.text = "SAM THEATER DEFENSE PROCUREMENT"
                tvSubtitle.text = "Procure MIM-104 Patriot PAC-3 Interceptors"

                val maxSlots = sam?.maxSamSlots() ?: 6
                val armed = sam?.samMunitions ?: 0
                val isFull = armed >= maxSlots

                if (isFull) {
                    tvBanner.text = "⚠️ SAM SILOS FULL ($armed / $maxSlots ARMED)"
                    tvBanner.setTextColor(context.getColor(R.color.accent_red))
                } else {
                    tvBanner.text = "✓ SAM INTERCEPTORS: $armed / $maxSlots ARMED"
                    tvBanner.setTextColor(context.getColor(R.color.accent_green))
                }

                // SAM Item
                val itemView = LayoutInflater.from(context).inflate(R.layout.item_procurement, container, false)
                val ivIcon = itemView.findViewById<ImageView>(R.id.ivProcureIcon)
                val tvName = itemView.findViewById<TextView>(R.id.tvProcureName)
                val tvRole = itemView.findViewById<TextView>(R.id.tvProcureRole)
                val tvCost = itemView.findViewById<TextView>(R.id.tvProcureCost)
                val tvPayload = itemView.findViewById<TextView>(R.id.tvProcurePayload)
                val tvRange = itemView.findViewById<TextView>(R.id.tvProcureRange)
                val tvSpeed = itemView.findViewById<TextView>(R.id.tvProcureSpeed)
                val btnProcure = itemView.findViewById<Button>(R.id.btnProcureAction)

                ivIcon.setImageResource(R.drawable.ic_sam)
                tvName.text = "MIM-104 Patriot PAC-3"
                tvRole.text = "Hit-to-Kill Surface-to-Air Interceptor"
                tvCost.text = "$150 / Silo"
                tvPayload.text = "Payload: Kinetic Warhead • Active Ka-Band Radar Seeker"
                tvRange.text = "Range: 100 km Theater Defense Umbrella"
                tvSpeed.text = "Speed: Mach 4.8 (5,880 km/h)"

                if (isFull) {
                    btnProcure.isEnabled = false
                    btnProcure.text = "🔒 SILOS FULL ($armed/$maxSlots)"
                    btnProcure.setBackgroundResource(R.drawable.bg_secondary_button)
                    btnProcure.setTextColor(Color.GRAY)
                } else if (gameState.player.wealth < 150) {
                    btnProcure.isEnabled = false
                    btnProcure.text = "INSUFFICIENT FUNDS ($150)"
                    btnProcure.setBackgroundResource(R.drawable.bg_secondary_button)
                    btnProcure.setTextColor(context.getColor(R.color.accent_red))
                } else {
                    btnProcure.isEnabled = true
                    btnProcure.text = "⚡ ARM SILO ($150)"
                    btnProcure.setOnClickListener {
                        if (sam != null) {
                            val res = gameState.armSamSlot(sam.id)
                            if (res.first) {
                                Toast.makeText(context, "Loaded 1x PAC-3 Interceptor into Silo #${sam.samMunitions}!", Toast.LENGTH_SHORT).show()
                                dialog?.dismiss()
                                onSuccess?.invoke()
                            } else {
                                Toast.makeText(context, res.second, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                container.addView(itemView)
            }

            "missile_silo" -> {
                val silo = gameState.structures.find { it.id == targetStructureId }
                    ?: gameState.structures.find { it.type == "missile_silo" && it.online }

                tvIcon.text = "🚀"
                tvTitle.text = "STRATEGIC CRUISE MISSILE PROCUREMENT"
                tvSubtitle.text = "Arm BrahMos-ER Heavy Strategic Cruise Missiles"

                val maxSlots = silo?.maxMissileSlots() ?: 4
                val armed = silo?.missileMunitions ?: 0
                val isFull = armed >= maxSlots

                if (isFull) {
                    tvBanner.text = "⚠️ SILOS FULL ($armed / $maxSlots ARMED)"
                    tvBanner.setTextColor(context.getColor(R.color.accent_red))
                } else {
                    tvBanner.text = "✓ STRATEGIC SILOS: $armed / $maxSlots ARMED"
                    tvBanner.setTextColor(context.getColor(R.color.accent_green))
                }

                // Cruise missile item
                val itemView = LayoutInflater.from(context).inflate(R.layout.item_procurement, container, false)
                val ivIcon = itemView.findViewById<ImageView>(R.id.ivProcureIcon)
                val tvName = itemView.findViewById<TextView>(R.id.tvProcureName)
                val tvRole = itemView.findViewById<TextView>(R.id.tvProcureRole)
                val tvCost = itemView.findViewById<TextView>(R.id.tvProcureCost)
                val tvPayload = itemView.findViewById<TextView>(R.id.tvProcurePayload)
                val tvRange = itemView.findViewById<TextView>(R.id.tvProcureRange)
                val tvSpeed = itemView.findViewById<TextView>(R.id.tvProcureSpeed)
                val btnProcure = itemView.findViewById<Button>(R.id.btnProcureAction)

                ivIcon.setImageResource(R.drawable.ic_interceptor)
                tvName.text = "BrahMos-ER Strategic Cruise Missile"
                tvRole.text = "Heavy Ground-Attack Precision Cruise Missile"
                tvCost.text = "$850 / Missile"
                tvPayload.text = "Payload: 300kg Semi-Armor-Piercing • TERCOM / GPS Guidance"
                tvRange.text = "Range: 200 km Strategic Reach"
                tvSpeed.text = "Speed: Mach 0.85 (1,040 km/h Terrain Hugging)"

                if (isFull) {
                    btnProcure.isEnabled = false
                    btnProcure.text = "🔒 SILOS FULL ($armed/$maxSlots)"
                    btnProcure.setBackgroundResource(R.drawable.bg_secondary_button)
                    btnProcure.setTextColor(Color.GRAY)
                } else if (gameState.player.wealth < 850) {
                    btnProcure.isEnabled = false
                    btnProcure.text = "INSUFFICIENT FUNDS ($850)"
                    btnProcure.setBackgroundResource(R.drawable.bg_secondary_button)
                    btnProcure.setTextColor(context.getColor(R.color.accent_red))
                } else {
                    btnProcure.isEnabled = true
                    btnProcure.text = "🚀 RELOAD SILO ($850)"
                    btnProcure.setOnClickListener {
                        if (silo != null) {
                            val res = gameState.reloadMissileSilo(silo.id)
                            if (res.first) {
                                Toast.makeText(context, "Loaded 1x BrahMos-ER into Silo #${silo.missileMunitions}!", Toast.LENGTH_SHORT).show()
                                dialog?.dismiss()
                                onSuccess?.invoke()
                            } else {
                                Toast.makeText(context, res.second, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                container.addView(itemView)
            }
        }

        dialog?.show()
    }
}
