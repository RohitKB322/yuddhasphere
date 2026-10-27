package com.yuddhasphere.network

import android.os.Handler
import android.os.Looper
import com.yuddhasphere.models.GeoPoint
import com.yuddhasphere.models.Player
import com.yuddhasphere.models.Structure
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GameClient(
    private var serverUrl: String = "wss://yuddhasphere-server.onrender.com",
    private val listener: NetworkListener,
    var deviceId: String = "",
    var callsign: String = "Commander"
) {
    interface NetworkListener {
        fun onConnectionStateChanged(connected: Boolean, statusText: String)
        fun onInitSyncReceived(player: Player, structures: List<Structure>)
        fun onStateDiffReceived(diffJson: JSONObject)
        fun onServerAlert(level: String, text: String)
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isConnected = false
    private var shouldReconnect = true

    fun updateCredentials(newDeviceId: String, newCallsign: String) {
        deviceId = newDeviceId
        callsign = newCallsign
        if (isConnected && deviceId.isNotEmpty()) {
            sendRegister(deviceId, callsign)
        }
    }

    fun connect(url: String = serverUrl) {
        serverUrl = url
        shouldReconnect = true
        mainHandler.post { listener.onConnectionStateChanged(false, "Connecting to $serverUrl...") }

        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                mainHandler.post {
                    listener.onConnectionStateChanged(true, "ONLINE (Synced)")
                }
                if (deviceId.isNotEmpty()) {
                    sendRegister(deviceId, callsign)
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "InitSync" -> {
                            // Parse server state
                            val srvPlayer = json.getJSONObject("player")
                            val player = Player(
                                id = srvPlayer.getString("id"),
                                callsign = srvPlayer.getString("callsign"),
                                team = srvPlayer.getString("team"),
                                teamColor = srvPlayer.optString("teamColor", "#00E5FF"),
                                position = GeoPoint(
                                    srvPlayer.getJSONObject("position").getDouble("lat"),
                                    srvPlayer.getJSONObject("position").getDouble("lng")
                                ),
                                wealth = srvPlayer.optInt("wealth", 2500),
                                incomePerHour = srvPlayer.optInt("incomePerHour", 100),
                                hp = srvPlayer.optInt("hp", 1000)
                            )

                            val structList = mutableListOf<Structure>()
                            val sArray = json.optJSONArray("structures")
                            if (sArray != null) {
                                for (i in 0 until sArray.length()) {
                                    val sObj = sArray.getJSONObject(i)
                                    val s = Structure(
                                        id = sObj.getString("id"),
                                        ownerId = sObj.optString("ownerId", player.id),
                                        team = sObj.optString("team", "blue"),
                                        teamColor = sObj.optString("teamColor", "#00E5FF"),
                                        type = sObj.getString("type"),
                                        name = sObj.optString("name", "Structure"),
                                        level = sObj.optInt("level", 1),
                                        position = GeoPoint(
                                            sObj.getJSONObject("position").getDouble("lat"),
                                            sObj.getJSONObject("position").getDouble("lng")
                                        ),
                                        hp = sObj.optInt("hp", 100),
                                        maxHp = sObj.optInt("maxHp", 100),
                                        radiusMeters = sObj.optDouble("radiusMeters", 150.0),
                                        radiusType = sObj.optString("radiusType", "influence"),
                                        online = sObj.optBoolean("online", true),
                                        upgradeFinishedAt = if (sObj.has("upgradeFinishedAt") && !sObj.isNull("upgradeFinishedAt")) sObj.getLong("upgradeFinishedAt") else null,
                                        samMunitions = sObj.optInt("samMunitions", 6)
                                    )
                                    if (sObj.has("hangarJets")) {
                                        val jArr = sObj.getJSONArray("hangarJets")
                                        for (k in 0 until jArr.length()) {
                                            val jObj = jArr.getJSONObject(k)
                                            s.hangarJets.add(
                                                com.yuddhasphere.models.HangarJetItem(
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
                                    structList.add(s)
                                }
                            }
                            mainHandler.post { listener.onInitSyncReceived(player, structList) }
                        }
                        "StateDiff" -> {
                            mainHandler.post { listener.onStateDiffReceived(json) }
                        }
                        "Alert" -> {
                            val level = json.optString("level", "info")
                            val msg = json.optString("text", "")
                            mainHandler.post { listener.onServerAlert(level, msg) }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                mainHandler.post {
                    listener.onConnectionStateChanged(false, "AUTONOMOUS (Local Sim)")
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                mainHandler.post {
                    listener.onConnectionStateChanged(false, "AUTONOMOUS (Local Sim)")
                }
            }
        })
    }

    fun disconnect() {
        shouldReconnect = false
        webSocket?.close(1000, "User disconnect")
        webSocket = null
    }

    fun sendRegister(deviceId: String, callsign: String) {
        val json = JSONObject().apply {
            put("type", "Register")
            put("deviceId", deviceId)
            put("callsign", callsign)
        }
        send(json)
    }

    fun sendLocation(pos: GeoPoint, accuracy: Double) {
        val json = JSONObject().apply {
            put("type", "ReportLocation")
            put("lat", pos.lat)
            put("lng", pos.lng)
            put("accuracy", accuracy)
            put("timestamp", System.currentTimeMillis())
        }
        send(json)
    }

    fun sendBuildStructure(type: String, lat: Double, lng: Double) {
        val json = JSONObject().apply {
            put("type", "BuildStructure")
            put("structureType", type)
            put("lat", lat)
            put("lng", lng)
        }
        send(json)
    }

    fun sendUpgrade(structureId: String) {
        val json = JSONObject().apply {
            put("type", "UpgradeStructure")
            put("structureId", structureId)
        }
        send(json)
    }

    fun sendSell(structureId: String) {
        val json = JSONObject().apply {
            put("type", "SellStructure")
            put("structureId", structureId)
        }
        send(json)
    }

    fun sendBuyItem(structureId: String, catalogId: String) {
        val json = JSONObject().apply {
            put("type", "BuyItem")
            put("structureId", structureId)
            put("catalogId", catalogId)
        }
        send(json)
    }

    fun sendRepairStructure(structureId: String) {
        val json = JSONObject().apply {
            put("type", "RepairStructure")
            put("structureId", structureId)
        }
        send(json)
    }

    fun sendCreateAlliance(name: String, tag: String, motto: String, colorHex: String) {
        val json = JSONObject().apply {
            put("type", "CreateAlliance")
            put("name", name)
            put("tag", tag)
            put("motto", motto)
            put("colorHex", colorHex)
        }
        send(json)
    }

    fun sendJoinAlliance(allianceId: String) {
        val json = JSONObject().apply {
            put("type", "JoinAlliance")
            put("allianceId", allianceId)
        }
        send(json)
    }

    fun sendLeaveAlliance() {
        val json = JSONObject().apply {
            put("type", "LeaveAlliance")
        }
        send(json)
    }

    fun sendSetTaxRate(taxPercent: Int) {
        val json = JSONObject().apply {
            put("type", "SetTaxRate")
            put("taxPercent", taxPercent)
        }
        send(json)
    }

    fun sendSetMemberRole(targetPlayerId: String, role: String) {
        val json = JSONObject().apply {
            put("type", "SetMemberRole")
            put("targetPlayerId", targetPlayerId)
            put("role", role)
        }
        send(json)
    }

    fun sendProposeTreaty(targetAllianceId: String, status: String) {
        val json = JSONObject().apply {
            put("type", "ProposeTreaty")
            put("targetAllianceId", targetAllianceId)
            put("status", status)
        }
        send(json)
    }

    fun sendGetLeaderboards(metric: String) {
        val json = JSONObject().apply {
            put("type", "GetLeaderboards")
            put("metric", metric)
        }
        send(json)
    }

    private fun send(json: JSONObject) {
        if (isConnected) {
            webSocket?.send(json.toString())
        }
    }
}
