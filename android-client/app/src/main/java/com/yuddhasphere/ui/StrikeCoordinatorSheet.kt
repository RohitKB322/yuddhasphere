package com.yuddhasphere.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.yuddhasphere.R
import com.yuddhasphere.models.GeoPoint
import com.yuddhasphere.state.LocalGameState
import com.yuddhasphere.utils.GeoMath
import java.util.Locale

class StrikeCoordinatorSheet(
    private val context: Context,
    private val targetPos: GeoPoint,
    private val targetName: String,
    private val gameState: LocalGameState,
    private val onLaunched: ((siloId: String, missileType: String) -> Unit)? = null
) {
    private var dialog: BottomSheetDialog? = null

    fun show() {
        dialog = BottomSheetDialog(context, R.style.Theme_YuddhaSphere)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_strike_coordinator, null)
        dialog?.setContentView(view)

        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.0f)
        }

        val tvSubtitle = view.findViewById<TextView>(R.id.tvStrikeSubtitle)
        val tvBanner = view.findViewById<TextView>(R.id.tvStrikeBanner)
        val container = view.findViewById<LinearLayout>(R.id.containerStrikeMissiles)
        val btnClose = view.findViewById<ImageButton>(R.id.btnCloseStrikeSheet)
        val btnCancel = view.findViewById<Button>(R.id.btnBottomCancelStrike)

        val dismissAction = View.OnClickListener { dialog?.dismiss() }
        btnClose.setOnClickListener(dismissAction)
        btnCancel.setOnClickListener(dismissAction)

        val formattedTarget = if (targetName.isNotBlank() && targetName != "Map Coordinates") {
            "$targetName (${String.format(Locale.US, "%.3f°N, %.3f°E", targetPos.lat, targetPos.lng)})"
        } else {
            String.format(Locale.US, "Coordinates: %.4f°N, %.4f°E", targetPos.lat, targetPos.lng)
        }
        tvSubtitle.text = formattedTarget

        val playerSilos = gameState.structures.filter {
            it.type == "missile_silo" && it.ownerId == gameState.player.id && it.online
        }

        if (playerSilos.isEmpty()) {
            tvBanner.text = "NO STRATEGIC MISSILE LAUNCHERS ACTIVE"
            tvBanner.setTextColor(Color.parseColor("#FF453A"))

            val tvEmpty = TextView(context).apply {
                text = "Deploy a Strategic Missile Silo facility to authorize long-range kinetic and ballistic strikes across the theater."
                setTextColor(Color.parseColor("#8E8E93"))
                textSize = 12f
                setPadding(0, 16, 0, 16)
            }
            container.addView(tvEmpty)
            dialog?.show()
            return
        }

        var totalViable = 0
        var totalMunitions = 0

        playerSilos.forEach { silo ->
            val equipped = silo.equippedMissiles
            totalMunitions += equipped.size

            equipped.forEachIndexed { slotIdx, mslId ->
                val msl = gameState.getMissileInfo(mslId)
                val distM = GeoMath.calculateDistanceMeters(silo.position, targetPos)
                val distKm = distM / 1000.0
                val inRange = distKm <= msl.rangeKm
                if (inRange) totalViable++

                val etaSec = (distM / (msl.speedMach * 340.0)).coerceAtLeast(4.0)

                val itemCard = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(if (inRange) R.drawable.bg_slot_occupied else R.drawable.bg_slot_empty)
                    setPadding(12, 10, 12, 10)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins(0, 0, 0, 8)
                    layoutParams = lp
                }

                // Row 1: Title and Range Badge
                val topRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }

                val tvName = TextView(context).apply {
                    text = "${msl.name} (#${slotIdx + 1})"
                    setTextColor(Color.WHITE)
                    textSize = 12.5f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    layoutParams = lp
                }
                topRow.addView(tvName)

                val tvBadge = TextView(context).apply {
                    if (inRange) {
                        text = "● IN RANGE (${distKm.toInt()} km / ${msl.rangeKm} km)"
                        setTextColor(Color.parseColor("#30D158"))
                    } else {
                        text = "✕ OUT OF RANGE (${distKm.toInt()} km > ${msl.rangeKm} km)"
                        setTextColor(Color.parseColor("#FF453A"))
                    }
                    textSize = 9.5f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                topRow.addView(tvBadge)
                itemCard.addView(topRow)

                // Row 2: Origin Silo & Specs
                val tvSpecs = TextView(context).apply {
                    text = "Launch Site: ${silo.name} • Mach ${msl.speedMach} • ETA ~${etaSec.toInt()}s • ${msl.role}"
                    setTextColor(Color.parseColor("#00E5FF"))
                    textSize = 10f
                    setPadding(0, 3, 0, 3)
                }
                itemCard.addView(tvSpecs)

                // Row 3: Warhead description
                val tvWarhead = TextView(context).apply {
                    text = "Payload: ${msl.payload}"
                    setTextColor(Color.parseColor("#8E8E93"))
                    textSize = 9.5f
                }
                itemCard.addView(tvWarhead)

                // Row 4: Action Button
                val btnLaunch = Button(context).apply {
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        32 * context.resources.displayMetrics.density.toInt()
                    )
                    lp.setMargins(0, 6, 0, 0)
                    layoutParams = lp
                    includeFontPadding = false
                    minHeight = 0
                    setPadding(0, 0, 0, 0)
                    textSize = 10.5f
                    setTypeface(null, android.graphics.Typeface.BOLD)

                    if (inRange) {
                        text = "🚀 LAUNCH ${msl.name.uppercase()}"
                        setBackgroundResource(R.drawable.bg_accent_button)
                        setTextColor(Color.parseColor("#0A0E1A"))
                        isEnabled = true
                        setOnClickListener {
                            dialog?.dismiss()
                            val res = gameState.fireSiloMissile(silo.id, targetPos, msl.id)
                            if (res.first) {
                                Toast.makeText(context, "${msl.name} launched successfully towards target!", Toast.LENGTH_SHORT).show()
                                onLaunched?.invoke(silo.id, msl.id)
                            } else {
                                Toast.makeText(context, res.second, Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        text = "TARGET EXCEEDS ${msl.rangeKm} KM RANGE"
                        setBackgroundResource(R.drawable.bg_secondary_button)
                        setTextColor(Color.parseColor("#636366"))
                        isEnabled = false
                    }
                }
                itemCard.addView(btnLaunch)

                container.addView(itemCard)
            }
        }

        if (totalMunitions == 0) {
            tvBanner.text = "ALL SILO BAYS EMPTY"
            tvBanner.setTextColor(Color.parseColor("#FF9500"))
            val tvEmpty = TextView(context).apply {
                text = "Your missile facilities currently have no munitions armed. Open the Missile Silo inspector to procure strategic missiles."
                setTextColor(Color.parseColor("#8E8E93"))
                textSize = 12f
                setPadding(0, 16, 0, 16)
            }
            container.addView(tvEmpty)
        } else {
            tvBanner.text = "READY FOR LAUNCH: $totalViable / $totalMunitions MISSILES IN STRIKE RANGE"
            tvBanner.setTextColor(if (totalViable > 0) Color.parseColor("#30D158") else Color.parseColor("#FF453A"))
        }

        dialog?.show()
    }
}
