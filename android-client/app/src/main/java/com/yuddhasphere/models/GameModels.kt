package com.yuddhasphere.models

import org.json.JSONArray
import org.json.JSONObject

data class GeoPoint(
    val lat: Double,
    val lng: Double,
    val altitude: Double = 0.0
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("lat", lat)
            put("lng", lng)
            put("altitude", altitude)
        }
    }
}

data class Player(
    var id: String = "p_local_commander",
    var callsign: String = "Commander",
    var team: String = "blue",
    var teamColor: String = "#00E5FF",
    var position: GeoPoint = GeoPoint(19.0760, 72.8777),
    var accuracy: Double = 15.0,
    var wealth: Int = 10000,
    var incomePerHour: Int = 100,
    var hp: Int = 1000,
    val maxHp: Int = 1000,
    var allianceTag: String = "",
    var allianceId: String? = null,
    var allianceRole: String = "Member",
    var xp: Int = 750,
    var rank: String = "Captain (Grade II)"
) {
    fun calculateNetWorth(structures: List<Structure>): Int {
        return wealth + structures.sumOf { (it.level * 2500) + (it.hp * 2) }
    }
}

data class HangarJetItem(
    val id: String,
    val catalogId: String,
    val name: String,
    val role: String,
    var hp: Int,
    val maxHp: Int,
    var status: String = "Ready in Hangar"
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("catalogId", catalogId)
            put("name", name)
            put("role", role)
            put("hp", hp)
            put("maxHp", maxHp)
            put("status", status)
        }
    }
}

data class Structure(
    val id: String,
    val ownerId: String,
    val team: String,
    val teamColor: String,
    var type: String,
    var name: String,
    var level: Int,
    val position: GeoPoint,
    var hp: Int,
    var maxHp: Int,
    var radiusMeters: Double,
    var radiusType: String,
    var online: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    var upgradeFinishedAt: Long? = null,
    val hangarJets: MutableList<HangarJetItem> = mutableListOf(),
    var samMunitions: Int = 6
) {
    fun maxHangarSlots(): Int = when (level) {
        1 -> 3
        2 -> 6
        else -> 9
    }

    fun maxSamSlots(): Int = when (level) {
        1 -> 4
        2 -> 6
        else -> 8
    }

    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("ownerId", ownerId)
            put("team", team)
            put("teamColor", teamColor)
            put("type", type)
            put("name", name)
            put("level", level)
            put("position", position.toJsonObject())
            put("hp", hp)
            put("maxHp", maxHp)
            put("radiusMeters", radiusMeters)
            put("radiusType", radiusType)
            put("online", online)
            if (upgradeFinishedAt != null) {
                put("upgradeFinishedAt", upgradeFinishedAt!!)
            }
            val jetsArr = JSONArray()
            hangarJets.forEach { jetsArr.put(it.toJsonObject()) }
            put("hangarJets", jetsArr)
            put("samMunitions", samMunitions)
        }
    }
}

data class UnitItem(
    val id: String,
    val ownerId: String,
    val team: String,
    val teamColor: String,
    val catalogId: String,
    val name: String,
    var position: GeoPoint,
    val originPosition: GeoPoint,
    var targetPosition: GeoPoint,
    var targetStructureId: String? = null,
    var hp: Int,
    var maxHp: Int,
    var order: String = "strike", // "strike", "patrol", "rtb"
    var speedMode: String = "subsonic", // "subsonic", "supersonic"
    var speedMps: Double = 220.0,
    var rangeMeters: Int = 15000,
    var fuelPercent: Double = 100.0,
    var weaponsLoadout: String = "2x GBU-12 Laser-Guided • 2x AIM-120 AMRAAM",
    var damage: Int = 60,
    val homeBaseId: String,
    var patrolAngle: Double = 0.0,
    var heading: Double = 0.0,
    var radarConeAngle: Int = 60, // 30, 60, or 90 degrees
    var roe: String = "fire_at_will" // "aggressive", "fire_at_will", "peaceful"
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("ownerId", ownerId)
            put("team", team)
            put("teamColor", teamColor)
            put("catalogId", catalogId)
            put("name", name)
            put("position", position.toJsonObject())
            put("originPosition", originPosition.toJsonObject())
            put("targetPosition", targetPosition.toJsonObject())
            put("hp", hp)
            put("maxHp", maxHp)
            put("order", order)
            put("speedMode", speedMode)
            put("speedMps", speedMps)
            put("fuelPercent", fuelPercent)
            put("weaponsLoadout", weaponsLoadout)
            put("damage", damage)
            put("homeBaseId", homeBaseId)
            put("heading", heading)
            put("radarConeAngle", radarConeAngle)
            put("roe", roe)
        }
    }
}

data class CatalogBuilding(
    val type: String,
    val name: String,
    val category: String,
    val radiusMeters: Double,
    val radiusType: String,
    val incomePerHour: Int,
    val hp: Int,
    val buildCost: Int,
    val description: String,
    val nextLevelType: String? = null,
    val upgradeCost: Int = 0,
    val upgradeDurationSeconds: Int = 0
)

data class JetItem(
    val id: String,
    val name: String,
    val role: String,
    val speedMps: Int,
    val rangeMeters: Int,
    val damage: Int,
    val hp: Int,
    val cost: Int,
    val weapons: String = "2x GBU-12 Guided Bombs • 2x AIM-120",
    val description: String
)

data class InterceptorItem(
    val id: String,
    val name: String,
    val rangeMeters: Int,
    val hitChance: Double,
    val cost: Int,
    val reloadSeconds: Int,
    val description: String
)

data class AllianceMember(
    val playerId: String,
    val callsign: String,
    var role: String = "Member", // "Supreme Commander", "Financial Minister", "Foreign Minister", "Member"
    var netWorth: Int = 10000,
    val joinedAt: Long = System.currentTimeMillis()
)

data class AllianceItem(
    val id: String,
    val name: String,
    val tag: String,
    var leaderId: String,
    var leaderCallsign: String,
    val members: MutableList<AllianceMember> = mutableListOf(),
    var totalWealth: Int = 25000,
    var territorySqKm: Double = 1.2,
    var taxRatePercent: Int = 10,
    var treasuryCredits: Int = 5000,
    var colorHex: String = "#00E5FF",
    var motto: String = "Dominance through strategy.",
    val createdAt: Long = System.currentTimeMillis()
) {
    val memberCount: Int
        get() = members.size
}

data class TreatyItem(
    val id: String,
    val allianceIdA: String,
    val allianceIdB: String,
    val targetAllianceName: String,
    val targetAllianceTag: String,
    var status: String = "neutral", // "pact", "neutral", "war"
    var updatedAt: Long = System.currentTimeMillis()
)

data class CombatReportItem(
    val id: String,
    val timestamp: Long = System.currentTimeMillis(),
    val category: String, // "combat", "economy", "radar", "diplomacy"
    val urgencyTag: String = "BREAKING ALERT",
    val title: String,
    val detail: String,
    val level: String = "info", // "info", "warn", "danger"
    val sector: String = "Sector 4 [Airspace]"
)

data class LeaderboardItem(
    val rank: Int,
    val name: String,
    val tag: String,
    val score: Int,
    val detail: String,
    val type: String // "player" or "alliance"
)
