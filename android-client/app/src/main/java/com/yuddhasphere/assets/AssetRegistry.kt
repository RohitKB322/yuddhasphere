package com.yuddhasphere.assets

import com.yuddhasphere.R

/**
 * AssetRegistry resolves data-driven asset keys to Android vector drawables
 * using the fallback chain: exact key -> lower level -> category default -> fallback icon.
 */
object AssetRegistry {

    private val KEY_MAP = mapOf(
        // Settlements
        "building.village.l1" to R.drawable.ic_village,
        "building.town.l2" to R.drawable.ic_town,
        "building.city.l3" to R.drawable.ic_city,
        "building.metropolis.l4" to R.drawable.ic_metropolis,
        "building.town_hall.l1" to R.drawable.ic_town_hall,

        // Military & Defense
        "building.barracks.l1" to R.drawable.ic_barracks,
        "building.airbase.l1" to R.drawable.ic_airbase,
        "building.port.l1" to R.drawable.ic_port,
        "building.sam.l1" to R.drawable.ic_sam,
        "building.radar.l1" to R.drawable.ic_radar,
        "building.wall.l1" to R.drawable.ic_wall,

        // Resources
        "building.mine.l1" to R.drawable.ic_mine,

        // Units & Defense Munitions
        "unit.jet_f16" to R.drawable.ic_jet_f16,
        "unit.jet_mirage2000" to R.drawable.ic_jet_f16,
        "unit.jet_f15" to R.drawable.ic_jet_f16,
        "unit.jet_su27" to R.drawable.ic_jet_f16,
        "unit.jet_a10" to R.drawable.ic_jet_f16,
        "interceptor.int_short" to R.drawable.ic_interceptor,
        "interceptor.int_medium" to R.drawable.ic_interceptor,
        "interceptor.int_long" to R.drawable.ic_interceptor,

        // UI & Avatar
        "ui.icon.money" to R.drawable.ic_currency,
        "ui.icon.income" to R.drawable.ic_income,
        "ui.icon.hp" to R.drawable.ic_hp,
        "ui.icon.alert" to R.drawable.ic_alert,
        "player.avatar.default" to R.drawable.ic_avatar_player
    )

    fun getDrawableForStructure(type: String, level: Int = 1): Int {
        val exactKey = "building.$type.l$level"
        KEY_MAP[exactKey]?.let { return it }

        // Fallback to level 1
        val l1Key = "building.$type.l1"
        KEY_MAP[l1Key]?.let { return it }

        // Category / General fallback
        return when (type) {
            "village", "town", "city", "metropolis" -> R.drawable.ic_village
            "airbase" -> R.drawable.ic_airbase
            "sam" -> R.drawable.ic_sam
            "mine" -> R.drawable.ic_mine
            "barracks" -> R.drawable.ic_barracks
            "radar" -> R.drawable.ic_radar
            "wall" -> R.drawable.ic_wall
            "port" -> R.drawable.ic_port
            else -> R.drawable.ic_town_hall
        }
    }

    fun getDrawableForKey(assetKey: String): Int {
        return KEY_MAP[assetKey] ?: R.drawable.ic_village
    }
}
