package com.yuddhasphere.state

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.yuddhasphere.models.*
import com.yuddhasphere.utils.GeoMath
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

class LocalGameState(private val context: Context) {

    interface StateListener {
        fun onPlayerUpdated(player: Player)
        fun onStructuresUpdated(structures: List<Structure>)
        fun onUnitsUpdated(units: List<UnitItem>)
        fun onAlert(title: String, message: String)
        fun onStructureUpgradeCompleted(structure: Structure)
        fun onMissileFired(
            missileId: String,
            fromLat: Double,
            fromLng: Double,
            toLat: Double,
            toLng: Double,
            missileType: String,
            targetUnitId: String? = null,
            targetName: String = "Target Vector",
            speedMach: Double = 4.2,
            hitProbability: Double = 0.88
        )
    }

    var player: Player = Player()
    val structures: MutableList<Structure> = mutableListOf()
    val activeUnits: MutableList<UnitItem> = mutableListOf()
    val catalogBuildings: MutableMap<String, CatalogBuilding> = mutableMapOf()
    val jetCatalog: MutableList<JetItem> = mutableListOf()
    val interceptorCatalog: MutableList<InterceptorItem> = mutableListOf()
    val alliances: MutableList<AllianceItem> = mutableListOf()
    val treaties: MutableList<TreatyItem> = mutableListOf()
    val combatReports: MutableList<CombatReportItem> = mutableListOf()

    private val listeners: MutableList<StateListener> = mutableListOf()
    private val handler = Handler(Looper.getMainLooper())
    private var incomeAccumulator: Double = 0.0
    private var hasInitialGpsFixed: Boolean = false
    private var tickCounter: Int = 0
    private val samCooldownMap = mutableMapOf<String, Long>()

    private val tickRunnable = object : Runnable {
        override fun run() {
            onGameTick()
            handler.postDelayed(this, 1000)
        }
    }

    init {
        loadCatalogsFromAssets()
        val loaded = loadLocalWorld()
        if (!loaded) {
            initializeStarterBase()
            saveLocalWorld()
        }
        seedAlliancesAndReports()
        handler.postDelayed(tickRunnable, 1000)
    }

    fun saveLocalWorld() {
        try {
            val root = JSONObject()
            val pObj = JSONObject().apply {
                put("id", player.id)
                put("callsign", player.callsign)
                put("team", player.team)
                put("teamColor", player.teamColor)
                put("allianceTag", player.allianceTag)
                put("wealth", player.wealth)
                put("incomePerHour", player.incomePerHour)
                put("hp", player.hp)
                put("maxHp", player.maxHp)
                val pos = JSONObject().apply {
                    put("lat", player.position.lat)
                    put("lng", player.position.lng)
                }
                put("position", pos)
            }
            root.put("player", pObj)

            val sArr = JSONArray()
            structures.forEach { s ->
                sArr.put(s.toJsonObject())
            }
            root.put("structures", sArr)

            // Persist all in-flight aircraft sorties & dogfight state
            val uArr = JSONArray()
            activeUnits.forEach { u ->
                uArr.put(u.toJsonObject())
            }
            root.put("activeUnits", uArr)

            val file = File(context.filesDir, "world_cache.json")
            file.writeText(root.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadLocalWorld(): Boolean {
        try {
            val file = File(context.filesDir, "world_cache.json")
            if (!file.exists()) return false

            val root = JSONObject(file.readText())
            if (root.has("player")) {
                val pObj = root.getJSONObject("player")
                player.id = pObj.optString("id", player.id)
                player.callsign = pObj.optString("callsign", player.callsign)
                player.team = pObj.optString("team", player.team)
                player.teamColor = pObj.optString("teamColor", player.teamColor)
                player.allianceTag = pObj.optString("allianceTag", player.allianceTag)
                player.wealth = pObj.optInt("wealth", player.wealth)
                player.incomePerHour = pObj.optInt("incomePerHour", player.incomePerHour)
                player.hp = pObj.optInt("hp", player.hp)
                player.maxHp = pObj.optInt("maxHp", player.maxHp)
                if (pObj.has("position")) {
                    val posObj = pObj.getJSONObject("position")
                    player.position = GeoPoint(posObj.getDouble("lat"), posObj.getDouble("lng"))
                }
            }

            if (root.has("structures")) {
                structures.clear()
                val sArr = root.getJSONArray("structures")
                for (i in 0 until sArr.length()) {
                    val sObj = sArr.getJSONObject(i)
                    val posObj = sObj.getJSONObject("position")
                    val s = Structure(
                        id = sObj.getString("id"),
                        ownerId = sObj.optString("ownerId", player.id),
                        team = sObj.optString("team", player.team),
                        teamColor = sObj.optString("teamColor", player.teamColor),
                        type = sObj.getString("type"),
                        name = sObj.optString("name", "Structure"),
                        level = sObj.optInt("level", 1),
                        position = GeoPoint(posObj.getDouble("lat"), posObj.getDouble("lng")),
                        hp = sObj.optInt("hp", 100),
                        maxHp = sObj.optInt("maxHp", 100),
                        radiusMeters = if (sObj.getString("type") == "sam") 100000.0 else if (sObj.getString("type") == "missile_silo") 200000.0 else sObj.optDouble("radiusMeters", 150.0),
                        radiusType = sObj.optString("radiusType", "influence"),
                        online = sObj.optBoolean("online", true),
                        upgradeFinishedAt = if (sObj.has("upgradeFinishedAt") && !sObj.isNull("upgradeFinishedAt")) sObj.getLong("upgradeFinishedAt") else null,
                        samMunitions = sObj.optInt("samMunitions", 6),
                        missileMunitions = sObj.optInt("missileMunitions", 4)
                    )
                    if (sObj.has("hangarJets")) {
                        val jArr = sObj.getJSONArray("hangarJets")
                        for (j in 0 until jArr.length()) {
                            val jObj = jArr.getJSONObject(j)
                            s.hangarJets.add(
                                HangarJetItem(
                                    id = jObj.getString("id"),
                                    catalogId = jObj.getString("catalogId"),
                                    name = jObj.optString("name", "Fighter"),
                                    role = jObj.optString("role", "Air Superiority"),
                                    hp = jObj.optInt("hp", 100),
                                    maxHp = jObj.optInt("maxHp", 100),
                                    status = jObj.optString("status", "Ready in Hangar")
                                )
                            )
                        }
                    }
                    structures.add(s)
                }

                // Restore in-flight active units & dogfight positions
                if (root.has("activeUnits")) {
                    activeUnits.clear()
                    val uArr = root.getJSONArray("activeUnits")
                    for (i in 0 until uArr.length()) {
                        val uObj = uArr.getJSONObject(i)
                        val posObj = uObj.getJSONObject("position")
                        val origObj = uObj.getJSONObject("originPosition")
                        val targetObj = uObj.getJSONObject("targetPosition")
                        val unit = UnitItem(
                            id = uObj.getString("id"),
                            ownerId = uObj.optString("ownerId", player.id),
                            ownerCallsign = uObj.optString("ownerCallsign", "Commander"),
                            team = uObj.optString("team", player.team),
                            teamColor = uObj.optString("teamColor", player.teamColor),
                            catalogId = uObj.optString("catalogId", "jet_f16"),
                            name = uObj.optString("name", "Fighter Jet"),
                            position = GeoPoint(posObj.getDouble("lat"), posObj.getDouble("lng")),
                            originPosition = GeoPoint(origObj.getDouble("lat"), origObj.getDouble("lng")),
                            targetPosition = GeoPoint(targetObj.getDouble("lat"), targetObj.getDouble("lng")),
                            targetStructureId = if (uObj.has("targetStructureId") && !uObj.isNull("targetStructureId")) uObj.getString("targetStructureId") else null,
                            hp = uObj.optInt("hp", 100),
                            maxHp = uObj.optInt("maxHp", 100),
                            order = uObj.optString("order", "patrol"),
                            speedMode = uObj.optString("speedMode", "subsonic"),
                            speedMps = uObj.optDouble("speedMps", 220.0),
                            rangeMeters = uObj.optInt("rangeMeters", 100000),
                            fuelPercent = uObj.optDouble("fuelPercent", 100.0),
                            weaponsLoadout = uObj.optString("weaponsLoadout", "2x GBU-12 • 2x AIM-120"),
                            damage = uObj.optInt("damage", 60),
                            homeBaseId = uObj.optString("homeBaseId", ""),
                            patrolAngle = uObj.optDouble("patrolAngle", 0.0),
                            heading = uObj.optDouble("heading", 0.0),
                            radarConeAngle = uObj.optInt("radarConeAngle", 60),
                            roe = uObj.optString("roe", "fire_at_will"),
                            lockedTargetUnitId = if (uObj.has("lockedTargetUnitId") && !uObj.isNull("lockedTargetUnitId")) uObj.getString("lockedTargetUnitId") else null,
                            lockedTargetName = if (uObj.has("lockedTargetName") && !uObj.isNull("lockedTargetName")) uObj.getString("lockedTargetName") else null
                        )
                        activeUnits.add(unit)
                    }

                    // Sync hangar status
                    activeUnits.forEach { u ->
                        val base = structures.find { it.id == u.homeBaseId }
                        val hj = base?.hangarJets?.find { it.id == u.id }
                        if (hj != null) {
                            hj.status = "In Flight"
                        }
                    }
                }

                recalculateIncome()
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    fun applyServerSync(srvPlayer: Player, srvStructures: List<Structure>) {
        player.id = srvPlayer.id
        player.wealth = srvPlayer.wealth
        player.incomePerHour = srvPlayer.incomePerHour
        player.hp = srvPlayer.hp
        if (srvStructures.isNotEmpty()) {
            srvStructures.forEach { srvS ->
                if (srvS.hangarJets.isEmpty()) {
                    val local = structures.find { it.id == srvS.id }
                    if (local != null && local.hangarJets.isNotEmpty()) {
                        srvS.hangarJets.addAll(local.hangarJets)
                    }
                }
            }
            structures.clear()
            structures.addAll(srvStructures)
        }
        recalculateIncome()
        saveLocalWorld()
        listeners.forEach {
            it.onPlayerUpdated(player)
            it.onStructuresUpdated(structures)
        }
    }

    fun addListener(listener: StateListener) {
        listeners.add(listener)
        listener.onPlayerUpdated(player)
        listener.onStructuresUpdated(structures)
        listener.onUnitsUpdated(activeUnits)
    }

    fun removeListener(listener: StateListener) {
        listeners.remove(listener)
    }

    private fun loadCatalogsFromAssets() {
        try {
            val configStream = context.assets.open("config.json")
            val configJson = JSONObject(InputStreamReader(configStream).readText())

            val settlements = configJson.getJSONObject("settlementChain")
            val keys = settlements.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = settlements.getJSONObject(key)
                catalogBuildings[key] = CatalogBuilding(
                    type = key,
                    name = obj.getString("name"),
                    category = "settlement",
                    radiusMeters = obj.getDouble("radiusMeters"),
                    radiusType = "influence",
                    incomePerHour = obj.getInt("incomePerHour"),
                    hp = obj.getInt("hp"),
                    buildCost = obj.getInt("buildCost"),
                    description = "Settlement expanding territory and income.",
                    nextLevelType = if (obj.has("nextLevel") && !obj.isNull("nextLevel")) obj.getString("nextLevel") else null,
                    upgradeCost = obj.optInt("upgradeCost", 0),
                    upgradeDurationSeconds = obj.optInt("upgradeDurationSeconds", 0)
                )
            }

            val others = configJson.getJSONObject("otherBuildings")
            val otherKeys = others.keys()
            while (otherKeys.hasNext()) {
                val key = otherKeys.next()
                val obj = others.getJSONObject(key)
                catalogBuildings[key] = CatalogBuilding(
                    type = key,
                    name = obj.getString("name"),
                    category = obj.getString("category"),
                    radiusMeters = obj.getDouble("radiusMeters"),
                    radiusType = obj.getString("radiusType"),
                    incomePerHour = obj.getInt("incomePerHour"),
                    hp = obj.getInt("hp"),
                    buildCost = obj.getInt("buildCost"),
                    description = obj.optString("description", "")
                )
            }

            val catalogStream = context.assets.open("catalogs.json")
            val catalogJson = JSONObject(InputStreamReader(catalogStream).readText())

            val jetsArray = catalogJson.getJSONArray("jets")
            for (i in 0 until jetsArray.length()) {
                val j = jetsArray.getJSONObject(i)
                jetCatalog.add(
                    JetItem(
                        id = j.getString("id"),
                        name = j.getString("name"),
                        role = j.getString("role"),
                        speedMps = j.getInt("speedMps"),
                        rangeMeters = j.getInt("rangeMeters"),
                        damage = j.getInt("damage"),
                        hp = j.getInt("hp"),
                        cost = j.getInt("cost"),
                        weapons = when (j.getString("id")) {
                            "jet_a10" -> "4x GBU-12 Guided Bombs • 30mm GAU-8 Cannon"
                            "jet_f15", "jet_su27" -> "4x AIM-120 AMRAAM • 2x GBU-12"
                            else -> "2x GBU-12 Laser-Guided • 2x AIM-120 AMRAAM"
                        },
                        description = j.getString("description")
                    )
                )
            }

            val intArr = catalogJson.getJSONArray("interceptors")
            for (i in 0 until intArr.length()) {
                val intObj = intArr.getJSONObject(i)
                interceptorCatalog.add(
                    InterceptorItem(
                        id = intObj.getString("id"),
                        name = intObj.getString("name"),
                        rangeMeters = intObj.getInt("rangeMeters"),
                        hitChance = intObj.getDouble("hitChance"),
                        cost = intObj.getInt("cost"),
                        reloadSeconds = intObj.getInt("reloadSeconds"),
                        description = intObj.getString("description")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initializeStarterBase() {
        if (structures.isEmpty()) {
            val starterHq = Structure(
                id = "hq_" + UUID.randomUUID().toString().take(8),
                ownerId = player.id,
                team = player.team,
                teamColor = player.teamColor,
                type = "town_hall",
                name = "Command Citadel (HQ)",
                level = 1,
                position = player.position,
                hp = 1000,
                maxHp = 1000,
                radiusMeters = 500.0,
                radiusType = "territory",
                online = true
            )
            structures.add(starterHq)
            recalculateIncome()
        }
    }

    fun onFirstLocationFix(pos: GeoPoint) {
        if (!hasInitialGpsFixed) {
            hasInitialGpsFixed = true
            player.position = pos
            val hqIndex = structures.indexOfFirst { it.type == "town_hall" && it.ownerId == player.id }
            if (hqIndex != -1 && structures.size == 1) {
                structures[hqIndex] = structures[hqIndex].copy(position = pos)
            }
            listeners.forEach {
                it.onPlayerUpdated(player)
                it.onStructuresUpdated(structures)
            }
        }
    }

    private fun onGameTick() {
        var stateChanged = false

        // 1. Income accumulation
        incomeAccumulator += (player.incomePerHour.toDouble() / 3600.0)
        if (incomeAccumulator >= 1.0) {
            val gain = incomeAccumulator.toInt()
            player.wealth += gain
            incomeAccumulator -= gain
            stateChanged = true
        }

        // 2. Building upgrade timers
        val now = System.currentTimeMillis()
        structures.forEach { s ->
            if (s.upgradeFinishedAt != null && now >= s.upgradeFinishedAt!!) {
                s.upgradeFinishedAt = null
                completeUpgrade(s)
                stateChanged = true
            }
        }

        // 3. Flight Sortie Physics & Mechanics
        if (activeUnits.isNotEmpty()) {
            val iterator = activeUnits.iterator()
            var unitsChanged = false

            while (iterator.hasNext()) {
                val u = iterator.next()
                unitsChanged = true

                // Fuel consumption: Subsonic (0.35%/s), Supersonic (1.10%/s)
                val burnRate = if (u.speedMode == "supersonic") 1.10 else 0.35
                u.fuelPercent = (u.fuelPercent - burnRate).coerceAtLeast(0.0)

                // Bingo Fuel check (<= 20%): triggers emergency RTB
                if (u.fuelPercent <= 20.0 && u.order != "rtb") {
                    u.order = "rtb"
                    listeners.forEach { it.onAlert("BINGO FUEL!", "${u.name} reached 20% fuel. Returning to base immediately!") }
                }

                // If fuel completely empty (0%), jet is lost
                if (u.fuelPercent <= 0.0) {
                    iterator.remove()
                    recoverOrDestroyHangarJet(u.homeBaseId, u.id, destroyed = true)
                    listeners.forEach { it.onAlert("AIRCRAFT LOST", "${u.name} ran out of fuel and crashed!") }
                    continue
                }

                val homeBase = structures.find { it.id == u.homeBaseId }
                val homePos = homeBase?.position ?: u.originPosition

                // Movement resolution
                val dest = if (u.order == "rtb") homePos else u.targetPosition
                val distRemaining = GeoMath.calculateDistanceMeters(u.position, dest)

                if (u.order == "patrol") {
                    // Realistic 2.5km Tactical Circular Orbit Patrol with continuous 360-degree scan
                    val orbitRadiusMeters = 2500.0
                    val rLat = orbitRadiusMeters / 111139.0
                    val rLng = orbitRadiusMeters / (111139.0 * cos(Math.toRadians(dest.lat)).coerceAtLeast(0.01))

                    if (distRemaining > 2800.0) {
                        // Fly smoothly to perimeter of the patrol orbit
                        advancePositionTowards(u, dest, distRemaining)
                        u.patrolAngle = Math.atan2((u.position.lng - dest.lng) / rLng, (u.position.lat - dest.lat) / rLat)
                    } else {
                        // Orbit continuously around patrol center
                        val prevLat = u.position.lat
                        val prevLng = u.position.lng

                        // Angular step based on actual flight velocity: deltaTheta = (v * dt) / R
                        val deltaTheta = (u.speedMps / orbitRadiusMeters).coerceIn(0.04, 0.22)
                        u.patrolAngle = (u.patrolAngle + deltaTheta) % (2.0 * Math.PI)

                        val orbLat = dest.lat + rLat * cos(u.patrolAngle)
                        val orbLng = dest.lng + rLng * sin(u.patrolAngle)
                        u.position = GeoPoint(orbLat, orbLng)

                        // Tangential heading continuously rotating 360° across all 4 directions (N, E, S, W)
                        val dLat = orbLat - prevLat
                        val dLng = orbLng - prevLng
                        val angleDeg = Math.toDegrees(Math.atan2(dLng, dLat))
                        u.heading = (angleDeg + 360.0) % 360.0
                    }
                } else if (u.order == "strike") {
                    if (distRemaining < 120.0) {
                        // Delivered strike!
                        val targetStruct = if (u.targetStructureId != null) structures.find { it.id == u.targetStructureId } else null
                        val targetName = targetStruct?.name ?: "Ground Installation"
                        val strikeMslId = "strike_" + UUID.randomUUID().toString().take(6)
                        listeners.forEach {
                            it.onMissileFired(
                                missileId = strikeMslId,
                                fromLat = u.position.lat,
                                fromLng = u.position.lng,
                                toLat = dest.lat,
                                toLng = dest.lng,
                                missileType = "air_to_ground",
                                targetUnitId = null,
                                targetName = targetName,
                                speedMach = 2.8,
                                hitProbability = 0.95
                            )
                        }
                        if (targetStruct != null) {
                            targetStruct.hp = (targetStruct.hp - u.damage).coerceAtLeast(0)
                            if (targetStruct.hp <= 0) {
                                structures.remove(targetStruct)
                                recalculateIncome()
                                listeners.forEach { it.onAlert("TARGET DESTROYED!", "${targetStruct.name} neutralized by strike!") }
                            } else {
                                listeners.forEach { it.onAlert("DIRECT HIT!", "Payload hit ${targetStruct.name} (-${u.damage} HP)!") }
                            }
                        } else {
                            listeners.forEach { it.onAlert("Strike Delivered", "${u.name} hit target coordinates!") }
                        }
                        u.order = "rtb"
                    } else {
                        advancePositionTowards(u, dest, distRemaining)
                    }
                } else if (u.order == "rtb") {
                    if (distRemaining < 80.0) {
                        // Landed safely
                        iterator.remove()
                        recoverOrDestroyHangarJet(u.homeBaseId, u.id, destroyed = false)
                        listeners.forEach { it.onAlert("AIRCRAFT RECOVERED", "${u.name} landed safely, refueled and rearmed.") }
                        continue
                    } else {
                        advancePositionTowards(u, dest, distRemaining)
                    }
                }

                // SAM Defense Interception Check (100km Theater Defense Umbrella)
                val samBatteries = structures.filter { it.type == "sam" && it.online && it.samMunitions > 0 }
                val samDefenseRange = 100000.0 // 100 km theater radius
                for (sam in samBatteries) {
                    if (u.team != player.team) {
                        val distToSam = GeoMath.calculateDistanceMeters(u.position, sam.position)
                        if (distToSam <= samDefenseRange) {
                            val lastFired = samCooldownMap[sam.id] ?: 0L
                            if (now - lastFired > 12000L) {
                                samCooldownMap[sam.id] = now
                                sam.samMunitions -= 1
                                val samMslId = "sam_" + UUID.randomUUID().toString().take(6)
                                listeners.forEach {
                                    it.onMissileFired(
                                        missileId = samMslId,
                                        fromLat = sam.position.lat,
                                        fromLng = sam.position.lng,
                                        toLat = u.position.lat,
                                        toLng = u.position.lng,
                                        missileType = "sam_interceptor",
                                        targetUnitId = u.id,
                                        targetName = "${u.ownerCallsign} • ${u.name}",
                                        speedMach = 4.8,
                                        hitProbability = 1.0
                                    )
                                    it.onAlert("SAM AIR DEFENSE!", "PATRIOT Battery locked and fired PAC-3 interceptor at ${u.name} (Range: ${(distToSam / 1000).toInt()} km)!")
                                }
                                break
                            }
                        }
                    }
                }
            }

            if (unitsChanged) {
                listeners.forEach { it.onUnitsUpdated(activeUnits) }
                tickCounter++
                if (tickCounter % 5 == 0) {
                    saveLocalWorld()
                }
            }
        }

        if (stateChanged) {
            listeners.forEach { it.onPlayerUpdated(player) }
            listeners.forEach { it.onStructuresUpdated(structures) }
        }
    }

    private fun advancePositionTowards(u: UnitItem, dest: GeoPoint, distRemaining: Double) {
        val speedMps = if (u.speedMode == "supersonic") 520.0 else 220.0
        val stepRatio = (speedMps / distRemaining).coerceAtMost(0.35)
        val dLat = dest.lat - u.position.lat
        val dLng = dest.lng - u.position.lng
        val newLat = u.position.lat + dLat * stepRatio
        val newLng = u.position.lng + dLng * stepRatio
        u.position = GeoPoint(newLat, newLng)
        val angleDeg = Math.toDegrees(Math.atan2(dLng, dLat))
        u.heading = (angleDeg + 360.0) % 360.0
    }

    private fun recoverOrDestroyHangarJet(homeBaseId: String, unitId: String, destroyed: Boolean) {
        val base = structures.find { it.id == homeBaseId } ?: return
        val jetIndex = base.hangarJets.indexOfFirst { it.id == unitId }
        if (jetIndex != -1) {
            if (destroyed) {
                base.hangarJets.removeAt(jetIndex)
            } else {
                base.hangarJets[jetIndex].status = "Ready in Hangar"
            }
        }
    }

    // --- HANGAR & SORTIE ACTIONS ---

    fun buyJetForAirbase(airbaseId: String, jetCatalogId: String): Pair<Boolean, String> {
        val base = structures.find { it.id == airbaseId } ?: return Pair(false, "Airbase not found.")
        val jet = jetCatalog.find { it.id == jetCatalogId } ?: return Pair(false, "Unknown jet type.")

        if (base.hangarJets.size >= base.maxHangarSlots()) {
            return Pair(false, "Hangar full! (${base.hangarJets.size}/${base.maxHangarSlots()} slots). Upgrade Airbase to expand slots.")
        }

        if (player.wealth < jet.cost) {
            return Pair(false, "Insufficient funds ($${player.wealth} < $${jet.cost}).")
        }

        player.wealth -= jet.cost
        val hangarJet = HangarJetItem(
            id = "jet_" + UUID.randomUUID().toString().take(6),
            catalogId = jet.id,
            name = jet.name,
            role = jet.role,
            hp = jet.hp,
            maxHp = jet.hp,
            status = "Ready in Hangar"
        )
        base.hangarJets.add(hangarJet)

        listeners.forEach {
            it.onPlayerUpdated(player)
            it.onStructuresUpdated(structures)
            it.onAlert("Jet Procured", "${jet.name} delivered to Hangar (Slot ${base.hangarJets.size}/${base.maxHangarSlots()}).")
        }
        saveLocalWorld()
        return Pair(true, "${jet.name} added to Hangar.")
    }

    fun launchSortieFromHangar(
        airbaseId: String,
        hangarJetId: String,
        targetPos: GeoPoint,
        targetStructureId: String?,
        order: String = "strike"
    ): Pair<Boolean, String> {
        val base = structures.find { it.id == airbaseId } ?: return Pair(false, "Airbase not found.")
        val jet = base.hangarJets.find { it.id == hangarJetId } ?: return Pair(false, "Jet not found in Hangar.")

        if (jet.status != "Ready in Hangar") {
            return Pair(false, "Jet is currently ${jet.status}.")
        }

        val cat = jetCatalog.find { it.id == jet.catalogId }
        val distToTarget = GeoMath.calculateDistanceMeters(base.position, targetPos)
        val maxRange = cat?.rangeMeters ?: 80000

        if (distToTarget > maxRange) {
            return Pair(false, "Target beyond combat radius (${(distToTarget / 1000).toInt()}km > ${(maxRange / 1000)}km).")
        }

        val callsignWithTag = if (player.allianceTag.isNotEmpty()) {
            "${player.allianceTag} ${player.callsign}"
        } else {
            player.callsign
        }

        jet.status = "In Flight"
        val sortie = UnitItem(
            id = jet.id,
            ownerId = player.id,
            ownerCallsign = callsignWithTag,
            team = player.team,
            teamColor = player.teamColor,
            catalogId = jet.catalogId,
            name = jet.name,
            position = base.position,
            originPosition = base.position,
            targetPosition = targetPos,
            targetStructureId = targetStructureId,
            hp = jet.hp,
            maxHp = jet.maxHp,
            order = order,
            speedMode = "subsonic",
            speedMps = cat?.speedMps?.toDouble() ?: 220.0,
            rangeMeters = maxRange,
            fuelPercent = 100.0,
            weaponsLoadout = cat?.weapons ?: "2x GBU-12 Guided Bombs • 2x AIM-120",
            damage = cat?.damage ?: 60,
            homeBaseId = base.id
        )

        activeUnits.add(sortie)
        addCombatReport("combat", "Sortie Scrambled", "${jet.name} scrambled from Hangar on ${order.uppercase()} vector.", "info")
        listeners.forEach {
            it.onStructuresUpdated(structures)
            it.onUnitsUpdated(activeUnits)
            it.onAlert("Sortie Scrambled!", "${jet.name} launched on ${order.uppercase()} mission!")
        }
        return Pair(true, "Sortie scrambled.")
    }

    fun setUnitSpeedMode(unitId: String, mode: String): Boolean {
        val u = activeUnits.find { it.id == unitId } ?: return false
        u.speedMode = mode
        u.speedMps = if (mode == "supersonic") 520.0 else 220.0
        listeners.forEach {
            it.onUnitsUpdated(activeUnits)
            it.onAlert("Speed Mode Changed", "${u.name} set to ${mode.uppercase()} (${if (mode == "supersonic") "Afterburner • 3x Fuel Burn" else "Cruising Speed"}).")
        }
        return true
    }

    fun abortUnitMissionRtb(unitId: String): Boolean {
        val u = activeUnits.find { it.id == unitId } ?: return false
        u.order = "rtb"
        listeners.forEach {
            it.onUnitsUpdated(activeUnits)
            it.onAlert("Mission Aborted", "${u.name} aborted mission. Heading back to base.")
        }
        return true
    }

    fun retargetUnitSortie(unitId: String, newTarget: GeoPoint, targetStructId: String?): Boolean {
        val u = activeUnits.find { it.id == unitId } ?: return false
        u.targetPosition = newTarget
        u.targetStructureId = targetStructId
        u.order = "strike"
        listeners.forEach {
            it.onUnitsUpdated(activeUnits)
            it.onAlert("Target Updated", "${u.name} vector updated to new target!")
        }
        return true
    }

    fun setUnitPatrol(unitId: String, patrolPos: GeoPoint): Boolean {
        val u = activeUnits.find { it.id == unitId } ?: return false
        u.targetPosition = patrolPos
        u.order = "patrol"
        listeners.forEach {
            it.onUnitsUpdated(activeUnits)
            it.onAlert("Patrol Assigned", "${u.name} assigned to Air Patrol.")
        }
        return true
    }

    fun buySamMunitions(samId: String): Pair<Boolean, String> {
        val sam = structures.find { it.id == samId } ?: return Pair(false, "SAM site not found.")
        val cost = 300
        if (player.wealth < cost) {
            return Pair(false, "Insufficient funds ($$cost).")
        }
        player.wealth -= cost
        sam.samMunitions = (sam.samMunitions + 4).coerceAtMost(sam.maxSamSlots())
        listeners.forEach {
            it.onPlayerUpdated(player)
            it.onStructuresUpdated(structures)
            it.onAlert("SAM Re-Armed", "Loaded interceptor missiles into battery (${sam.samMunitions}/${sam.maxSamSlots()}).")
        }
        saveLocalWorld()
        return Pair(true, "+4 Interceptors armed.")
    }

    fun armSamSlot(samId: String): Pair<Boolean, String> {
        val sam = structures.find { it.id == samId } ?: return Pair(false, "SAM battery not found.")
        if (sam.samMunitions >= sam.maxSamSlots()) {
            return Pair(false, "SAM battery silos full (${sam.samMunitions}/${sam.maxSamSlots()}).")
        }
        val cost = 150
        if (player.wealth < cost) {
            return Pair(false, "Insufficient funds ($$cost required).")
        }
        player.wealth -= cost
        sam.samMunitions += 1
        listeners.forEach {
            it.onPlayerUpdated(player)
            it.onStructuresUpdated(structures)
            it.onAlert("Silo Armed", "Loaded 1x Interceptor into Silo #${sam.samMunitions}.")
        }
        saveLocalWorld()
        return Pair(true, "Missile armed.")
    }

    fun setUnitRoe(unitId: String, roe: String): Boolean {
        val u = activeUnits.find { it.id == unitId } ?: return false
        u.roe = roe
        val roeLabel = when (roe) {
            "aggressive" -> "AGGRESSIVE (Seek & Destroy)"
            "fire_at_will" -> "FIRE AT WILL (Engage targets in cone)"
            else -> "PEACEFUL (Hold Fire / Recon)"
        }
        listeners.forEach {
            it.onUnitsUpdated(activeUnits)
            it.onAlert("ROE Updated", "${u.name} set to $roeLabel")
        }
        return true
    }

    fun setUnitRadarCone(unitId: String, angle: Int): Boolean {
        val u = activeUnits.find { it.id == unitId } ?: return false
        u.radarConeAngle = angle
        val rangeKm = when {
            angle <= 30 -> 300
            angle >= 90 -> 80
            else -> 180
        }
        listeners.forEach {
            it.onUnitsUpdated(activeUnits)
            it.onAlert("Radar Aperture", "${u.name} radar aperture set to ${angle}° sector ($rangeKm km scan range)")
        }
        return true
    }

    fun setJetLockedTarget(unitId: String, targetUnitId: String?): Boolean {
        val u = activeUnits.find { it.id == unitId } ?: return false
        u.lockedTargetUnitId = targetUnitId
        val target = if (targetUnitId != null) activeUnits.find { it.id == targetUnitId } else null
        u.lockedTargetName = target?.let { "${it.ownerCallsign} • ${it.name}" }
        listeners.forEach {
            it.onUnitsUpdated(activeUnits)
            if (target != null) {
                it.onAlert("RADAR TRACK LOCKED", "${u.name} locked on ${target.name}!")
            } else {
                it.onAlert("TARGET UNLOCKED", "${u.name} unlocked radar track.")
            }
        }
        saveLocalWorld()
        return true
    }

    fun fireManualMissile(unitId: String): Pair<Boolean, String> {
        val u = activeUnits.find { it.id == unitId } ?: return Pair(false, "Unit not found.")

        // 100km Max Engagement Radius for Air-to-Air Interceptor
        val maxRangeM = 100000.0

        // 1. Prioritize explicitly locked target
        var targetUnit = if (!u.lockedTargetUnitId.isNullOrEmpty()) {
            activeUnits.find { it.id == u.lockedTargetUnitId }
        } else null

        // 2. If locked target not found, auto-acquire closest hostile aircraft within 100km
        if (targetUnit == null) {
            targetUnit = activeUnits.filter { it.id != u.id && it.team != u.team }
                .minByOrNull { GeoMath.calculateDistanceMeters(u.position, it.position) }
                ?.takeIf { GeoMath.calculateDistanceMeters(u.position, it.position) <= maxRangeM }
        }

        val destPos: GeoPoint
        val targetName: String
        val targetId: String?

        if (targetUnit != null) {
            destPos = targetUnit.position
            targetName = "${targetUnit.ownerCallsign} • ${targetUnit.name}"
            targetId = targetUnit.id
        } else {
            // Forward BVR projection along current heading for full 100km engagement envelope!
            val headingRad = Math.toRadians(u.heading)
            val dLat = (maxRangeM / 111139.0) * cos(headingRad)
            val dLng = (maxRangeM / (111139.0 * cos(Math.toRadians(u.position.lat)).coerceAtLeast(0.01))) * sin(headingRad)
            destPos = GeoPoint(u.position.lat + dLat, u.position.lng + dLng)
            targetName = "Airspace Intercept Vector (100km Forward)"
            targetId = null
        }

        val distMeters = GeoMath.calculateDistanceMeters(u.position, destPos)
        val missileId = "msl_" + UUID.randomUUID().toString().take(6)

        listeners.forEach {
            it.onMissileFired(
                missileId = missileId,
                fromLat = u.position.lat,
                fromLng = u.position.lng,
                toLat = destPos.lat,
                toLng = destPos.lng,
                missileType = "air_to_air",
                targetUnitId = targetId,
                targetName = targetName,
                speedMach = 4.8,
                hitProbability = 1.0
            )
            it.onAlert("FOX TWO!", "${u.name} launched AIM-120D AMRAAM at $targetName (${(distMeters / 1000).toInt()} km)!")
        }
        return Pair(true, "Missile launched ($targetName locked)")
    }

    fun fireSamAtTarget(targetUnitId: String): Pair<Boolean, String> {
        val target = activeUnits.find { it.id == targetUnitId } ?: return Pair(false, "Target not found.")
        val sam = structures.find { it.type == "sam" && it.online && it.samMunitions > 0 }
            ?: return Pair(false, "No active SAM batteries with interceptor munitions available.")

        val distToSam = GeoMath.calculateDistanceMeters(target.position, sam.position)
        if (distToSam > 100000.0) {
            return Pair(false, "Target beyond 100km theater defense envelope (${(distToSam / 1000).toInt()} km).")
        }

        sam.samMunitions -= 1
        samCooldownMap[sam.id] = System.currentTimeMillis()
        val samMslId = "sam_" + UUID.randomUUID().toString().take(6)
        listeners.forEach {
            it.onPlayerUpdated(player)
            it.onStructuresUpdated(structures)
            it.onMissileFired(
                missileId = samMslId,
                fromLat = sam.position.lat,
                fromLng = sam.position.lng,
                toLat = target.position.lat,
                toLng = target.position.lng,
                missileType = "sam_interceptor",
                targetUnitId = target.id,
                targetName = "${target.ownerCallsign} • ${target.name}",
                speedMach = 4.8,
                hitProbability = 1.0
            )
            it.onAlert("MANUAL SAM LAUNCH!", "Launched PATRIOT PAC-3 interceptor at ${target.name} (Range: ${(distToSam / 1000).toInt()} km)!")
        }
        saveLocalWorld()
        return Pair(true, "Interceptor launched at ${target.name}!")
    }

    fun fireFox2AtTarget(firerJetId: String, targetUnitId: String): Pair<Boolean, String> {
        val firer = activeUnits.find { it.id == firerJetId } ?: return Pair(false, "Friendly fighter not found.")
        val target = activeUnits.find { it.id == targetUnitId } ?: return Pair(false, "Target not found.")

        val distMeters = GeoMath.calculateDistanceMeters(firer.position, target.position)
        if (distMeters > 100000.0) {
            return Pair(false, "Target beyond maximum 100km engagement envelope (${(distMeters / 1000).toInt()} km).")
        }

        val missileId = "msl_" + UUID.randomUUID().toString().take(6)
        listeners.forEach {
            it.onMissileFired(
                missileId = missileId,
                fromLat = firer.position.lat,
                fromLng = firer.position.lng,
                toLat = target.position.lat,
                toLng = target.position.lng,
                missileType = "air_to_air",
                targetUnitId = target.id,
                targetName = "${target.ownerCallsign} • ${target.name}",
                speedMach = 4.8,
                hitProbability = 1.0
            )
            it.onAlert("FOX TWO!", "${firer.name} launched AIM-120D at ${target.name} (${(distMeters / 1000).toInt()} km)!")
        }
        return Pair(true, "AIM-120D AMRAAM launched!")
    }

    fun fireCruiseMissile(siloId: String, targetPos: GeoPoint): Pair<Boolean, String> {
        val silo = structures.find { it.id == siloId && it.type == "missile_silo" && it.online }
            ?: return Pair(false, "Missile launcher facility not found or offline.")

        if (silo.missileMunitions <= 0) {
            return Pair(false, "Silo depleted! Purchase BrahMos-ER Cruise Missile reload.")
        }

        val distM = GeoMath.calculateDistanceMeters(silo.position, targetPos)
        if (distM > 200000.0) {
            return Pair(false, "Target coordinates beyond 200km strategic range (${(distM / 1000).toInt()} km).")
        }

        silo.missileMunitions -= 1
        val mslId = "cruise_" + UUID.randomUUID().toString().take(6)
        listeners.forEach {
            it.onStructuresUpdated(structures)
            it.onMissileFired(
                missileId = mslId,
                fromLat = silo.position.lat,
                fromLng = silo.position.lng,
                toLat = targetPos.lat,
                toLng = targetPos.lng,
                missileType = "strategic_cruise",
                targetUnitId = null,
                targetName = "Strategic Grid Target [${(distM / 1000).toInt()} km]",
                speedMach = 0.85,
                hitProbability = 0.98
            )
            it.onAlert("CRUISE MISSILE LAUNCHED", "Heavy BrahMos-ER Cruise Missile launched from Silo towards target!")
        }
        saveLocalWorld()
        return Pair(true, "BrahMos-ER Cruise Missile launched!")
    }

    fun reloadMissileSilo(siloId: String): Pair<Boolean, String> {
        val silo = structures.find { it.id == siloId && it.type == "missile_silo" }
            ?: return Pair(false, "Missile launcher facility not found.")

        val cost = 850
        if (player.wealth < cost) {
            return Pair(false, "Insufficient funds ($$cost needed for BrahMos-ER missile reload).")
        }
        if (silo.missileMunitions >= silo.maxMissileSlots()) {
            return Pair(false, "Missile Silo already at maximum capacity (${silo.maxMissileSlots()}).")
        }

        player.wealth -= cost
        silo.missileMunitions = (silo.missileMunitions + 1).coerceAtMost(silo.maxMissileSlots())
        listeners.forEach {
            it.onPlayerUpdated(player)
            it.onStructuresUpdated(structures)
            it.onAlert("SILO RELOADED", "Loaded 1x BrahMos-ER Cruise Missile into Silo (${silo.missileMunitions}/${silo.maxMissileSlots()}).")
        }
        saveLocalWorld()
        return Pair(true, "Cruise missile reloaded.")
    }

    fun destroyUnitInCombat(unitId: String, unitName: String) {
        val u = activeUnits.find { it.id == unitId } ?: return
        activeUnits.remove(u)
        recoverOrDestroyHangarJet(u.homeBaseId, u.id, destroyed = true)
        addCombatReport("combat", "AIRCRAFT DESTROYED", "$unitName shot down by missile interception!", "danger")
        listeners.forEach {
            it.onUnitsUpdated(activeUnits)
            it.onAlert("SPLASH ONE!", "$unitName was neutralized!")
        }
        saveLocalWorld()
    }

    fun spawnOpforBogey(): UnitItem {
        val center = player.position
        // Realistic long-range radar spawn: ~60km away bearing 045°
        val bogeyPos = GeoPoint(center.lat + 0.38, center.lng + 0.42)
        val bogeyTarget = GeoPoint(center.lat, center.lng)
        val bogey = UnitItem(
            id = "bogey_" + UUID.randomUUID().toString().take(6),
            ownerId = "opfor_commander",
            ownerCallsign = "[OPFOR] Col. Volkov",
            team = "red",
            teamColor = "#FF3B30",
            catalogId = "jet_su27",
            name = "Hostile Su-27 Flanker",
            position = bogeyPos,
            originPosition = bogeyPos,
            targetPosition = bogeyTarget,
            hp = 180,
            maxHp = 180,
            order = "patrol",
            speedMode = "subsonic",
            speedMps = 240.0,
            rangeMeters = 100000,
            fuelPercent = 90.0,
            weaponsLoadout = "4x R-77 BVR • 2x R-73 Archer",
            damage = 65,
            homeBaseId = "opfor_base"
        )
        activeUnits.add(bogey)
        listeners.forEach {
            it.onUnitsUpdated(activeUnits)
            it.onAlert("BOGEY IN AIRSPACE!", "Hostile Su-27 Flanker tracked bearing 045°!")
        }
        saveLocalWorld()
        return bogey
    }

    // --- STANDARD BUILDING ACTIONS ---

    fun buildStructure(type: String, targetPos: GeoPoint): Pair<Boolean, String> {
        val cat = catalogBuildings[type] ?: return Pair(false, "Unknown building type.")

        if (player.wealth < cat.buildCost) {
            return Pair(false, "Insufficient funds! Need $${cat.buildCost}, you have $${player.wealth}.")
        }

        val distFromPlayer = GeoMath.calculateDistanceMeters(player.position, targetPos)
        if (distFromPlayer > 800.0) {
            return Pair(false, "Location too far from Commander (${distFromPlayer.toInt()}m > 800m max). Tap '🎯 At GPS' to build beside you.")
        }

        if (!GeoMath.satisfiesSeparation(targetPos, structures, 20.0)) {
            return Pair(false, "Too close to an existing structure (minimum 20m separation required).")
        }

        if (type != "town_hall") {
            val inside = GeoMath.isInsideTerritory(targetPos, structures, player.id)
            if (!inside) {
                val hq = structures.find { it.type == "town_hall" && it.ownerId == player.id }
                if (hq != null) {
                    val distToHq = GeoMath.calculateDistanceMeters(targetPos, hq.position)
                    if (distToHq > 1000.0 && structures.size == 1) {
                        val idx = structures.indexOf(hq)
                        structures[idx] = hq.copy(position = player.position)
                    } else {
                        return Pair(false, "Must be placed inside your territory radius (within 500m of your Command Citadel).")
                    }
                }
            }
        }

        player.wealth -= cat.buildCost
        val newStructure = Structure(
            id = "str_" + UUID.randomUUID().toString().take(8),
            ownerId = player.id,
            team = player.team,
            teamColor = player.teamColor,
            type = type,
            name = cat.name,
            level = 1,
            position = targetPos,
            hp = cat.hp,
            maxHp = cat.hp,
            radiusMeters = cat.radiusMeters,
            radiusType = cat.radiusType,
            online = true
        )
        structures.add(newStructure)
        recalculateIncome()
        addCombatReport("economy", "Structure Deployed", "${cat.name} constructed (+$$${cat.incomePerHour}/hr).", "info")

        listeners.forEach {
            it.onPlayerUpdated(player)
            it.onStructuresUpdated(structures)
            it.onAlert("Deployment Confirmed", "${cat.name} constructed successfully!")
        }
        saveLocalWorld()

        return Pair(true, "Deployment successful.")
    }

    fun upgradeStructure(structureId: String): Pair<Boolean, String> {
        val s = structures.find { it.id == structureId } ?: return Pair(false, "Structure not found.")
        val cat = catalogBuildings[s.type] ?: return Pair(false, "No upgrade available.")

        val nextType = cat.nextLevelType
        if (nextType == null || cat.upgradeCost <= 0) {
            return Pair(false, "Structure is already at maximum level.")
        }

        if (s.upgradeFinishedAt != null) {
            return Pair(false, "Upgrade already in progress.")
        }

        if (player.wealth < cat.upgradeCost) {
            return Pair(false, "Insufficient funds ($${player.wealth} < $${cat.upgradeCost}).")
        }

        player.wealth -= cat.upgradeCost
        val durationSeconds = if (cat.upgradeDurationSeconds > 0) cat.upgradeDurationSeconds else 30
        s.upgradeFinishedAt = System.currentTimeMillis() + (durationSeconds * 1000L)

        listeners.forEach {
            it.onPlayerUpdated(player)
            it.onStructuresUpdated(structures)
            it.onAlert("Upgrade Initiated", "Upgrading ${s.name} (Ready in ${durationSeconds}s)")
        }

        return Pair(true, "Upgrade started.")
    }

    private fun completeUpgrade(s: Structure) {
        val cat = catalogBuildings[s.type] ?: return
        val nextType = cat.nextLevelType ?: return
        val nextCat = catalogBuildings[nextType] ?: return

        s.type = nextType
        s.name = nextCat.name
        s.level += 1
        s.maxHp = nextCat.hp
        s.hp = nextCat.hp
        s.radiusMeters = nextCat.radiusMeters
        s.radiusType = nextCat.radiusType

        recalculateIncome()
        addCombatReport("economy", "Upgrade Completed", "${s.name} is now Level ${s.level} (HP: ${s.maxHp})!", "info")
        listeners.forEach {
            it.onStructureUpgradeCompleted(s)
            it.onAlert("Upgrade Complete!", "${s.name} is now Level ${s.level}!")
        }
        saveLocalWorld()
    }

    fun repairStructure(structureId: String): Pair<Boolean, String> {
        val s = structures.find { it.id == structureId } ?: return Pair(false, "Structure not found.")
        if (s.hp >= s.maxHp) {
            return Pair(false, "Structure already at full HP.")
        }
        val repairCost = 50
        if (player.wealth < repairCost) {
            return Pair(false, "Insufficient funds for repair ($$repairCost).")
        }
        player.wealth -= repairCost
        s.hp = s.maxHp

        listeners.forEach {
            it.onPlayerUpdated(player)
            it.onStructuresUpdated(structures)
            it.onAlert("Repairs Complete", "${s.name} fully restored to ${s.maxHp} HP.")
        }
        saveLocalWorld()
        return Pair(true, "Repaired.")
    }

    fun sellStructure(structureId: String): Pair<Boolean, String> {
        val index = structures.indexOfFirst { it.id == structureId }
        if (index == -1) return Pair(false, "Structure not found.")
        val s = structures[index]
        if (s.type == "town_hall") {
            return Pair(false, "Cannot sell Command Citadel (HQ).")
        }

        val cat = catalogBuildings[s.type]
        val refund = ((cat?.buildCost ?: 300) * 0.5).toInt()
        player.wealth += refund
        structures.removeAt(index)
        recalculateIncome()

        listeners.forEach {
            it.onPlayerUpdated(player)
            it.onStructuresUpdated(structures)
            it.onAlert("Structure Sold", "${s.name} decommissioned. +$$refund refunded.")
        }
        saveLocalWorld()
        return Pair(true, "Sold for +$$refund.")
    }

    fun updatePlayerLocation(newPos: GeoPoint, accuracy: Double) {
        if (!hasInitialGpsFixed) {
            onFirstLocationFix(newPos)
        } else {
            player.position = newPos
            player.accuracy = accuracy
            listeners.forEach { it.onPlayerUpdated(player) }
        }
    }

    fun updateCommanderProfile(name: String, tag: String, colorHex: String) {
        player.callsign = name
        player.allianceTag = tag
        player.teamColor = colorHex
        listeners.forEach { it.onPlayerUpdated(player) }
    }

    private fun recalculateIncome() {
        var total = 0
        structures.forEach { s ->
            if (s.online) {
                val cat = catalogBuildings[s.type]
                total += cat?.incomePerHour ?: 0
            }
        }
        player.incomePerHour = total
        listeners.forEach { it.onPlayerUpdated(player) }
    }

    fun getStructuresJson(): String {
        val arr = JSONArray()
        structures.forEach { arr.put(it.toJsonObject()) }
        return arr.toString()
    }

    fun getUnitsJson(): String {
        val arr = JSONArray()
        activeUnits.forEach { arr.put(it.toJsonObject()) }
        return arr.toString()
    }

    // --- MILESTONE 4: ALLIANCES, TREATIES & COMBAT REPORTS ---

    private fun seedAlliancesAndReports() {
        alliances.clear()
        treaties.clear()
        player.allianceTag = ""
        player.allianceId = null
        player.allianceRole = "Member"

        combatReports.clear()
        combatReports.add(
            CombatReportItem(
                id = "rep_1",
                timestamp = System.currentTimeMillis() - 45000,
                category = "combat",
                urgencyTag = "🚨 WAR BULLETIN",
                title = "MASSIVE AIR INCURSION INTERCEPTED OVER SECTOR 4",
                detail = "PATRIOT-Alpha Air Defense Battery tracked and engaged supersonic hostile strike formation. 1x Bogey neutralized with zero collateral damage recorded in perimeter.",
                level = "danger",
                sector = "Sector 4 [Air Corridor]"
            )
        )
        combatReports.add(
            CombatReportItem(
                id = "rep_2",
                timestamp = System.currentTimeMillis() - 180000,
                category = "radar",
                urgencyTag = "📡 AIR RADAR FLASH",
                title = "UNIDENTIFIED SUPERSONIC TARGET DETECTED",
                detail = "Long-range phased array acquired high-altitude radar cross-section closing at Mach 1.6 bearing 035. Air defense grid elevated to Condition Amber.",
                level = "warn",
                sector = "Sector 7 [Radar Dome]"
            )
        )
        combatReports.add(
            CombatReportItem(
                id = "rep_3",
                timestamp = System.currentTimeMillis() - 600000,
                category = "economy",
                urgencyTag = "💰 TREASURY DISPATCH",
                title = "REGIONAL OIL & CITADEL REVENUE HARVESTED",
                detail = "Command Citadel automated fiscal ledgers recorded +$450 credits in resource royalties and regional trade duties.",
                level = "info",
                sector = "HQ Command Citadel"
            )
        )
        combatReports.add(
            CombatReportItem(
                id = "rep_4",
                timestamp = System.currentTimeMillis() - 1200000,
                category = "diplomacy",
                urgencyTag = "🌐 DIPLOMATIC WIRE",
                title = "FACTION CHARTERS OPENED GLOBALLY",
                detail = "Supreme Defense Command authorized commanders to charter sovereign alliances, appoint ministerial cabinets, and enforce border sovereignty.",
                level = "info",
                sector = "Global Command Nexus"
            )
        )

        // Seed hostile OPFOR bogey in airspace if none active
        if (activeUnits.none { it.team == "red" }) {
            spawnOpforBogey()
        }
    }

    fun addCombatReport(category: String, title: String, detail: String, level: String = "info", urgencyTag: String = "BREAKING ALERT", sector: String = "Tactical Grid") {
        val rep = CombatReportItem("rep_${System.currentTimeMillis()}", System.currentTimeMillis(), category, urgencyTag, title, detail, level, sector)
        combatReports.add(0, rep)
        if (combatReports.size > 50) {
            combatReports.removeAt(combatReports.size - 1)
        }
    }

    fun createAlliance(name: String, tag: String, motto: String, colorHex: String): Pair<Boolean, String> {
        val cleanTag = if (tag.startsWith("[")) tag.uppercase() else "[${tag.uppercase()}]"
        val leaderMember = AllianceMember(
            playerId = player.id,
            callsign = player.callsign,
            role = "Supreme Commander",
            netWorth = player.calculateNetWorth(structures),
            joinedAt = System.currentTimeMillis()
        )
        val newAlliance = AllianceItem(
            id = "all_" + UUID.randomUUID().toString().take(6),
            name = name,
            tag = cleanTag,
            leaderId = player.id,
            leaderCallsign = player.callsign,
            members = mutableListOf(leaderMember),
            totalWealth = player.calculateNetWorth(structures),
            territorySqKm = 1.0,
            taxRatePercent = 10,
            treasuryCredits = 5000,
            colorHex = colorHex,
            motto = motto.ifEmpty { "Forward to victory." },
            createdAt = System.currentTimeMillis()
        )
        alliances.add(0, newAlliance)
        player.allianceId = newAlliance.id
        player.allianceTag = cleanTag
        player.allianceRole = "Supreme Commander"
        addCombatReport("diplomacy", "ALLIANCE CHARTERED: $name $cleanTag", "Commander ${player.callsign} established sovereign alliance $name $cleanTag as Supreme Commander.", "info", "🌐 SOVEREIGN CHARTER", "Supreme Command")

        listeners.forEach {
            it.onPlayerUpdated(player)
            it.onAlert("Alliance Established", "Welcome to $name $cleanTag!")
        }
        return Pair(true, "Alliance $cleanTag created!")
    }

    fun joinAlliance(allianceId: String): Pair<Boolean, String> {
        val target = alliances.find { it.id == allianceId } ?: return Pair(false, "Alliance not found.")
        val newMember = AllianceMember(
            playerId = player.id,
            callsign = player.callsign,
            role = "Member",
            netWorth = player.calculateNetWorth(structures),
            joinedAt = System.currentTimeMillis()
        )
        target.members.add(newMember)
        player.allianceId = target.id
        player.allianceTag = target.tag
        player.allianceRole = "Member"
        addCombatReport("diplomacy", "ENLISTED: ${target.name} ${target.tag}", "Commander ${player.callsign} enlisted as combat Member in ${target.name} ${target.tag}.", "info", "🤝 ENLISTMENT CONFIRMED", "Alliance Headquarters")

        listeners.forEach {
            it.onPlayerUpdated(player)
            it.onAlert("Enlistment Confirmed", "Enlisted into ${target.name} ${target.tag}!")
        }
        return Pair(true, "Enlisted into ${target.tag}.")
    }

    fun leaveAlliance(): Pair<Boolean, String> {
        val currentAll = alliances.find { it.id == player.allianceId }
        val oldTag = player.allianceTag
        if (currentAll != null) {
            currentAll.members.removeAll { it.playerId == player.id }
            if (currentAll.members.isEmpty()) {
                alliances.remove(currentAll)
            } else if (currentAll.leaderId == player.id) {
                currentAll.members[0].role = "Supreme Commander"
                currentAll.leaderId = currentAll.members[0].playerId
                currentAll.leaderCallsign = currentAll.members[0].callsign
            }
        }
        player.allianceId = null
        player.allianceTag = ""
        player.allianceRole = "Member"
        addCombatReport("diplomacy", "FACTION DEPARTURE", "Commander ${player.callsign} departed $oldTag and returned to Independent Command.", "warn", "⚡ DISBAND / LEAVE", "Field Sector")

        listeners.forEach {
            it.onPlayerUpdated(player)
            it.onAlert("Alliance Departed", "Returned to Independent status.")
        }
        return Pair(true, "Departed alliance.")
    }

    fun setTaxRate(taxPercent: Int): Pair<Boolean, String> {
        val all = alliances.find { it.id == player.allianceId } ?: return Pair(false, "No active alliance.")
        if (player.allianceRole != "Supreme Commander" && player.allianceRole != "Financial Minister") {
            return Pair(false, "Only Supreme Commander or Financial Minister can adjust taxation.")
        }
        all.taxRatePercent = taxPercent.coerceIn(0, 30)
        addCombatReport("economy", "ALLIANCE TAX SET TO ${all.taxRatePercent}%", "Alliance fiscal rate adjusted to ${all.taxRatePercent}%.", "info", "💰 TREASURY FLASH", "Fiscal Ministry")
        listeners.forEach { it.onAlert("Tax Rate Updated", "Alliance taxation set to ${all.taxRatePercent}%") }
        return Pair(true, "Tax rate set to ${all.taxRatePercent}%.")
    }

    fun promoteMember(targetPlayerId: String, newRole: String): Pair<Boolean, String> {
        val all = alliances.find { it.id == player.allianceId } ?: return Pair(false, "No active alliance.")
        if (player.allianceRole != "Supreme Commander") {
            return Pair(false, "Only Supreme Commander can reassign cabinet posts.")
        }
        val member = all.members.find { it.playerId == targetPlayerId } ?: return Pair(false, "Member not found.")
        member.role = newRole
        if (targetPlayerId == player.id) {
            player.allianceRole = newRole
        }
        addCombatReport("diplomacy", "CABINET APPOINTMENT: $newRole", "Commander ${member.callsign} promoted to $newRole.", "info", "👑 PROMOTION ORDER", "Supreme Command")
        listeners.forEach { it.onAlert("Cabinet Assigned", "${member.callsign} is now $newRole.") }
        return Pair(true, "${member.callsign} appointed as $newRole.")
    }

    fun setTreatyStatus(targetAllianceId: String, status: String): Pair<Boolean, String> {
        val all = alliances.find { it.id == player.allianceId } ?: return Pair(false, "You must be in an alliance.")
        if (player.allianceRole != "Supreme Commander" && player.allianceRole != "Foreign Minister") {
            return Pair(false, "Only Supreme Commander or Foreign Minister can ratify treaties.")
        }
        val target = alliances.find { it.id == targetAllianceId } ?: return Pair(false, "Target alliance not found.")
        val existing = treaties.find { it.allianceIdB == targetAllianceId }
        if (existing != null) {
            existing.status = status
            existing.updatedAt = System.currentTimeMillis()
        } else {
            treaties.add(TreatyItem("try_${System.currentTimeMillis()}", all.id, targetAllianceId, target.name, target.tag, status))
        }

        val statusLabel = when (status) {
            "pact" -> "Mutual Defense Pact"
            "war" -> "State of War"
            else -> "Ceasefire / Neutrality"
        }
        val level = if (status == "war") "danger" else "info"
        val tagUrgency = if (status == "war") "🚨 WAR DECLARATION" else "🤝 DIPLOMATIC TREATY"
        addCombatReport("diplomacy", "TREATY RATIFIED: $statusLabel", "Diplomatic status with ${target.name} ${target.tag} set to $statusLabel.", level, tagUrgency, "Foreign Ministry")

        listeners.forEach {
            it.onAlert("Treaty Ratified", "Status with ${target.tag} updated to $statusLabel.")
        }
        return Pair(true, "Treaty updated.")
    }

    fun getLeaderboard(metric: String): List<LeaderboardItem> {
        val list = mutableListOf<LeaderboardItem>()
        if (metric == "territory") {
            alliances.sortedByDescending { it.territorySqKm }.forEachIndexed { idx, all ->
                list.add(
                    LeaderboardItem(
                        rank = idx + 1,
                        name = all.name,
                        tag = all.tag,
                        score = (all.territorySqKm * 10).toInt(),
                        detail = "${all.territorySqKm} km² controlled",
                        type = "alliance"
                    )
                )
            }
        } else if (metric == "xp") {
            list.add(LeaderboardItem(1, player.callsign, player.allianceTag, player.xp + 450, "Rank: ${player.rank}", "player"))
            list.add(LeaderboardItem(2, "General Vane", "[NEX]", 1150, "Rank: General (Grade III)", "player"))
            list.add(LeaderboardItem(3, "Admiral Sterling", "[AEG]", 980, "Rank: Fleet Admiral", "player"))
            list.add(LeaderboardItem(4, "Colonel Ray", "[SOL]", 820, "Rank: Wing Commander", "player"))
            list.add(LeaderboardItem(5, "Major Briggs", "[SOL]", 650, "Rank: Tactical Major", "player"))
        } else {
            // Wealth
            list.add(LeaderboardItem(1, player.callsign, player.allianceTag, player.calculateNetWorth(structures), "Net Worth: $%,d".format(player.calculateNetWorth(structures)), "player"))
            list.add(LeaderboardItem(2, "Nexus Syndicate", "[NEX]", 112000, "12 Bases • 8 Air Wings", "alliance"))
            list.add(LeaderboardItem(3, "Solar Vanguard", "[SOL]", 84000, "8 Bases • 6 Air Wings", "alliance"))
            list.add(LeaderboardItem(4, "Aegis Coalition", "[AEG]", 62000, "6 Bases • 4 Air Wings", "alliance"))
            list.add(LeaderboardItem(5, "General Vane", "[NEX]", 48500, "Citadel Citadel L3", "player"))
        }
        return list
    }
}
