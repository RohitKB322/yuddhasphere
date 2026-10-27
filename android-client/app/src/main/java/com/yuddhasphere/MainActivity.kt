package com.yuddhasphere

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.yuddhasphere.assets.AssetRegistry
import com.yuddhasphere.databinding.ActivityMainBinding
import com.yuddhasphere.location.LocationTracker
import com.yuddhasphere.map.MapBridge
import com.yuddhasphere.models.CatalogBuilding
import com.yuddhasphere.models.GeoPoint
import com.yuddhasphere.models.Player
import com.yuddhasphere.models.Structure
import com.yuddhasphere.models.UnitItem
import com.yuddhasphere.network.GameClient
import com.yuddhasphere.state.LocalGameState
import com.yuddhasphere.ui.BuildMenuSheet
import com.yuddhasphere.ui.MarketSheet
import android.widget.LinearLayout
import android.widget.TextView
import com.yuddhasphere.ui.MilitarySheet
import com.yuddhasphere.ui.ProcurementSheet
import com.yuddhasphere.ui.UnderProgressDialog
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity(), MapBridge.BridgeListener, LocalGameState.StateListener, GameClient.NetworkListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var gameState: LocalGameState
    private lateinit var locationTracker: LocationTracker
    private lateinit var gameClient: GameClient
    private lateinit var prefs: SharedPreferences

    private var activePlacementBuilding: CatalogBuilding? = null
    private var isMapReady = false
    private var activeInspectedStructureId: String? = null
    private var activeSelectedJetId: String? = null

    private var isRetargetingMode = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            locationTracker.startTracking()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge full immersion layout
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("yuddhasphere_prefs", Context.MODE_PRIVATE)

        // Safe area insets handling
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.topHudContainer.setPadding(
                binding.topHudContainer.paddingLeft,
                statusBars.top.coerceAtLeast(10),
                binding.topHudContainer.paddingRight,
                binding.topHudContainer.paddingBottom
            )
            binding.bottomNavBar.translationY = -navBars.bottom.toFloat().coerceAtLeast(0f)
            insets
        }

        // Persistent unique device identity
        val userIdKey = "device_user_id"
        var userId = prefs.getString(userIdKey, null)
        if (userId.isNullOrEmpty()) {
            userId = "usr_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
            prefs.edit().putString(userIdKey, userId).apply()
        }

        gameState = LocalGameState(this)
        gameState.player.id = userId
        loadSavedCommanderProfile()
        gameState.addListener(this)

        val savedHost = prefs.getString("server_host", "yuddhasphere-server.onrender.com") ?: "yuddhasphere-server.onrender.com"
        val savedPort = prefs.getString("server_port", "443") ?: "443"
        val initialUrl = buildServerUrl(savedHost, savedPort)
        val savedCallsign = prefs.getString("callsign", "Commander") ?: "Commander"
        gameClient = GameClient(initialUrl, this, userId, savedCallsign)
        gameClient.connect(initialUrl)

        initMapWebView()
        initNavigationBars()
        initPlacementControls()
        initFloatingCards()
        initOverlays()
        initLocationTracker()

        // If no callsign set yet, show commander registration card immediately
        if (!prefs.contains("callsign")) {
            showCommanderRegistration()
        }
    }

    private fun loadSavedCommanderProfile() {
        val savedCallsign = prefs.getString("callsign", "Commander") ?: "Commander"
        val savedTag = prefs.getString("alliance_tag", "") ?: ""
        val savedColor = prefs.getString("team_color", "#00E5FF") ?: "#00E5FF"
        gameState.updateCommanderProfile(savedCallsign, savedTag, savedColor)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initMapWebView() {
        binding.mapWebView.apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString = "YuddhaSphere-RTS/0.3 (Android; Mobile)"

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    postDelayed({
                        if (!isMapReady) {
                            onMapReady()
                        }
                    }, 500)
                }
            }

            addJavascriptInterface(MapBridge(this@MainActivity), "AndroidBridge")
            loadUrl("file:///android_asset/map/index.html")
        }
    }

    private fun initNavigationBars() {
        // Bottom Nav Item 1: Build
        binding.navBuild.setOnClickListener {
            hideAllCards()
            BuildMenuSheet(this, gameState) { selectedBuilding ->
                startPlacementMode(selectedBuilding)
            }.show()
        }

        // Bottom Nav Item 2: Military (Airbase Hangar & Munitions)
        binding.navMilitary.setOnClickListener {
            hideAllCards()
            showMilitarySheet()
        }

        // Bottom Nav Item 3: Market (GTX Global Tactical Exchange)
        binding.navMarket.setOnClickListener {
            hideAllCards()
            showMarketScreen()
        }

        // Bottom Nav Item 4: Alliance (Full Screen Overlay)
        binding.navAlliance.setOnClickListener {
            hideAllCards()
            showAllianceScreen()
        }

        // Bottom Nav Item 5: Reports (24/7 Breaking War News Wire Overlay)
        binding.navReports.setOnClickListener {
            hideAllCards()
            showReportsScreen()
        }

        // Bottom Nav Item 6: Menu (Settings & Server Nexus)
        binding.navMenu.setOnClickListener {
            showCommanderRegistration()
        }

        // Wealth Details Button in Top HUD -> opens GTX Market & Portfolio
        binding.btnWealthDetails.setOnClickListener {
            showMarketScreen()
        }

        // Recenter / Locate Me Button (in Top HUD)
        binding.btnRecenterGps.setOnClickListener {
            binding.mapWebView.evaluateJavascript("window.recenterMap();", null)
        }

        // Commander HP / Avatar in Top HUD -> opens Commander Profile
        binding.btnHpDetails.setOnClickListener {
            showCommanderProfileCard()
        }

        // Alerts Button -> opens breaking war reports
        binding.btnAlerts.setOnClickListener {
            hideAllCards()
            showReportsScreen()
        }

        // Cancel Sortie Button
        binding.btnCancelSortie.setOnClickListener {
            cancelSortieTargetingMode()
        }

        // Close Missile Telemetry HUD
        binding.btnCloseMissileTelemetry.setOnClickListener {
            binding.cardMissileTelemetry.visibility = View.GONE
        }

        // Close Hostile Target Inspector HUD
        binding.btnCloseHostileInspector.setOnClickListener {
            binding.cardHostileTargetInspector.visibility = View.GONE
        }
    }

    private fun showMilitarySheet() {
        val airbase = gameState.structures.find { it.type == "airbase" && it.online }
            ?: gameState.structures.find { it.type == "town_hall" }

        if (airbase != null) {
            showMilitarySheetForAirbase(airbase.id)
        } else {
            Toast.makeText(this, "Construct an Airbase first to procure and station fighter aircraft!", Toast.LENGTH_LONG).show()
        }
    }

    private fun showMilitarySheetForAirbase(targetAirbaseId: String) {
        val airbase = gameState.structures.find { it.id == targetAirbaseId } ?: return
        ProcurementSheet(this, gameState, gameClient, airbase.id, "airbase") {
            showStructureInspector(airbase.id)
        }.show()
    }

    private fun promptSortieTargeting(airbaseId: String, hangarJetId: String, jetName: String) {
        pendingTargetingAirbaseId = airbaseId
        pendingTargetingHangarJetId = hangarJetId
        binding.sortieTargetingBar.visibility = View.VISIBLE
        binding.bottomNavBar.visibility = View.GONE
        binding.tvSortieHint.text = "✈ Tap target building on map to strike with $jetName"
        binding.mapWebView.evaluateJavascript("window.startSortieTargeting();", null)
    }

    private var pendingTargetingAirbaseId: String? = null
    private var pendingTargetingHangarJetId: String? = null
    private var pendingTargetingCruiseSiloId: String? = null

    private fun promptCruiseMissileTargeting(siloId: String) {
        pendingTargetingCruiseSiloId = siloId
        binding.sortieTargetingBar.visibility = View.VISIBLE
        binding.bottomNavBar.visibility = View.GONE
        binding.tvSortieHint.text = "🎯 Tap target on map (within 200km) to launch BrahMos-ER Cruise Missile"
        binding.mapWebView.evaluateJavascript("window.startSortieTargeting();", null)
    }

    private fun cancelSortieTargetingMode() {
        pendingTargetingAirbaseId = null
        pendingTargetingHangarJetId = null
        pendingTargetingCruiseSiloId = null
        isRetargetingMode = false
        binding.sortieTargetingBar.visibility = View.GONE
        binding.bottomNavBar.visibility = View.VISIBLE
        binding.mapWebView.evaluateJavascript("window.cancelSortieTargeting();", null)
    }

    private fun initPlacementControls() {
        binding.btnCancelPlacement.setOnClickListener {
            cancelPlacementMode()
        }

        binding.btnPlaceAtGps.setOnClickListener {
            binding.mapWebView.evaluateJavascript("window.setPlacementToPlayerGps();", null)
        }

        binding.btnConfirmPlacement.setOnClickListener {
            val building = activePlacementBuilding ?: return@setOnClickListener

            binding.mapWebView.evaluateJavascript("window.getPlacementCoordinates();") { coordsJson ->
                val cleanedJson = coordsJson?.replace("^\"|\"$".toRegex(), "")?.replace("\\\"", "\"") ?: "{}"
                try {
                    val obj = JSONObject(cleanedJson)
                    val lat = obj.optDouble("lat", gameState.player.position.lat)
                    val lng = obj.optDouble("lng", gameState.player.position.lng)

                    val targetPos = GeoPoint(lat, lng)
                    val result = gameState.buildStructure(building.type, targetPos)

                    if (result.first) {
                        cancelPlacementMode()
                        syncWorldToMap()
                        gameClient.sendBuildStructure(building.type, lat, lng)
                        Toast.makeText(this, "Constructed ${building.name}!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, result.second, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Placement error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startPlacementMode(building: CatalogBuilding) {
        activePlacementBuilding = building
        binding.placementControlBar.visibility = View.VISIBLE
        binding.bottomNavBar.visibility = View.GONE
        binding.tvPlacementHint.text = "Placing ${building.name} (${building.radiusMeters.toInt()}m ${building.radiusType})"

        val jsCall = "window.startPlacement('${building.type}', ${building.radiusMeters}, '${building.radiusType}');"
        binding.mapWebView.evaluateJavascript(jsCall, null)
    }

    private fun cancelPlacementMode() {
        activePlacementBuilding = null
        binding.placementControlBar.visibility = View.GONE
        binding.bottomNavBar.visibility = View.VISIBLE
        binding.mapWebView.evaluateJavascript("window.cancelPlacement();", null)
    }

    // --- FLOATING IN-LAYOUT CARDS (Map NEVER blacks out!) ---

    private fun initFloatingCards() {
        // Close Structure Inspector
        binding.btnCloseInspector.setOnClickListener {
            binding.cardStructureInspector.visibility = View.GONE
            activeInspectedStructureId = null
            binding.mapWebView.evaluateJavascript("window.deselectAll();", null)
        }

        // Close Jet Control
        binding.btnCloseJetControl.setOnClickListener {
            binding.cardJetSortieControl.visibility = View.GONE
            activeSelectedJetId = null
            binding.mapWebView.evaluateJavascript("window.untrackJet(); window.deselectAll();", null)
        }

        // Close Commander Profile
        binding.btnCloseCommanderProfile.setOnClickListener {
            binding.cardCommanderProfile.visibility = View.GONE
        }

        // Edit Commander Profile
        binding.btnEditCommanderProfile.setOnClickListener {
            showCommanderRegistration()
        }

        // In-Flight Jet Speed: Subsonic
        binding.btnSpeedSubsonic.setOnClickListener {
            val jetId = activeSelectedJetId ?: return@setOnClickListener
            gameState.setUnitSpeedMode(jetId, "subsonic")
            binding.btnSpeedSubsonic.setBackgroundResource(R.drawable.bg_accent_button)
            binding.btnSpeedSubsonic.setTextColor(getColor(R.color.bg_tactical_dark))
            binding.btnSpeedSupersonic.setBackgroundResource(R.drawable.bg_secondary_button)
            binding.btnSpeedSupersonic.setTextColor(getColor(R.color.accent_orange))
        }

        // In-Flight Jet Speed: Supersonic
        binding.btnSpeedSupersonic.setOnClickListener {
            val jetId = activeSelectedJetId ?: return@setOnClickListener
            gameState.setUnitSpeedMode(jetId, "supersonic")
            binding.btnSpeedSupersonic.setBackgroundResource(R.drawable.bg_accent_button)
            binding.btnSpeedSupersonic.setTextColor(getColor(R.color.bg_tactical_dark))
            binding.btnSpeedSubsonic.setBackgroundResource(R.drawable.bg_secondary_button)
            binding.btnSpeedSubsonic.setTextColor(getColor(R.color.text_secondary))
        }

        // In-Flight Jet: Radar Arc Sector (30, 60, 90)
        binding.btnRadarArc30.setOnClickListener {
            val jetId = activeSelectedJetId ?: return@setOnClickListener
            gameState.setUnitRadarCone(jetId, 30)
            updateRadarArcButtons(30)
        }
        binding.btnRadarArc60.setOnClickListener {
            val jetId = activeSelectedJetId ?: return@setOnClickListener
            gameState.setUnitRadarCone(jetId, 60)
            updateRadarArcButtons(60)
        }
        binding.btnRadarArc90.setOnClickListener {
            val jetId = activeSelectedJetId ?: return@setOnClickListener
            gameState.setUnitRadarCone(jetId, 90)
            updateRadarArcButtons(90)
        }

        // In-Flight Jet: ROE Tactics (Aggressive, Fire at Will, Peaceful)
        binding.btnRoeAggressive.setOnClickListener {
            val jetId = activeSelectedJetId ?: return@setOnClickListener
            gameState.setUnitRoe(jetId, "aggressive")
            updateRoeButtons("aggressive")
        }
        binding.btnRoeFireAtWill.setOnClickListener {
            val jetId = activeSelectedJetId ?: return@setOnClickListener
            gameState.setUnitRoe(jetId, "fire_at_will")
            updateRoeButtons("fire_at_will")
        }
        binding.btnRoePeaceful.setOnClickListener {
            val jetId = activeSelectedJetId ?: return@setOnClickListener
            gameState.setUnitRoe(jetId, "peaceful")
            updateRoeButtons("peaceful")
        }

        // Tactical Map Zoom Controls (+ and -)
        binding.btnMapZoomIn.setOnClickListener {
            binding.mapWebView.evaluateJavascript("window.zoomIn();", null)
        }
        binding.btnMapZoomOut.setOnClickListener {
            binding.mapWebView.evaluateJavascript("window.zoomOut();", null)
        }

        // In-Flight Jet: Fox-2 Missile Manual Launch
        binding.btnLaunchFoxTwo.setOnClickListener {
            val jetId = activeSelectedJetId ?: return@setOnClickListener
            val res = gameState.fireManualMissile(jetId)
            if (res.first) {
                val u = gameState.activeUnits.find { it.id == jetId }
                if (u != null) {
                    showJetSortieControl(u.id)
                }
            }
        }

        // In-Flight Jet: Abort & RTB
        binding.btnAbortRtb.setOnClickListener {
            val jetId = activeSelectedJetId ?: return@setOnClickListener
            gameState.abortUnitMissionRtb(jetId)
            binding.cardJetSortieControl.visibility = View.GONE
        }

        // In-Flight Jet: Re-target
        binding.btnRetargetSortie.setOnClickListener {
            val jetId = activeSelectedJetId ?: return@setOnClickListener
            isRetargetingMode = true
            binding.cardJetSortieControl.visibility = View.GONE
            binding.sortieTargetingBar.visibility = View.VISIBLE
            binding.bottomNavBar.visibility = View.GONE
            binding.tvSortieHint.text = "✈ Tap target building on map to re-assign strike"
            binding.mapWebView.evaluateJavascript("window.startSortieTargeting();", null)
        }

        // In-Flight Jet: Set Patrol
        binding.btnSetPatrol.setOnClickListener {
            val jetId = activeSelectedJetId ?: return@setOnClickListener
            val u = gameState.activeUnits.find { it.id == jetId }
            if (u != null) {
                gameState.setUnitPatrol(jetId, u.position)
                binding.cardJetSortieControl.visibility = View.GONE
            }
        }

        // Commander & Server Settings Controls
        binding.btnCloseCommanderSetup.setOnClickListener {
            binding.cardCommanderSetup.visibility = View.GONE
        }

        binding.btnPresetEmulator.setOnClickListener {
            binding.etServerHost.setText("yuddhasphere-server.onrender.com")
            binding.etServerPort.setText("443")
            Toast.makeText(this, "Set to Render Cloud Server", Toast.LENGTH_SHORT).show()
        }

        binding.btnPresetLocalhost.setOnClickListener {
            binding.etServerHost.setText("192.168.137.1")
            binding.etServerPort.setText("8080")
            Toast.makeText(this, "Set to Local Hotspot (192.168.137.1)", Toast.LENGTH_SHORT).show()
        }

        binding.btnTestServerConnection.setOnClickListener {
            val host = binding.etServerHost.text.toString().trim().ifEmpty { "yuddhasphere-server.onrender.com" }
            val port = binding.etServerPort.text.toString().trim().ifEmpty { "443" }
            val targetUrl = buildServerUrl(host, port)
            binding.tvServerStatusBadge.text = "Status: Connecting to $targetUrl..."
            binding.tvServerStatusBadge.setTextColor(Color.parseColor("#FF9F0A"))
            gameClient.connect(targetUrl)
            Toast.makeText(this, "Testing connection to $targetUrl...", Toast.LENGTH_SHORT).show()
        }

        // Commander Setup & Server Save
        binding.btnSaveCommanderProfile.setOnClickListener {
            val callsign = binding.etCommanderCallsign.text.toString().trim()
            val tag = binding.etCommanderTag.text.toString().trim()
            val host = binding.etServerHost.text.toString().trim().ifEmpty { "yuddhasphere-server.onrender.com" }
            val port = binding.etServerPort.text.toString().trim().ifEmpty { "443" }

            if (callsign.isNotEmpty()) {
                val finalTag = if (tag.isNotEmpty()) (if (tag.startsWith("[")) tag else "[$tag]") else ""
                prefs.edit()
                    .putString("callsign", callsign)
                    .putString("alliance_tag", finalTag)
                    .putString("server_host", host)
                    .putString("server_port", port)
                    .apply()

                gameState.updateCommanderProfile(callsign, finalTag, "#00E5FF")
                val srvUrl = buildServerUrl(host, port)
                val currentUid = prefs.getString("device_user_id", "usr_commander") ?: "usr_commander"
                gameClient.updateCredentials(currentUid, callsign)
                gameClient.connect(srvUrl)
                binding.cardCommanderSetup.visibility = View.GONE
                syncPlayerPositionToMap(autoRecenter = false)
                Toast.makeText(this, "Settings saved. Connecting to $srvUrl...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enter a Callsign.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildServerUrl(hostInput: String, portInput: String): String {
        val clean = hostInput.trim().removePrefix("http://").removePrefix("https://")
        if (clean.startsWith("ws://") || clean.startsWith("wss://")) {
            return clean
        }
        if (clean.contains(".onrender.com") || clean.contains(".koyeb.app") || clean.contains(".up.railway.app") || clean.contains(".loca.lt") || clean.contains(".glitch.me")) {
            return "wss://$clean"
        }
        val port = portInput.trim().ifEmpty { "8080" }
        return "ws://$clean:$port"
    }

    private fun updateRadarArcButtons(selectedAngle: Int) {
        val pairs = listOf(
            binding.btnRadarArc30 to 30,
            binding.btnRadarArc60 to 60,
            binding.btnRadarArc90 to 90
        )
        pairs.forEach { (btn, angle) ->
            if (angle == selectedAngle) {
                btn.setBackgroundResource(R.drawable.bg_accent_button)
                btn.setTextColor(getColor(R.color.bg_tactical_dark))
            } else {
                btn.setBackgroundResource(R.drawable.bg_secondary_button)
                btn.setTextColor(getColor(R.color.white))
            }
        }
    }

    private fun updateRoeButtons(selectedRoe: String) {
        val pairs = listOf(
            binding.btnRoeAggressive to "aggressive",
            binding.btnRoeFireAtWill to "fire_at_will",
            binding.btnRoePeaceful to "peaceful"
        )
        pairs.forEach { (btn, roe) ->
            if (roe == selectedRoe) {
                btn.setBackgroundResource(R.drawable.bg_accent_button)
                btn.setTextColor(getColor(R.color.bg_tactical_dark))
            } else {
                btn.setBackgroundResource(R.drawable.bg_secondary_button)
                btn.setTextColor(getColor(R.color.text_secondary))
            }
        }
    }

    private fun hideAllCards() {
        if (activeSelectedJetId != null) {
            binding.mapWebView.evaluateJavascript("window.untrackJet();", null)
        }
        binding.cardStructureInspector.visibility = View.GONE
        binding.cardJetSortieControl.visibility = View.GONE
        binding.cardMissileTelemetry.visibility = View.GONE
        binding.cardHostileTargetInspector.visibility = View.GONE
        binding.cardCommanderSetup.visibility = View.GONE
        binding.cardCommanderProfile.visibility = View.GONE
        binding.overlayReportsScreen.visibility = View.GONE
        binding.overlayAllianceScreen.visibility = View.GONE
        binding.overlayMarketScreen.visibility = View.GONE
        activeInspectedStructureId = null
        activeSelectedJetId = null
    }

    private fun showCommanderRegistration() {
        hideAllCards()
        binding.cardCommanderSetup.visibility = View.VISIBLE
        binding.etCommanderCallsign.setText(gameState.player.callsign)
        binding.etCommanderTag.setText(gameState.player.allianceTag)
        binding.etServerHost.setText(prefs.getString("server_host", "yuddhasphere-server.onrender.com"))
        binding.etServerPort.setText(prefs.getString("server_port", "443"))
    }

    private fun showCommanderProfileCard() {
        hideAllCards()
        val p = gameState.player
        binding.cardCommanderProfile.visibility = View.VISIBLE
        binding.tvProfileCallsign.text = p.callsign
        binding.tvProfileRank.text = "${p.rank} • ${p.allianceTag}"
        binding.tvProfileNetWorth.text = "$%,d".format(p.calculateNetWorth(gameState.structures))
        binding.tvProfileXp.text = "${p.xp} / 1000 XP"
        binding.pbProfileXp.progress = p.xp
    }

    private fun showStructureInspector(structureId: String) {
        val s = gameState.structures.find { it.id == structureId } ?: return
        hideAllCards()
        activeInspectedStructureId = structureId
        binding.cardStructureInspector.visibility = View.VISIBLE

        binding.ivInspectorIcon.setImageResource(AssetRegistry.getDrawableForStructure(s.type, s.level))
        binding.tvInspectorName.text = s.name
        binding.tvInspectorLevel.text = "Level ${s.level} • ${s.radiusType.replaceFirstChar { it.uppercase() }} Structure"

        if (s.upgradeFinishedAt != null) {
            binding.tvInspectorStatus.text = "UPGRADING"
            binding.tvInspectorStatus.setTextColor(getColor(R.color.accent_orange))
        } else if (s.online) {
            binding.tvInspectorStatus.text = "ONLINE"
            binding.tvInspectorStatus.setTextColor(getColor(R.color.accent_green))
        } else {
            binding.tvInspectorStatus.text = "OFFLINE"
            binding.tvInspectorStatus.setTextColor(getColor(R.color.accent_red))
        }

        binding.tvInspectorHp.text = "${s.hp} / ${s.maxHp}"
        binding.pbInspectorHp.max = s.maxHp
        binding.pbInspectorHp.progress = s.hp
        binding.tvInspectorRadius.text = "Radius: ${s.radiusMeters.toInt()}m"

        val cat = gameState.catalogBuildings[s.type]
        binding.tvInspectorIncome.text = if (cat != null && cat.incomePerHour > 0) "Income: +$${cat.incomePerHour}/hr" else "Income: $0/hr"

        // Dynamic visual slots for Airbase Hangar, SAM Silos & Missile Launcher Silos
        if (s.type == "airbase") {
            binding.layoutHangarSlots.visibility = View.VISIBLE
            binding.layoutSamSlots.visibility = View.GONE
            renderHangarSlots(s)
        } else if (s.type == "sam") {
            binding.layoutSamSlots.visibility = View.VISIBLE
            binding.layoutHangarSlots.visibility = View.GONE
            renderSamSlots(s)
        } else if (s.type == "missile_silo") {
            binding.layoutSamSlots.visibility = View.VISIBLE
            binding.layoutHangarSlots.visibility = View.GONE
            renderMissileSiloSlots(s)
        } else {
            binding.layoutHangarSlots.visibility = View.GONE
            binding.layoutSamSlots.visibility = View.GONE
        }

        // Upgrade button
        val nextType = cat?.nextLevelType
        if (nextType != null && cat.upgradeCost > 0) {
            binding.btnInspectorUpgrade.visibility = View.VISIBLE
            binding.btnInspectorUpgrade.text = "UPGRADE ($${cat.upgradeCost})"
            binding.btnInspectorUpgrade.setOnClickListener {
                val res = gameState.upgradeStructure(s.id)
                if (res.first) {
                    gameClient.sendUpgrade(s.id)
                }
                binding.cardStructureInspector.visibility = View.GONE
            }
        } else {
            binding.btnInspectorUpgrade.visibility = View.GONE
        }

        // Repair button
        binding.btnInspectorRepair.setOnClickListener {
            val res = gameState.repairStructure(s.id)
            if (res.first) {
                gameClient.sendRepairStructure(s.id)
                binding.tvInspectorHp.text = "${s.hp} / ${s.maxHp}"
                binding.pbInspectorHp.progress = s.hp
            }
        }

        // Sell button
        binding.btnInspectorSell.setOnClickListener {
            val res = gameState.sellStructure(s.id)
            if (res.first) {
                gameClient.sendSell(s.id)
            }
            binding.cardStructureInspector.visibility = View.GONE
            binding.mapWebView.evaluateJavascript("window.deselectAll();", null)
        }
    }

    private fun renderHangarSlots(s: Structure) {
        binding.containerHangarSlots.removeAllViews()
        val maxSlots = s.maxHangarSlots()
        binding.tvHangarHeader.text = "✈ AIRBASE HANGAR BAYS (${s.hangarJets.size}/$maxSlots BAYS OCCUPIED)"

        for (i in 0 until maxSlots) {
            val slotCard = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val params = android.widget.LinearLayout.LayoutParams(
                    (140 * resources.displayMetrics.density).toInt(),
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (8 * resources.displayMetrics.density).toInt()
                }
                layoutParams = params
                setPadding(
                    (8 * resources.displayMetrics.density).toInt(),
                    (8 * resources.displayMetrics.density).toInt(),
                    (8 * resources.displayMetrics.density).toInt(),
                    (8 * resources.displayMetrics.density).toInt()
                )
            }

            if (i < s.hangarJets.size) {
                val jet = s.hangarJets[i]
                slotCard.setBackgroundResource(R.drawable.bg_slot_occupied)

                val tvTitle = android.widget.TextView(this).apply {
                    text = "BAY #${i + 1} • ${jet.name}"
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val tvStatus = android.widget.TextView(this).apply {
                    text = jet.status.uppercase()
                    setTextColor(if (jet.status.contains("Ready")) Color.parseColor("#30D158") else Color.parseColor("#FF9F0A"))
                    textSize = 9.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val tvHp = android.widget.TextView(this).apply {
                    text = "Integrity: ${jet.hp}/${jet.maxHp} HP"
                    setTextColor(Color.parseColor("#8E8E93"))
                    textSize = 9f
                }
                val btnAction = android.widget.Button(this).apply {
                    text = if (jet.status.contains("Ready")) "🚀 SCRAMBLE" else "✈ IN-FLIGHT"
                    textSize = 10f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#070B14"))
                    setBackgroundResource(if (jet.status.contains("Ready")) R.drawable.bg_accent_button else R.drawable.bg_secondary_button)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        (32 * resources.displayMetrics.density).toInt()
                    ).apply {
                        topMargin = (6 * resources.displayMetrics.density).toInt()
                    }
                    setOnClickListener {
                        if (jet.status.contains("Ready")) {
                            binding.cardStructureInspector.visibility = View.GONE
                            promptSortieTargeting(s.id, jet.id, jet.name)
                        } else {
                            binding.cardStructureInspector.visibility = View.GONE
                            showJetSortieControl(jet.id)
                        }
                    }
                }

                slotCard.addView(tvTitle)
                slotCard.addView(tvStatus)
                slotCard.addView(tvHp)
                slotCard.addView(btnAction)
            } else {
                slotCard.setBackgroundResource(R.drawable.bg_slot_empty)

                val tvTitle = android.widget.TextView(this).apply {
                    text = "BAY #${i + 1} (EMPTY)"
                    setTextColor(Color.parseColor("#00E5FF"))
                    textSize = 11f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val tvDesc = android.widget.TextView(this).apply {
                    text = "Empty Hangar Bay"
                    setTextColor(Color.parseColor("#8E8E93"))
                    textSize = 9f
                }
                val btnBuy = android.widget.Button(this).apply {
                    text = "+ BUY JET"
                    textSize = 10f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#00E5FF"))
                    setBackgroundResource(R.drawable.bg_secondary_button)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        (32 * resources.displayMetrics.density).toInt()
                    ).apply {
                        topMargin = (6 * resources.displayMetrics.density).toInt()
                    }
                }

                val onEmptySlotClicked = View.OnClickListener {
                    showMilitarySheetForAirbase(s.id)
                }
                slotCard.setOnClickListener(onEmptySlotClicked)
                btnBuy.setOnClickListener(onEmptySlotClicked)

                slotCard.addView(tvTitle)
                slotCard.addView(tvDesc)
                slotCard.addView(btnBuy)
            }

            binding.containerHangarSlots.addView(slotCard)
        }
    }

    private fun renderSamSlots(s: Structure) {
        binding.containerSamSlots.removeAllViews()
        val maxSlots = s.maxSamSlots()
        binding.tvSamHeader.text = "🚀 SAM INTERCEPTOR SILOS (${s.samMunitions}/$maxSlots ARMED)"

        for (i in 0 until maxSlots) {
            val siloCard = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val params = android.widget.LinearLayout.LayoutParams(
                    (115 * resources.displayMetrics.density).toInt(),
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (8 * resources.displayMetrics.density).toInt()
                }
                layoutParams = params
                setPadding(
                    (8 * resources.displayMetrics.density).toInt(),
                    (8 * resources.displayMetrics.density).toInt(),
                    (8 * resources.displayMetrics.density).toInt(),
                    (8 * resources.displayMetrics.density).toInt()
                )
            }

            if (i < s.samMunitions) {
                siloCard.setBackgroundResource(R.drawable.bg_slot_occupied)

                val tvTitle = android.widget.TextView(this).apply {
                    text = "SILO #${i + 1}"
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val tvStatus = android.widget.TextView(this).apply {
                    text = "ARMED & READY"
                    setTextColor(Color.parseColor("#30D158"))
                    textSize = 9.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val tvRange = android.widget.TextView(this).apply {
                    text = "Patriot PAC-3 • 100km"
                    setTextColor(Color.parseColor("#8E8E93"))
                    textSize = 8.5f
                }

                siloCard.addView(tvTitle)
                siloCard.addView(tvStatus)
                siloCard.addView(tvRange)
            } else {
                siloCard.setBackgroundResource(R.drawable.bg_slot_empty)

                val tvTitle = android.widget.TextView(this).apply {
                    text = "SILO #${i + 1} (EMPTY)"
                    setTextColor(Color.parseColor("#FF9F0A"))
                    textSize = 10.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val btnArm = android.widget.Button(this).apply {
                    text = "ARM ($150)"
                    textSize = 10f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#FF9F0A"))
                    setBackgroundResource(R.drawable.bg_secondary_button)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        (32 * resources.displayMetrics.density).toInt()
                    ).apply {
                        topMargin = (6 * resources.displayMetrics.density).toInt()
                    }
                    setOnClickListener {
                        ProcurementSheet(this@MainActivity, gameState, gameClient, s.id, "sam") {
                            renderSamSlots(s)
                        }.show()
                    }
                }

                siloCard.setOnClickListener {
                    ProcurementSheet(this@MainActivity, gameState, gameClient, s.id, "sam") {
                        renderSamSlots(s)
                    }.show()
                }

                siloCard.addView(tvTitle)
                siloCard.addView(btnArm)
            }

            binding.containerSamSlots.addView(siloCard)
        }
    }

    private fun renderMissileSiloSlots(s: Structure) {
        binding.containerSamSlots.removeAllViews()
        val maxSlots = s.maxMissileSlots()
        binding.tvSamHeader.text = "🚀 STRATEGIC CRUISE MISSILE SILOS (${s.missileMunitions}/$maxSlots ARMED)"

        for (i in 0 until maxSlots) {
            val siloCard = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val params = android.widget.LinearLayout.LayoutParams(
                    (135 * resources.displayMetrics.density).toInt(),
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (8 * resources.displayMetrics.density).toInt()
                }
                layoutParams = params
                setPadding(
                    (8 * resources.displayMetrics.density).toInt(),
                    (8 * resources.displayMetrics.density).toInt(),
                    (8 * resources.displayMetrics.density).toInt(),
                    (8 * resources.displayMetrics.density).toInt()
                )
            }

            if (i < s.missileMunitions) {
                siloCard.setBackgroundResource(R.drawable.bg_slot_occupied)

                val tvTitle = android.widget.TextView(this).apply {
                    text = "SILO #${i + 1} • BRAHMOS"
                    setTextColor(Color.WHITE)
                    textSize = 10.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val tvStatus = android.widget.TextView(this).apply {
                    text = "ARMED & READY"
                    setTextColor(Color.parseColor("#30D158"))
                    textSize = 9.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val tvRange = android.widget.TextView(this).apply {
                    text = "Range: 200km • Mach 0.85"
                    setTextColor(Color.parseColor("#8E8E93"))
                    textSize = 8.5f
                }
                val btnLaunch = android.widget.Button(this).apply {
                    text = "🎯 TARGET LAUNCH"
                    textSize = 9.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#070B14"))
                    setBackgroundResource(R.drawable.bg_accent_button)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        (32 * resources.displayMetrics.density).toInt()
                    ).apply {
                        topMargin = (6 * resources.displayMetrics.density).toInt()
                    }
                    setOnClickListener {
                        binding.cardStructureInspector.visibility = View.GONE
                        promptCruiseMissileTargeting(s.id)
                    }
                }

                siloCard.addView(tvTitle)
                siloCard.addView(tvStatus)
                siloCard.addView(tvRange)
                siloCard.addView(btnLaunch)
            } else {
                siloCard.setBackgroundResource(R.drawable.bg_slot_empty)

                val tvTitle = android.widget.TextView(this).apply {
                    text = "SILO #${i + 1} (EMPTY)"
                    setTextColor(Color.parseColor("#FF9F0A"))
                    textSize = 10.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val tvDesc = android.widget.TextView(this).apply {
                    text = "BrahMos-ER Cruise"
                    setTextColor(Color.parseColor("#8E8E93"))
                    textSize = 8.5f
                }
                val btnArm = android.widget.Button(this).apply {
                    text = "RELOAD ($850)"
                    textSize = 9.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#FF9F0A"))
                    setBackgroundResource(R.drawable.bg_secondary_button)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        (32 * resources.displayMetrics.density).toInt()
                    ).apply {
                        topMargin = (6 * resources.displayMetrics.density).toInt()
                    }
                    setOnClickListener {
                        ProcurementSheet(this@MainActivity, gameState, gameClient, s.id, "missile_silo") {
                            renderMissileSiloSlots(s)
                        }.show()
                    }
                }

                siloCard.setOnClickListener {
                    ProcurementSheet(this@MainActivity, gameState, gameClient, s.id, "missile_silo") {
                        renderMissileSiloSlots(s)
                    }.show()
                }

                siloCard.addView(tvTitle)
                siloCard.addView(tvDesc)
                siloCard.addView(btnArm)
            }

            binding.containerSamSlots.addView(siloCard)
        }
    }

    private fun showJetSortieControl(jetId: String) {
        val u = gameState.activeUnits.find { it.id == jetId } ?: return
        hideAllCards()
        activeSelectedJetId = jetId
        binding.cardJetSortieControl.visibility = View.VISIBLE
        // Offset latitude so aircraft stays centered nicely above the bottom control card
        binding.mapWebView.evaluateJavascript("window.panToCoordinates(${u.position.lat}, ${u.position.lng}, 16.5, true);", null)
        binding.mapWebView.evaluateJavascript("window.trackJet('${jetId}');", null)

        binding.tvJetControlTitle.text = "${u.name} (Sortie)"
        binding.tvJetCommanderCallsign.text = "PILOT: ${u.ownerCallsign.ifEmpty { "Commander" }}"
        val orderDesc = when (u.order) {
            "strike" -> "STRIKE MISSION • EN ROUTE"
            "patrol" -> "AIR COMBAT PATROL (CAP)"
            else -> "RETURNING TO AIRBASE (RTB)"
        }
        binding.tvJetControlStatus.text = orderDesc

        val fuel = u.fuelPercent.toInt()
        binding.tvJetFuelPercent.text = "$fuel%"
        binding.pbJetFuel.progress = fuel

        if (fuel <= 20) {
            binding.tvJetFuelPercent.setTextColor(getColor(R.color.accent_red))
            binding.pbJetFuel.progressTintList = ContextCompat.getColorStateList(this, R.color.accent_red)
        } else if (fuel <= 45) {
            binding.tvJetFuelPercent.setTextColor(getColor(R.color.accent_orange))
            binding.pbJetFuel.progressTintList = ContextCompat.getColorStateList(this, R.color.accent_orange)
        } else {
            binding.tvJetFuelPercent.setTextColor(getColor(R.color.accent_green))
            binding.pbJetFuel.progressTintList = ContextCompat.getColorStateList(this, R.color.accent_green)
        }

        binding.tvJetWeaponsLoadout.text = "Armament: ${u.weaponsLoadout}"

        // Radar Target Lock Status & Selection
        if (!u.lockedTargetUnitId.isNullOrEmpty()) {
            binding.btnSelectTarget.text = "🎯 LOCKED: ${u.lockedTargetName ?: "Target Aircraft"}"
            binding.btnClearTarget.visibility = View.VISIBLE
            binding.tvLockedTargetInfo.text = "Radar homing lock active • AIM-120D tracking target"
        } else {
            binding.btnSelectTarget.text = "🎯 SELECT TARGET TO LOCK"
            binding.btnClearTarget.visibility = View.GONE
            binding.tvLockedTargetInfo.text = "No target locked (Auto-homing or forward 100km projection)"
        }

        binding.btnSelectTarget.setOnClickListener {
            showTargetSelectionDialog(u)
        }

        binding.btnClearTarget.setOnClickListener {
            gameState.setJetLockedTarget(u.id, null)
            showJetSortieControl(u.id)
        }

        // Speed buttons styling
        if (u.speedMode == "supersonic") {
            binding.btnSpeedSupersonic.setBackgroundResource(R.drawable.bg_accent_button)
            binding.btnSpeedSupersonic.setTextColor(getColor(R.color.bg_tactical_dark))
            binding.btnSpeedSubsonic.setBackgroundResource(R.drawable.bg_secondary_button)
            binding.btnSpeedSubsonic.setTextColor(getColor(R.color.text_secondary))
        } else {
            binding.btnSpeedSubsonic.setBackgroundResource(R.drawable.bg_accent_button)
            binding.btnSpeedSubsonic.setTextColor(getColor(R.color.bg_tactical_dark))
            binding.btnSpeedSupersonic.setBackgroundResource(R.drawable.bg_secondary_button)
            binding.btnSpeedSupersonic.setTextColor(getColor(R.color.accent_orange))
        }

        updateRadarArcButtons(u.radarConeAngle)
        updateRoeButtons(u.roe)
    }

    private fun showTargetSelectionDialog(firerJet: UnitItem) {
        val targets = gameState.activeUnits.filter { it.id != firerJet.id }
        if (targets.isEmpty()) {
            Toast.makeText(this, "No other aircraft currently detected in airspace.", Toast.LENGTH_SHORT).show()
            return
        }

        val targetItems = targets.map { target ->
            val distKm = com.yuddhasphere.utils.GeoMath.calculateDistanceMeters(firerJet.position, target.position) / 1000.0
            val isEnemy = target.team != firerJet.team
            val tag = if (isEnemy) "[HOSTILE]" else "[FRIENDLY]"
            "$tag ${target.ownerCallsign} • ${target.name}\nDist: ${String.format(Locale.US, "%.1f", distKm)} km | HP: ${target.hp}/${target.maxHp}"
        }.toTypedArray()

        android.app.AlertDialog.Builder(this, R.style.Theme_YuddhaSphere)
            .setTitle("ACQUIRE RADAR TARGET LOCK")
            .setItems(targetItems) { _, which ->
                val chosenTarget = targets[which]
                gameState.setJetLockedTarget(firerJet.id, chosenTarget.id)
                showJetSortieControl(firerJet.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- 100% REAL LIVE GPS ONLY (No Sim Walker) ---

    private fun initLocationTracker() {
        locationTracker = LocationTracker(this) { pos, accuracy ->
            gameState.updatePlayerLocation(pos, accuracy)
            syncPlayerPositionToMap(autoRecenter = false)
            gameClient.sendLocation(pos, accuracy)
        }

        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (hasFine || hasCoarse) {
            locationTracker.startTracking()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    override fun onPause() {
        super.onPause()
        gameState.saveLocalWorld()
    }

    override fun onStop() {
        super.onStop()
        gameState.saveLocalWorld()
    }

    override fun onDestroy() {
        super.onDestroy()
        gameState.saveLocalWorld()
        locationTracker.stopTracking()
        gameClient.disconnect()
        gameState.removeListener(this)
    }

    // --- MapBridge Callbacks ---

    override fun onMapReady() {
        isMapReady = true
        syncPlayerPositionToMap(autoRecenter = true)
        syncWorldToMap()
        syncUnitsToMap()
    }

    override fun onBuildingClicked(structureId: String) {
        showStructureInspector(structureId)
    }

    override fun onJetClicked(jetId: String) {
        val u = gameState.activeUnits.find { it.id == jetId } ?: return
        val isOwnJet = (u.ownerId == gameState.player.id || u.team == gameState.player.team)
        if (isOwnJet) {
            showJetSortieControl(jetId)
        } else {
            showHostileTargetInspector(jetId)
        }
    }

    private fun showHostileTargetInspector(jetId: String) {
        val u = gameState.activeUnits.find { it.id == jetId } ?: return
        hideAllCards()
        binding.cardHostileTargetInspector.visibility = View.VISIBLE
        binding.mapWebView.evaluateJavascript("window.panToCoordinates(${u.position.lat}, ${u.position.lng}, 16.5, true);", null)

        binding.tvHostileTitle.text = "HOSTILE TARGET: ${u.name}"
        binding.tvHostileCallsign.text = "PILOT: ${u.ownerCallsign.ifEmpty { "[OPFOR] Hostile Pilot" }}"

        val distKm = com.yuddhasphere.utils.GeoMath.calculateDistanceMeters(gameState.player.position, u.position) / 1000.0
        binding.tvHostileRange.text = "RANGE: ${String.format("%.1f", distKm)} km"
        binding.tvHostileHp.text = "HP: ${u.hp} / ${u.maxHp}"
        binding.tvHostileSpeed.text = if (u.speedMode == "supersonic") "Mach 1.6" else "Mach 0.8"

        // Action 1: Surface-to-Air Missile Intercept
        binding.btnEngageSam.setOnClickListener {
            val res = gameState.fireSamAtTarget(u.id)
            if (res.first) {
                binding.cardHostileTargetInspector.visibility = View.GONE
            }
        }

        // Action 2: Air-to-Air 100km Fox-2 Missile Launch
        binding.btnEngageAirFox2.setOnClickListener {
            val friendlyFighter = gameState.activeUnits.find { it.ownerId == gameState.player.id }
            if (friendlyFighter != null) {
                val res = gameState.fireFox2AtTarget(friendlyFighter.id, u.id)
                if (res.first) {
                    binding.cardHostileTargetInspector.visibility = View.GONE
                }
            } else {
                // If no friendly jet in the air, check if airbase has ready fighter to scramble
                val base = gameState.structures.find { it.type == "airbase" && it.online }
                val readyJet = base?.hangarJets?.find { it.status == "Ready in Hangar" }
                if (base != null && readyJet != null) {
                    val scrambleRes = gameState.launchSortieFromHangar(base.id, readyJet.id, u.position, null, "strike")
                    if (scrambleRes.first) {
                        binding.cardHostileTargetInspector.visibility = View.GONE
                    }
                }
            }
        }
    }

    override fun onPlayerAvatarClicked() {
        showCommanderProfileCard()
    }

    override fun onMapClicked() {
        hideAllCards()
    }

    override fun onConfirmPlacement(type: String, lat: Double, lng: Double) {
        val result = gameState.buildStructure(type, GeoPoint(lat, lng))
        if (result.first) {
            cancelPlacementMode()
            syncWorldToMap()
            gameClient.sendBuildStructure(type, lat, lng)
        } else {
            Toast.makeText(this, result.second, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSortieTargetPicked(lat: Double, lng: Double, targetStructureId: String) {
        if (pendingTargetingCruiseSiloId != null) {
            val siloId = pendingTargetingCruiseSiloId!!
            cancelSortieTargetingMode()
            val result = gameState.fireCruiseMissile(siloId, GeoPoint(lat, lng))
            if (!result.first) {
                Toast.makeText(this, result.second, Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (isRetargetingMode) {
            val jetId = activeSelectedJetId ?: return
            isRetargetingMode = false
            cancelSortieTargetingMode()
            gameState.retargetUnitSortie(jetId, GeoPoint(lat, lng), if (targetStructureId.isNotEmpty()) targetStructureId else null)
            syncUnitsToMap()
            return
        }

        val airbaseId = pendingTargetingAirbaseId ?: return
        val hangarJetId = pendingTargetingHangarJetId ?: return
        cancelSortieTargetingMode()

        val target = GeoPoint(lat, lng)
        val targetStruct = if (targetStructureId.isNotEmpty()) targetStructureId else null
        val order = if (targetStruct != null) "strike" else "patrol"

        val result = gameState.launchSortieFromHangar(airbaseId, hangarJetId, target, targetStruct, order)
        if (result.first) {
            syncUnitsToMap()
        }
    }

    // --- LocalGameState Callbacks ---

    override fun onPlayerUpdated(player: Player) {
        binding.tvWealth.text = String.format("%,d", player.wealth)
        binding.tvIncomeRate.text = "+$${player.incomePerHour}/hr"
        binding.tvHp.text = "${player.hp}"
    }

    override fun onStructuresUpdated(structures: List<Structure>) {
        if (isMapReady) {
            syncWorldToMap()
        }
        // Update inspector card if visible
        activeInspectedStructureId?.let { id ->
            structures.find { it.id == id }?.let { s ->
                binding.tvInspectorHp.text = "${s.hp} / ${s.maxHp}"
                binding.pbInspectorHp.progress = s.hp
                if (s.type == "airbase") {
                    renderHangarSlots(s)
                } else if (s.type == "sam") {
                    renderSamSlots(s)
                } else if (s.type == "missile_silo") {
                    renderMissileSiloSlots(s)
                }
            }
        }
    }

    override fun onUnitsUpdated(units: List<UnitItem>) {
        if (isMapReady) {
            syncUnitsToMap()
        }
        // Smoothly update aircraft HUD in real time if selected
        activeSelectedJetId?.let { id ->
            val u = units.find { it.id == id }
            if (u != null) {
                val fuel = u.fuelPercent.toInt()
                binding.tvJetFuelPercent.text = "$fuel%"
                binding.pbJetFuel.progress = fuel
                val orderDesc = when (u.order) {
                    "strike" -> "STRIKE MISSION • EN ROUTE"
                    "patrol" -> "AIR COMBAT PATROL (CAP)"
                    else -> "RETURNING TO AIRBASE (RTB)"
                }
                binding.tvJetControlStatus.text = "🎥 TRACKING • $orderDesc"
            } else {
                activeSelectedJetId = null
                binding.cardJetSortieControl.visibility = View.GONE
            }
        }
    }

    override fun onMarketUpdated(assets: List<com.yuddhasphere.models.MarketAsset>, holdings: Map<String, com.yuddhasphere.models.PortfolioHolding>) {
        runOnUiThread {
            if (binding.overlayMarketScreen.visibility == View.VISIBLE) {
                updateMarketPortfolioHeader()
                if (activeMarketAssetId != null) {
                    val asset = gameState.marketAssets[activeMarketAssetId]
                    if (asset != null) {
                        binding.tvLiveAssetPrice.text = "$%,.2f".format(asset.currentPrice)
                        val isPos = asset.changePercent >= 0
                        binding.tvLiveAssetChange.text = "%s%.1f%%".format(if (isPos) "+" else "", asset.changePercent)
                        binding.tvLiveAssetChange.setTextColor(if (isPos) getColor(R.color.accent_green) else getColor(R.color.accent_orange))
                        binding.chartLiveAsset.addLiveTick(asset.currentPrice)
                        updateLiveTradePreview()
                    }
                }
                updateMarketListPrices()
            }
        }
    }

    override fun onAlert(title: String, message: String) {
        android.util.Log.d("YuddhaSphere", "Tactical Alert: $title - $message")
    }

    override fun onStructureUpgradeCompleted(structure: Structure) {
        syncWorldToMap()
    }

    override fun onMissileFired(
        missileId: String,
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double,
        missileType: String,
        targetUnitId: String?,
        targetName: String,
        speedMach: Double,
        hitProbability: Double
    ) {
        if (isMapReady) {
            val missileObj = JSONObject().apply {
                put("id", missileId)
                put("fromLat", fromLat)
                put("fromLng", fromLng)
                put("toLat", toLat)
                put("toLng", toLng)
                put("missileType", missileType)
                put("targetUnitId", targetUnitId ?: "")
                put("targetName", targetName)
                put("speedMach", speedMach)
                put("hitProbability", hitProbability)
            }
            val script = "window.launchActiveMissile(${missileObj}); window.trackMissile('${missileId}');"
            binding.mapWebView.evaluateJavascript(script, null)
        }
    }

    override fun onMissileClicked(
        missileId: String,
        missileType: String,
        targetName: String,
        speedMach: Double,
        etaSeconds: Double,
        hitProbability: Double,
        distKm: Double
    ) {
        showMissileTelemetry(missileId, missileType, targetName, speedMach, etaSeconds, hitProbability, distKm)
    }

    override fun onUnitShotDown(unitId: String, unitName: String) {
        gameState.destroyUnitInCombat(unitId, unitName)
    }

    private fun showMissileTelemetry(
        missileId: String,
        missileType: String,
        targetName: String,
        speedMach: Double,
        etaSeconds: Double,
        hitProbability: Double,
        distKm: Double
    ) {
        hideAllCards()
        binding.cardMissileTelemetry.visibility = View.VISIBLE
        val title = when (missileType) {
            "strategic_cruise" -> "BRAHMOS-ER CRUISE MISSILE"
            "sam_interceptor" -> "MIM-104 PATRIOT PAC-3"
            "air_to_ground" -> "GBU-12 LASER-GUIDED BOMB"
            else -> "AIM-120D AMRAAM (BVR)"
        }
        val status = when (missileType) {
            "strategic_cruise" -> "TERCOM / GPS STRATEGIC TRAJECTORY"
            "sam_interceptor" -> "SURFACE-TO-AIR ACTIVE RADAR GUIDANCE"
            "air_to_ground" -> "OPTICAL / LASER TERMINAL TRACKING"
            else -> "ACTIVE TERMINAL RADAR SEEKER LOCKED"
        }
        binding.tvMissileTitle.text = title
        binding.tvMissileStatus.text = status
        binding.tvMissileSpeed.text = "Mach ${String.format(Locale.US, "%.2f", speedMach)} (${(speedMach * 340 * 3.6).toInt()} km/h)"
        binding.tvMissileEta.text = "${String.format(Locale.US, "%.1f", etaSeconds)}s"
        binding.tvMissilePk.text = "${(hitProbability * 100).toInt()}%"
        binding.tvMissileTarget.text = "$targetName (Range: ${String.format(Locale.US, "%.1f", distKm)} km)"
    }

    // --- NetworkListener Callbacks ---

    override fun onConnectionStateChanged(connected: Boolean, statusText: String) {
        if (connected) {
            binding.tvServerSyncBadge.text = "● ONLINE (Live Sync)"
            binding.tvServerSyncBadge.setTextColor(Color.parseColor("#30D158"))
            binding.tvServerStatusBadge.text = "Status: ONLINE (Synced)"
            binding.tvServerStatusBadge.setTextColor(Color.parseColor("#30D158"))
        } else {
            binding.tvServerSyncBadge.text = "● AUTONOMOUS"
            binding.tvServerSyncBadge.setTextColor(Color.parseColor("#FF9F0A"))
            binding.tvServerStatusBadge.text = "Status: $statusText"
            binding.tvServerStatusBadge.setTextColor(Color.parseColor("#FF9F0A"))
        }
    }

    override fun onInitSyncReceived(player: Player, structures: List<Structure>) {
        gameState.applyServerSync(player, structures)
        syncWorldToMap()
        syncPlayerPositionToMap(autoRecenter = false)
    }

    override fun onStateDiffReceived(diffJson: JSONObject) {
        var worldChanged = false
        // 1. Updated structures from multiplayer sync
        if (diffJson.has("updatedStructures")) {
            val arr = diffJson.getJSONArray("updatedStructures")
            for (i in 0 until arr.length()) {
                val sObj = arr.getJSONObject(i)
                val id = sObj.getString("id")
                val posObj = sObj.getJSONObject("position")
                val s = Structure(
                    id = id,
                    ownerId = sObj.optString("ownerId", ""),
                    team = sObj.optString("team", "blue"),
                    teamColor = sObj.optString("teamColor", "#00E5FF"),
                    type = sObj.getString("type"),
                    name = sObj.optString("name", "Structure"),
                    level = sObj.optInt("level", 1),
                    position = GeoPoint(posObj.getDouble("lat"), posObj.getDouble("lng")),
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
                val idx = gameState.structures.indexOfFirst { it.id == id }
                if (idx != -1) {
                    if (s.hangarJets.isEmpty() && gameState.structures[idx].hangarJets.isNotEmpty()) {
                        s.hangarJets.addAll(gameState.structures[idx].hangarJets)
                    }
                    gameState.structures[idx] = s
                } else {
                    // Match against optimistic local placement at identical coords
                    val localMatchIdx = gameState.structures.indexOfFirst {
                        it.type == s.type &&
                        Math.abs(it.position.lat - s.position.lat) < 0.0001 &&
                        Math.abs(it.position.lng - s.position.lng) < 0.0001
                    }
                    if (localMatchIdx != -1) {
                        if (s.hangarJets.isEmpty() && gameState.structures[localMatchIdx].hangarJets.isNotEmpty()) {
                            s.hangarJets.addAll(gameState.structures[localMatchIdx].hangarJets)
                        }
                        gameState.structures[localMatchIdx] = s
                    } else {
                        gameState.structures.add(s)
                    }
                }
                worldChanged = true
            }
        }

        // 2. Removed structures
        if (diffJson.has("removedStructureIds")) {
            val arr = diffJson.getJSONArray("removedStructureIds")
            for (i in 0 until arr.length()) {
                val id = arr.getString(i)
                gameState.structures.removeAll { it.id == id }
                worldChanged = true
            }
        }

        // 3. Authoritative player stat updates from server
        if (diffJson.has("playerUpdate")) {
            val pUpdate = diffJson.getJSONObject("playerUpdate")
            if (pUpdate.has("wealth")) {
                gameState.player.wealth = pUpdate.getInt("wealth")
            }
            if (pUpdate.has("incomePerHour")) {
                gameState.player.incomePerHour = pUpdate.getInt("incomePerHour")
            }
            binding.tvWealth.text = String.format("%,d", gameState.player.wealth)
            binding.tvIncomeRate.text = "+$${gameState.player.incomePerHour}/h"
            gameState.saveLocalWorld()
        }

        if (worldChanged) {
            gameState.saveLocalWorld()
            syncWorldToMap()
            activeInspectedStructureId?.let { id ->
                val inspected = gameState.structures.find { it.id == id }
                if (inspected != null && binding.cardStructureInspector.visibility == View.VISIBLE) {
                    showStructureInspector(inspected.id)
                }
            }
        }
    }

    override fun onServerAlert(level: String, text: String) {
        android.util.Log.d("YuddhaSphere", "[Server] $text")
    }

    private fun syncPlayerPositionToMap(autoRecenter: Boolean = false) {
        if (!isMapReady) return
        val pos = gameState.player.position
        val acc = gameState.player.accuracy
        val callsign = gameState.player.callsign
        val script = "window.updatePlayerLocation(${pos.lat}, ${pos.lng}, $acc, $autoRecenter, '$callsign');"
        binding.mapWebView.evaluateJavascript(script, null)
    }

    private fun syncWorldToMap() {
        if (!isMapReady) return
        val json = gameState.getStructuresJson()
        val escaped = JSONObject.quote(json)
        val script = "window.renderWorldState($escaped);"
        binding.mapWebView.evaluateJavascript(script, null)
    }

    private fun syncUnitsToMap() {
        if (!isMapReady) return
        val json = gameState.getUnitsJson()
        val escaped = JSONObject.quote(json)
        val script = "window.renderUnits($escaped);"
        binding.mapWebView.evaluateJavascript(script, null)
    }

    // --- FULL-PAGE TRANSPARENT OVERLAYS: REPORTS & ALLIANCE ---

    private fun initOverlays() {
        // Reports Overlay Controls
        binding.btnCloseReports.setOnClickListener {
            binding.overlayReportsScreen.visibility = View.GONE
        }
        binding.btnBottomCancelReports.setOnClickListener {
            binding.overlayReportsScreen.visibility = View.GONE
        }
        binding.btnNewsFilterAll.setOnClickListener { renderNewsFeed("all") }
        binding.btnNewsFilterWar.setOnClickListener { renderNewsFeed("combat") }
        binding.btnNewsFilterRadar.setOnClickListener { renderNewsFeed("radar") }
        binding.btnNewsFilterEconomy.setOnClickListener { renderNewsFeed("economy") }

        // Alliance Overlay Controls
        binding.btnCloseAlliance.setOnClickListener {
            binding.overlayAllianceScreen.visibility = View.GONE
        }
        binding.btnBottomCancelAlliance.setOnClickListener {
            binding.overlayAllianceScreen.visibility = View.GONE
        }

        // Market Overlay Controls
        initMarketControls()
        binding.btnOpenCreateAlliance.setOnClickListener {
            binding.layoutCreateAllianceForm.visibility = View.VISIBLE
        }
        binding.btnCancelCreateAlliance.setOnClickListener {
            binding.layoutCreateAllianceForm.visibility = View.GONE
        }
        binding.btnConfirmCreateAlliance.setOnClickListener {
            val name = binding.etNewAllianceName.text.toString().trim()
            val tag = binding.etNewAllianceTag.text.toString().trim()
            val motto = binding.etNewAllianceMotto.text.toString().trim()
            if (name.isNotEmpty() && tag.isNotEmpty()) {
                val res = gameState.createAlliance(name, tag, motto, "#00E5FF")
                if (res.first) {
                    gameClient.sendCreateAlliance(name, tag, motto, "#00E5FF")
                    binding.layoutCreateAllianceForm.visibility = View.GONE
                    binding.etNewAllianceName.text.clear()
                    binding.etNewAllianceTag.text.clear()
                    binding.etNewAllianceMotto.text.clear()
                    renderAllianceScreen()
                    Toast.makeText(this, res.second, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, res.second, Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "Please enter an Alliance Name and Tag.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnLeaveAlliance.setOnClickListener {
            val res = gameState.leaveAlliance()
            if (res.first) {
                gameClient.sendLeaveAlliance()
                renderAllianceScreen()
            }
            Toast.makeText(this, res.second, Toast.LENGTH_SHORT).show()
        }

        binding.btnTax0.setOnClickListener { applyTaxRate(0) }
        binding.btnTax5.setOnClickListener { applyTaxRate(5) }
        binding.btnTax10.setOnClickListener { applyTaxRate(10) }
        binding.btnTax15.setOnClickListener { applyTaxRate(15) }
        binding.btnTax20.setOnClickListener { applyTaxRate(20) }
    }

    private fun showReportsScreen() {
        hideAllCards()
        binding.overlayReportsScreen.visibility = View.VISIBLE
        binding.tvNewsTicker.isSelected = true
        renderNewsFeed("all")
    }

    private fun renderNewsFeed(category: String) {
        val pairs = listOf(
            binding.btnNewsFilterAll to "all",
            binding.btnNewsFilterWar to "combat",
            binding.btnNewsFilterRadar to "radar",
            binding.btnNewsFilterEconomy to "economy"
        )
        pairs.forEach { (btn, cat) ->
            if (cat == category) {
                btn.setBackgroundResource(R.drawable.bg_accent_button)
                btn.setTextColor(getColor(R.color.bg_tactical_dark))
            } else {
                btn.setBackgroundResource(R.drawable.bg_secondary_button)
                btn.setTextColor(getColor(R.color.white))
            }
        }

        val allReports = gameState.combatReports
        val filtered = if (category == "all") allReports else allReports.filter { it.category == category }

        // Featured Breaking News banner
        if (filtered.isNotEmpty()) {
            val topReport = filtered.first()
            binding.cardFeaturedNews.visibility = View.VISIBLE
            binding.tvFeaturedUrgencyTag.text = topReport.urgencyTag
            binding.tvFeaturedHeadline.text = topReport.title
            binding.tvFeaturedBody.text = topReport.detail
            val minsAgo = ((System.currentTimeMillis() - topReport.timestamp) / 60000).coerceAtLeast(1)
            binding.tvFeaturedTime.text = "$minsAgo MINS AGO • ${topReport.sector}"
        } else {
            binding.cardFeaturedNews.visibility = View.GONE
        }

        // Ticker update
        val tickerItems = allReports.take(4).map { "${it.urgencyTag}: ${it.title}" }
        if (tickerItems.isNotEmpty()) {
            binding.tvNewsTicker.text = tickerItems.joinToString(" • ")
        }

        // Populate news items container
        binding.containerNewsFeed.removeAllViews()
        val itemsToRender = if (filtered.size > 1) filtered.subList(1, filtered.size) else emptyList()

        if (itemsToRender.isEmpty() && filtered.isEmpty()) {
            val emptyTv = android.widget.TextView(this).apply {
                text = "No active broadcasts in this frequency sector."
                setTextColor(Color.parseColor("#8E8E93"))
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding(0, (24 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            binding.containerNewsFeed.addView(emptyTv)
            return
        }

        itemsToRender.forEach { r ->
            val card = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_pill_stat)
                setPadding(
                    (12 * resources.displayMetrics.density).toInt(),
                    (10 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt(),
                    (10 * resources.displayMetrics.density).toInt()
                )
                val params = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (8 * resources.displayMetrics.density).toInt()
                }
                layoutParams = params
            }

            // Top row with urgency badge + timestamp
            val topRow = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val badge = android.widget.TextView(this).apply {
                text = r.urgencyTag
                setTextColor(when (r.level) {
                    "danger" -> Color.parseColor("#FF453A")
                    "warn" -> Color.parseColor("#FF9F0A")
                    else -> Color.parseColor("#00E5FF")
                })
                textSize = 9.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val elapsedMins = ((System.currentTimeMillis() - r.timestamp) / 60000).coerceAtLeast(1)
            val timeTv = android.widget.TextView(this).apply {
                text = "$elapsedMins MINS AGO • ${r.sector}"
                setTextColor(Color.parseColor("#8E8E93"))
                textSize = 9.5f
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginStart = (8 * resources.displayMetrics.density).toInt()
                }
            }

            topRow.addView(badge)
            topRow.addView(timeTv)

            val headline = android.widget.TextView(this).apply {
                text = r.title
                setTextColor(Color.WHITE)
                textSize = 12.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                val p = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (4 * resources.displayMetrics.density).toInt()
                }
                layoutParams = p
            }

            val body = android.widget.TextView(this).apply {
                text = r.detail
                setTextColor(Color.parseColor("#C7C7CC"))
                textSize = 11f
                val p = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (4 * resources.displayMetrics.density).toInt()
                }
                layoutParams = p
            }

            card.addView(topRow)
            card.addView(headline)
            card.addView(body)
            binding.containerNewsFeed.addView(card)
        }
    }

    private fun showAllianceScreen() {
        hideAllCards()
        binding.overlayAllianceScreen.visibility = View.VISIBLE
        binding.layoutCreateAllianceForm.visibility = View.GONE
        renderAllianceScreen()
    }

    private fun applyTaxRate(rate: Int) {
        val result = gameState.setTaxRate(rate)
        if (result.first) {
            gameClient.sendSetTaxRate(rate)
            renderAllianceScreen()
        } else {
            Toast.makeText(this, result.second, Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderAllianceScreen() {
        val myAllianceId = gameState.player.allianceId
        val myAll = if (myAllianceId != null) gameState.alliances.find { it.id == myAllianceId } else null

        if (myAll == null) {
            binding.viewNoAllianceState.visibility = View.VISIBLE
            binding.viewActiveAllianceDashboard.visibility = View.GONE
        } else {
            binding.viewNoAllianceState.visibility = View.GONE
            binding.viewActiveAllianceDashboard.visibility = View.VISIBLE

            binding.tvMyAllianceName.text = "${myAll.name} ${myAll.tag}"
            val sdf = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.US)
            binding.tvMyAllianceFounded.text = "Founded: ${sdf.format(java.util.Date(myAll.createdAt))} • Leader: ${myAll.leaderCallsign}"

            val role = gameState.player.allianceRole
            binding.tvMyRoleBadge.text = when (role) {
                "Supreme Commander" -> "👑 SUPREME COMMANDER"
                "Financial Minister" -> "💼 FINANCIAL MINISTER"
                "Foreign Minister" -> "🌐 FOREIGN MINISTER"
                else -> "⚔ MEMBER"
            }

            binding.tvAllianceTreasury.text = "$%,d".format(myAll.treasuryCredits)
            binding.tvAllianceTaxRate.text = "${myAll.taxRatePercent}% Duties"
            binding.tvAllianceRosterCount.text = "${myAll.members.size} Member${if (myAll.members.size > 1) "s" else ""}"

            // Highlight tax button corresponding to current taxRatePercent
            val taxPairs = listOf(
                binding.btnTax0 to 0,
                binding.btnTax5 to 5,
                binding.btnTax10 to 10,
                binding.btnTax15 to 15,
                binding.btnTax20 to 20
            )
            taxPairs.forEach { (btn, rate) ->
                if (rate == myAll.taxRatePercent) {
                    btn.setBackgroundResource(R.drawable.bg_accent_button)
                    btn.setTextColor(getColor(R.color.bg_tactical_dark))
                } else {
                    btn.setBackgroundResource(R.drawable.bg_secondary_button)
                    btn.setTextColor(getColor(R.color.white))
                }
                btn.isEnabled = (role == "Supreme Commander" || role == "Financial Minister")
            }

            // Render members roster
            binding.containerMyAllianceMembers.removeAllViews()
            myAll.members.forEach { m ->
                val memberCard = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.bg_pill_stat)
                    setPadding(
                        (10 * resources.displayMetrics.density).toInt(),
                        (8 * resources.displayMetrics.density).toInt(),
                        (10 * resources.displayMetrics.density).toInt(),
                        (8 * resources.displayMetrics.density).toInt()
                    )
                    val p = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (6 * resources.displayMetrics.density).toInt()
                    }
                    layoutParams = p
                }

                val memberInfo = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }

                val tvName = android.widget.TextView(this).apply {
                    text = "${m.callsign} ${if (m.playerId == gameState.player.id) "(You)" else ""}"
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val tvPost = android.widget.TextView(this).apply {
                    text = "Post: ${m.role} • Net Worth: $%,d".format(m.netWorth)
                    setTextColor(Color.parseColor("#00E5FF"))
                    textSize = 10f
                }
                memberInfo.addView(tvName)
                memberInfo.addView(tvPost)
                memberCard.addView(memberInfo)

                // If user is Supreme Commander and member is not self, allow promoting!
                if (role == "Supreme Commander" && m.playerId != gameState.player.id) {
                    val btnPromote = android.widget.Button(this).apply {
                        text = "PROMOTE"
                        textSize = 9.5f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setBackgroundResource(R.drawable.bg_secondary_button)
                        setTextColor(getColor(R.color.accent_orange))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            (80 * resources.displayMetrics.density).toInt(),
                            (30 * resources.displayMetrics.density).toInt()
                        )
                        setOnClickListener {
                            val options = arrayOf("Financial Minister", "Foreign Minister", "Member")
                            android.app.AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle("Appoint Post for ${m.callsign}")
                                .setItems(options) { _, which ->
                                    val selectedRole = options[which]
                                    val res = gameState.promoteMember(m.playerId, selectedRole)
                                    if (res.first) {
                                        gameClient.sendSetMemberRole(m.playerId, selectedRole)
                                        renderAllianceScreen()
                                    }
                                    Toast.makeText(this@MainActivity, res.second, Toast.LENGTH_SHORT).show()
                                }
                                .show()
                        }
                    }
                    memberCard.addView(btnPromote)
                }

                binding.containerMyAllianceMembers.addView(memberCard)
            }
        }

        // Global Alliances List (other alliances)
        binding.containerGlobalAlliances.removeAllViews()
        val otherAlliances = gameState.alliances.filter { it.id != myAllianceId }

        if (otherAlliances.isEmpty()) {
            val emptyCard = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.bg_pill_stat)
                setPadding(
                    (16 * resources.displayMetrics.density).toInt(),
                    (18 * resources.displayMetrics.density).toInt(),
                    (16 * resources.displayMetrics.density).toInt(),
                    (18 * resources.displayMetrics.density).toInt()
                )
            }
            val emptyTitle = android.widget.TextView(this).apply {
                text = "No other active alliances formed yet."
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val emptySub = android.widget.TextView(this).apply {
                text = "Be the pioneer to establish global factions or recruit allies."
                setTextColor(Color.parseColor("#8E8E93"))
                textSize = 10.5f
            }
            emptyCard.addView(emptyTitle)
            emptyCard.addView(emptySub)
            binding.containerGlobalAlliances.addView(emptyCard)
        } else {
            val sdf = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US)
            otherAlliances.forEach { all ->
                val card = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.bg_glass_card)
                    setPadding(
                        (14 * resources.displayMetrics.density).toInt(),
                        (12 * resources.displayMetrics.density).toInt(),
                        (14 * resources.displayMetrics.density).toInt(),
                        (12 * resources.displayMetrics.density).toInt()
                    )
                    val p = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (8 * resources.displayMetrics.density).toInt()
                    }
                    layoutParams = p
                }

                val titleRow = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }

                val nameTv = android.widget.TextView(this).apply {
                    text = "${all.name} ${all.tag}"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }

                val wealthTv = android.widget.TextView(this).apply {
                    text = "$%,d Net Worth".format(all.totalWealth)
                    setTextColor(Color.parseColor("#30D158"))
                    textSize = 11f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }

                titleRow.addView(nameTv)
                titleRow.addView(wealthTv)

                val detailsTv = android.widget.TextView(this).apply {
                    text = "Founded: ${sdf.format(java.util.Date(all.createdAt))} • Leader: ${all.leaderCallsign} • ${all.members.size} Members"
                    setTextColor(Color.parseColor("#8E8E93"))
                    textSize = 10.5f
                }

                val mottoTv = android.widget.TextView(this).apply {
                    text = "\"${all.motto}\""
                    setTextColor(Color.parseColor("#C7C7CC"))
                    textSize = 11f
                    val lp = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (4 * resources.displayMetrics.density).toInt()
                    }
                    layoutParams = lp
                }

                // Actions: Enlist, Declare War, Sign Pact
                val actionRow = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    val lp = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (8 * resources.displayMetrics.density).toInt()
                    }
                    layoutParams = lp
                }

                val btnEnlist = android.widget.Button(this).apply {
                    text = "ENLIST / JOIN"
                    textSize = 10f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setBackgroundResource(R.drawable.bg_accent_button)
                    setTextColor(getColor(R.color.bg_tactical_dark))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0,
                        (34 * resources.displayMetrics.density).toInt(),
                        1f
                    )
                    setOnClickListener {
                        val res = gameState.joinAlliance(all.id)
                        if (res.first) {
                            gameClient.sendJoinAlliance(all.id)
                            renderAllianceScreen()
                        }
                        Toast.makeText(this@MainActivity, res.second, Toast.LENGTH_SHORT).show()
                    }
                }
                actionRow.addView(btnEnlist)

                // If user is in an alliance and has foreign authority, offer treaty options
                val userRole = gameState.player.allianceRole
                if (myAll != null && (userRole == "Supreme Commander" || userRole == "Foreign Minister")) {
                    val btnWar = android.widget.Button(this).apply {
                        text = "⚔ WAR"
                        textSize = 10f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setBackgroundResource(R.drawable.bg_danger_button)
                        setTextColor(getColor(R.color.accent_red))
                        val lp = android.widget.LinearLayout.LayoutParams(
                            (64 * resources.displayMetrics.density).toInt(),
                            (34 * resources.displayMetrics.density).toInt()
                        ).apply {
                            marginStart = (6 * resources.displayMetrics.density).toInt()
                        }
                        layoutParams = lp
                        setOnClickListener {
                            val res = gameState.setTreatyStatus(all.id, "war")
                            if (res.first) {
                                gameClient.sendProposeTreaty(all.id, "war")
                            }
                            Toast.makeText(this@MainActivity, res.second, Toast.LENGTH_SHORT).show()
                        }
                    }

                    val btnPact = android.widget.Button(this).apply {
                        text = "🤝 PACT"
                        textSize = 10f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setBackgroundResource(R.drawable.bg_secondary_button)
                        setTextColor(getColor(R.color.accent_green))
                        val lp = android.widget.LinearLayout.LayoutParams(
                            (64 * resources.displayMetrics.density).toInt(),
                            (34 * resources.displayMetrics.density).toInt()
                        ).apply {
                            marginStart = (6 * resources.displayMetrics.density).toInt()
                        }
                        layoutParams = lp
                        setOnClickListener {
                            val res = gameState.setTreatyStatus(all.id, "pact")
                            if (res.first) {
                                gameClient.sendProposeTreaty(all.id, "pact")
                            }
                            Toast.makeText(this@MainActivity, res.second, Toast.LENGTH_SHORT).show()
                        }
                    }

                    actionRow.addView(btnWar)
                    actionRow.addView(btnPact)
                }

                card.addView(titleRow)
                card.addView(detailsTv)
                card.addView(mottoTv)
                card.addView(actionRow)
                binding.containerGlobalAlliances.addView(card)
            }
        }
    }

    // =========================================================================
    // GTX GLOBAL TACTICAL EXCHANGE (MARKET) ENGINE
    // =========================================================================
    private var selectedMarketCategory: String = "all"
    private var activeMarketAssetId: String? = null
    private var isTradeBuyMode: Boolean = true
    private var selectedTradeQuantity: Int = 1

    private fun initMarketControls() {
        binding.btnCloseMarket.setOnClickListener {
            binding.overlayMarketScreen.visibility = View.GONE
        }
        binding.btnBottomCancelMarket.setOnClickListener {
            binding.overlayMarketScreen.visibility = View.GONE
        }
        binding.btnCloseLiveDetail.setOnClickListener {
            binding.layoutLiveAssetDetailCard.visibility = View.GONE
            activeMarketAssetId = null
        }

        binding.btnMarketFilterAll.setOnClickListener { setMarketFilter("all") }
        binding.btnMarketFilterCommodities.setOnClickListener { setMarketFilter("commodity") }
        binding.btnMarketFilterEquities.setOnClickListener { setMarketFilter("company") }
        binding.btnMarketFilterPortfolio.setOnClickListener { setMarketFilter("portfolio") }

        binding.btnLiveModeBuy.setOnClickListener { setTradeMode(true) }
        binding.btnLiveModeSell.setOnClickListener { setTradeMode(false) }

        binding.btnLiveQty1.setOnClickListener { setTradeQuantity(1) }
        binding.btnLiveQty5.setOnClickListener { setTradeQuantity(5) }
        binding.btnLiveQty10.setOnClickListener { setTradeQuantity(10) }
        binding.btnLiveQty50.setOnClickListener { setTradeQuantity(50) }
        binding.btnLiveQtyMax.setOnClickListener { setTradeQuantityMax() }

        binding.btnExecuteLiveTrade.setOnClickListener { executeMarketTrade() }
    }

    private fun showMarketScreen() {
        hideAllCards()
        binding.overlayMarketScreen.visibility = View.VISIBLE
        binding.tvMarketNewsTickerOverlay.isSelected = true
        if (gameState.marketAssets.isEmpty()) {
            gameState.seedDefaultMarketAssets()
        }
        renderMarketScreen()
    }

    private fun setMarketFilter(category: String) {
        selectedMarketCategory = category
        val tabs = listOf(
            binding.btnMarketFilterAll to "all",
            binding.btnMarketFilterCommodities to "commodity",
            binding.btnMarketFilterEquities to "company",
            binding.btnMarketFilterPortfolio to "portfolio"
        )
        tabs.forEach { (btn, cat) ->
            if (cat == category) {
                btn.setBackgroundResource(R.drawable.bg_accent_button)
                btn.setTextColor(getColor(R.color.bg_tactical_dark))
            } else {
                btn.setBackgroundResource(R.drawable.bg_secondary_button)
                btn.setTextColor(getColor(R.color.white))
            }
        }
        renderMarketList()
    }

    private fun renderMarketScreen() {
        setMarketFilter(selectedMarketCategory)
        updateMarketPortfolioHeader()
        renderMarketNewsTicker()
    }

    private fun updateMarketPortfolioHeader() {
        val holdingsValue = gameState.getPortfolioValue()
        val totalInvested = gameState.player.holdings.values.sumOf { it.totalInvested }
        val pnl = holdingsValue - totalInvested
        val pnlPercent = if (totalInvested > 0.0) (pnl / totalInvested) * 100.0 else 0.0

        binding.tvMarketPortfolioWorth.text = "$%,.2f".format(holdingsValue)
        val pnlSign = if (pnl >= 0.0) "+" else "-"
        binding.tvMarketPortfolioPnL.text = "%s$%,.2f (%+.1f%%)".format(pnlSign, Math.abs(pnl), pnlPercent)
        if (pnl >= 0.0) {
            binding.tvMarketPortfolioPnL.setTextColor(getColor(R.color.accent_green))
        } else {
            binding.tvMarketPortfolioPnL.setTextColor(getColor(R.color.accent_orange))
        }

        binding.tvMarketCashBalance.text = "Cash: $%,d".format(gameState.player.wealth)
        binding.tvMarketInvestedCapital.text = "Invested: $%,.2f".format(totalInvested)
    }

    private fun renderMarketNewsTicker() {
        if (gameState.marketNews.isNotEmpty()) {
            val headlineString = gameState.marketNews.take(6).joinToString("   ★   ") { it.headline }
            binding.tvMarketNewsTickerOverlay.text = headlineString
        }
    }

    private fun renderMarketList() {
        binding.containerMarketList.removeAllViews()
        val allAssets = gameState.marketAssets.values.toList()

        val filtered = when (selectedMarketCategory) {
            "commodity" -> allAssets.filter { it.type == "commodity" }
            "company" -> allAssets.filter { it.type == "company" }
            "portfolio" -> allAssets.filter { gameState.player.holdings.containsKey(it.id) }
            else -> allAssets
        }

        if (filtered.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = if (selectedMarketCategory == "portfolio") "No active stock or commodity positions held.\nBrowse Commodities or Equities to trade." else "No market assets available."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding(0, (24 * resources.displayMetrics.density).toInt(), 0, (24 * resources.displayMetrics.density).toInt())
            }
            binding.containerMarketList.addView(tvEmpty)
            return
        }

        filtered.forEach { asset ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * resources.displayMetrics.density).toInt()
                }
                setBackgroundResource(R.drawable.bg_glass_hud)
                setPadding(
                    (12 * resources.displayMetrics.density).toInt(),
                    (10 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt(),
                    (10 * resources.displayMetrics.density).toInt()
                )
                gravity = android.view.Gravity.CENTER_VERTICAL
                tag = asset.id
            }

            val tvBadge = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (42 * resources.displayMetrics.density).toInt(),
                    (42 * resources.displayMetrics.density).toInt()
                )
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.bg_secondary_button)
                text = asset.symbol
                setTextColor(getColor(R.color.accent_cyan))
                textSize = 11.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val infoLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginStart = (10 * resources.displayMetrics.density).toInt()
                }
            }

            val tvName = TextView(this).apply {
                text = asset.name
                setTextColor(Color.WHITE)
                textSize = 13.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val holding = gameState.player.holdings[asset.id]
            val tvSubtitle = TextView(this).apply {
                text = if (holding != null && holding.quantity > 0) {
                    "${asset.category} • Owned: ${holding.quantity} ($%,.2f)".format(holding.quantity * asset.currentPrice)
                } else {
                    "${asset.category} • ${asset.type.capitalize(Locale.ROOT)}"
                }
                setTextColor(if (holding != null && holding.quantity > 0) getColor(R.color.accent_green) else getColor(R.color.text_secondary))
                textSize = 10f
            }

            infoLayout.addView(tvName)
            infoLayout.addView(tvSubtitle)

            val priceLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.END
            }

            val tvPrice = TextView(this).apply {
                text = "$%,.2f".format(asset.currentPrice)
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                tag = "price_${asset.id}"
            }

            val isPos = asset.changePercent >= 0
            val tvChange = TextView(this).apply {
                text = "%s%.1f%%".format(if (isPos) "+" else "", asset.changePercent)
                setTextColor(if (isPos) getColor(R.color.accent_green) else getColor(R.color.accent_orange))
                textSize = 10.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                tag = "change_${asset.id}"
            }

            priceLayout.addView(tvPrice)
            priceLayout.addView(tvChange)

            card.addView(tvBadge)
            card.addView(infoLayout)
            card.addView(priceLayout)

            card.setOnClickListener {
                openLiveAssetDetail(asset.id)
            }

            binding.containerMarketList.addView(card)
        }
    }

    private fun openLiveAssetDetail(assetId: String) {
        val asset = gameState.marketAssets[assetId] ?: return
        activeMarketAssetId = asset.id
        binding.layoutLiveAssetDetailCard.visibility = View.VISIBLE

        binding.tvLiveAssetSymbolBadge.text = asset.symbol
        binding.tvLiveAssetName.text = asset.name
        binding.tvLiveAssetCategory.text = "${asset.category} • ${asset.description}"
        binding.tvLiveAssetPrice.text = "$%,.2f".format(asset.currentPrice)

        val isPos = asset.changePercent >= 0
        binding.tvLiveAssetChange.text = "%s%.1f%%".format(if (isPos) "+" else "", asset.changePercent)
        binding.tvLiveAssetChange.setTextColor(if (isPos) getColor(R.color.accent_green) else getColor(R.color.accent_orange))

        binding.chartLiveAsset.setAsset(asset)

        setTradeMode(true)
        setTradeQuantity(1)
        updateLiveTradePreview()
    }

    private fun setTradeMode(isBuy: Boolean) {
        isTradeBuyMode = isBuy
        if (isBuy) {
            binding.btnLiveModeBuy.setBackgroundResource(R.drawable.bg_accent_button)
            binding.btnLiveModeBuy.setTextColor(getColor(R.color.bg_tactical_dark))
            binding.btnLiveModeSell.setBackgroundResource(R.drawable.bg_secondary_button)
            binding.btnLiveModeSell.setTextColor(getColor(R.color.accent_orange))
            binding.btnExecuteLiveTrade.text = "CONFIRM BUY"
            binding.btnExecuteLiveTrade.setBackgroundResource(R.drawable.bg_accent_button)
            binding.btnExecuteLiveTrade.setTextColor(getColor(R.color.bg_tactical_dark))
        } else {
            binding.btnLiveModeBuy.setBackgroundResource(R.drawable.bg_secondary_button)
            binding.btnLiveModeBuy.setTextColor(getColor(R.color.accent_cyan))
            binding.btnLiveModeSell.setBackgroundResource(R.drawable.bg_danger_button)
            binding.btnLiveModeSell.setTextColor(getColor(R.color.white))
            binding.btnExecuteLiveTrade.text = "CONFIRM SELL"
            binding.btnExecuteLiveTrade.setBackgroundResource(R.drawable.bg_danger_button)
            binding.btnExecuteLiveTrade.setTextColor(getColor(R.color.white))
        }
        updateLiveTradePreview()
    }

    private fun setTradeQuantity(qty: Int) {
        selectedTradeQuantity = qty.coerceAtLeast(1)
        updateLiveTradePreview()
    }

    private fun setTradeQuantityMax() {
        val asset = activeMarketAssetId?.let { gameState.marketAssets[it] } ?: return
        if (isTradeBuyMode) {
            val maxCanAfford = (gameState.player.wealth / asset.currentPrice).toInt().coerceAtLeast(1)
            selectedTradeQuantity = maxCanAfford
        } else {
            val owned = gameState.player.holdings[asset.id]?.quantity ?: 0
            selectedTradeQuantity = owned.coerceAtLeast(1)
        }
        updateLiveTradePreview()
    }

    private fun updateLiveTradePreview() {
        val asset = activeMarketAssetId?.let { gameState.marketAssets[it] } ?: return
        val holding = gameState.player.holdings[asset.id]
        val ownedQty = holding?.quantity ?: 0
        val avgPrice = holding?.avgCostBasis ?: 0.0
        val positionVal = ownedQty * asset.currentPrice
        val posPnL = positionVal - (holding?.totalInvested ?: 0.0)

        binding.tvLiveAssetHoldingInfo.text = "Owned: $ownedQty shares • Position: $%,.2f • P&L: %s$%,.2f".format(
            positionVal,
            if (posPnL >= 0.0) "+" else "-",
            Math.abs(posPnL)
        )

        val totalEst = asset.currentPrice * selectedTradeQuantity
        if (isTradeBuyMode) {
            val balanceAfter = (gameState.player.wealth - totalEst).toInt()
            binding.tvLiveTradeCostPreview.text = "Total Cost: $%,.2f (%d @ $%,.2f)".format(totalEst, selectedTradeQuantity, asset.currentPrice)
            binding.tvLiveCashAfterPreview.text = "Cash After: $%,d".format(balanceAfter)
            if (balanceAfter < 0) {
                binding.tvLiveCashAfterPreview.setTextColor(getColor(R.color.accent_red))
            } else {
                binding.tvLiveCashAfterPreview.setTextColor(getColor(R.color.text_secondary))
            }
        } else {
            val balanceAfter = (gameState.player.wealth + totalEst).toInt()
            binding.tvLiveTradeCostPreview.text = "Proceeds: $%,.2f (%d @ $%,.2f)".format(totalEst, selectedTradeQuantity, asset.currentPrice)
            binding.tvLiveCashAfterPreview.text = "Cash After: $%,d".format(balanceAfter)
            binding.tvLiveCashAfterPreview.setTextColor(getColor(R.color.text_secondary))
        }
    }

    private fun executeMarketTrade() {
        val assetId = activeMarketAssetId ?: return
        val res = if (isTradeBuyMode) {
            gameState.executeMarketBuy(assetId, selectedTradeQuantity)
        } else {
            gameState.executeMarketSell(assetId, selectedTradeQuantity)
        }

        if (res.first) {
            gameClient.sendMarketOrder(assetId, isTradeBuyMode, selectedTradeQuantity)
            Toast.makeText(this, res.second, Toast.LENGTH_SHORT).show()
            updateMarketPortfolioHeader()
            updateLiveTradePreview()
            renderMarketList()
        } else {
            Toast.makeText(this, res.second, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateMarketListPrices() {
        gameState.marketAssets.values.forEach { asset ->
            val tvPrice = binding.containerMarketList.findViewWithTag<TextView>("price_${asset.id}")
            val tvChange = binding.containerMarketList.findViewWithTag<TextView>("change_${asset.id}")
            if (tvPrice != null) {
                tvPrice.text = "$%,.2f".format(asset.currentPrice)
            }
            if (tvChange != null) {
                val isPos = asset.changePercent >= 0
                tvChange.text = "%s%.1f%%".format(if (isPos) "+" else "", asset.changePercent)
                tvChange.setTextColor(if (isPos) getColor(R.color.accent_green) else getColor(R.color.accent_orange))
            }
        }
    }

}
