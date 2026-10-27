package com.yuddhasphere.map

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

class MapBridge(private val listener: BridgeListener) {

    interface BridgeListener {
        fun onMapReady()
        fun onBuildingClicked(structureId: String)
        fun onJetClicked(jetId: String)
        fun onPlayerAvatarClicked()
        fun onMapClicked()
        fun onConfirmPlacement(type: String, lat: Double, lng: Double)
        fun onSortieTargetPicked(lat: Double, lng: Double, targetStructureId: String)
        fun onMissileClicked(
            missileId: String,
            missileType: String,
            targetName: String,
            speedMach: Double,
            etaSeconds: Double,
            hitProbability: Double,
            distKm: Double
        )
        fun onUnitShotDown(unitId: String, unitName: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onMapReady() {
        mainHandler.post { listener.onMapReady() }
    }

    @JavascriptInterface
    fun onPlayerAvatarClicked() {
        mainHandler.post { listener.onPlayerAvatarClicked() }
    }

    @JavascriptInterface
    fun onBuildingClicked(structureId: String) {
        mainHandler.post { listener.onBuildingClicked(structureId) }
    }

    @JavascriptInterface
    fun onJetClicked(jetId: String) {
        mainHandler.post { listener.onJetClicked(jetId) }
    }

    @JavascriptInterface
    fun onMapClicked() {
        mainHandler.post { listener.onMapClicked() }
    }

    @JavascriptInterface
    fun onConfirmPlacement(type: String, lat: Double, lng: Double) {
        mainHandler.post { listener.onConfirmPlacement(type, lat, lng) }
    }

    @JavascriptInterface
    fun onSortieTargetPicked(lat: Double, lng: Double, targetStructureId: String) {
        mainHandler.post { listener.onSortieTargetPicked(lat, lng, targetStructureId) }
    }

    @JavascriptInterface
    fun onMissileClicked(
        missileId: String,
        missileType: String,
        targetName: String,
        speedMach: Double,
        etaSeconds: Double,
        hitProbability: Double,
        distKm: Double
    ) {
        mainHandler.post {
            listener.onMissileClicked(missileId, missileType, targetName, speedMach, etaSeconds, hitProbability, distKm)
        }
    }

    @JavascriptInterface
    fun onUnitShotDown(unitId: String, unitName: String) {
        mainHandler.post { listener.onUnitShotDown(unitId, unitName) }
    }
}
