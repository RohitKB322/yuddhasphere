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
import com.yuddhasphere.ui.MilitarySheet
import com.yuddhasphere.ui.UnderProgressDialog
import org.json.JSONObject

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

        gameState = LocalGameState(this)
        loadSavedCommanderProfile()
        gameState.addListener(this)

        val savedHost = prefs.getString("server_host", "yuddhasphere-server.onrender.com") ?: "yuddhasphere-server.onrender.com"
        val savedPort = prefs.getString("server_port", "443") ?: "443"
        val initialUrl = buildServerUrl(savedHost, savedPort)
        gameClient = GameClient(initialUrl, this)
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

        // Bottom Nav Item 3: Alliance (Full Screen Overlay)
        binding.navAlliance.setOnClickListener {
            hideAllCards()
            showAllianceScreen()
        }

        // Bottom Nav Item 4: Reports (24/7 Breaking War News Wire Overlay)
        binding.navReports.setOnClickListener {
            hideAllCards()
            showReportsScreen()
        }

        // Bottom Nav Item 5: Menu (Settings & Server Nexus)
        binding.navMenu.setOnClickListener {
            showCommanderRegistration()
        }

        // Recenter / Locate Me Button (in Top HUD)
        binding.btnRecenterGps.setOnClickListener {
            binding.mapWebView.evaluateJavascript("window.recenterMap();", null)
            Toast.makeText(this, "Camera centered on Commander", Toast.LENGTH_SHORT).show()
        }

        // Commander HP / Avatar in Top HUD -> opens Commander Profile
        binding.btnHpDetails.setOnClickListener {
            showCommanderProfileCard()
        }

        // Alerts Button
        binding.btnAlerts.setOnClickListener {
            Toast.makeText(this, "Airspace Status: Clear • All Defense Batteries Active", Toast.LENGTH_LONG).show()
        }

        // Cancel Sortie Button
        binding.btnCancelSortie.setOnClickListener {
            cancelSortieTargetingMode()
        }
    }

    private fun showMilitarySheet() {
        MilitarySheet(this, gameState) { selectedJetOrMunitionId ->
            if (selectedJetOrMunitionId.startsWith("jet_")) {
                // Find Airbase
                val airbase = gameState.structures.find { it.type == "airbase" && it.online }
                    ?: gameState.structures.find { it.type == "town_hall" }

                if (airbase != null) {
                    // Purchase into hangar if not yet purchased
                    val buyResult = gameState.buyJetForAirbase(airbase.id, selectedJetOrMunitionId)
                    if (buyResult.first) {
                        val newJet = airbase.hangarJets.last()
                        promptSortieTargeting(airbase.id, newJet.id, newJet.name)
                    } else {
                        // If hangar already has this jet ready, scramble it
                        val readyJet = airbase.hangarJets.find { it.catalogId == selectedJetOrMunitionId && it.status == "Ready in Hangar" }
                        if (readyJet != null) {
                            promptSortieTargeting(airbase.id, readyJet.id, readyJet.name)
                        } else {
                            Toast.makeText(this, buyResult.second, Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    Toast.makeText(this, "Build an Airbase first to store and launch fighter jets!", Toast.LENGTH_LONG).show()
                }
            } else {
                // Interceptor purchase
                val sam = gameState.structures.find { it.type == "sam" && it.online }
                if (sam != null) {
                    gameState.buySamMunitions(sam.id)
                } else {
                    Toast.makeText(this, "Build a SAM site first to equip interceptors!", Toast.LENGTH_SHORT).show()
                }
            }
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

    private fun cancelSortieTargetingMode() {
        pendingTargetingAirbaseId = null
        pendingTargetingHangarJetId = null
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
            Toast.makeText(this, "Target aligned to Commander GPS", Toast.LENGTH_SHORT).show()
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
            binding.mapWebView.evaluateJavascript("window.deselectAll();", null)
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

        // In-Flight Jet: Fox-2 Missile Manual Launch
        binding.btnLaunchFoxTwo.setOnClickListener {
            val jetId = activeSelectedJetId ?: return@setOnClickListener
            gameState.fireManualMissile(jetId)
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
                gameClient.connect(srvUrl)
                gameClient.sendRegister("device_01", callsign)
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
        binding.cardStructureInspector.visibility = View.GONE
        binding.cardJetSortieControl.visibility = View.GONE
        binding.cardCommanderSetup.visibility = View.GONE
        binding.cardCommanderProfile.visibility = View.GONE
        binding.overlayReportsScreen.visibility = View.GONE
        binding.overlayAllianceScreen.visibility = View.GONE
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

        // Dynamic visual slots for Airbase Hangar & SAM Silos
        if (s.type == "airbase") {
            binding.layoutHangarSlots.visibility = View.VISIBLE
            binding.layoutSamSlots.visibility = View.GONE
            renderHangarSlots(s)
        } else if (s.type == "sam") {
            binding.layoutSamSlots.visibility = View.VISIBLE
            binding.layoutHangarSlots.visibility = View.GONE
            renderSamSlots(s)
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
                gameState.upgradeStructure(s.id)
                binding.cardStructureInspector.visibility = View.GONE
            }
        } else {
            binding.btnInspectorUpgrade.visibility = View.GONE
        }

        // Repair button
        binding.btnInspectorRepair.setOnClickListener {
            gameState.repairStructure(s.id)
            binding.tvInspectorHp.text = "${s.hp} / ${s.maxHp}"
            binding.pbInspectorHp.progress = s.hp
        }

        // Sell button
        binding.btnInspectorSell.setOnClickListener {
            gameState.sellStructure(s.id)
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
                    setOnClickListener {
                        binding.cardStructureInspector.visibility = View.GONE
                        showMilitarySheet()
                    }
                }

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
                    text = "Range: 2,500m"
                    setTextColor(Color.parseColor("#8E8E93"))
                    textSize = 9f
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
                        val result = gameState.armSamSlot(s.id)
                        if (result.first) {
                            renderSamSlots(s)
                        } else {
                            Toast.makeText(this@MainActivity, result.second, Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                siloCard.addView(tvTitle)
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

        binding.tvJetControlTitle.text = "${u.name} (Lead Sortie)"
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

    override fun onDestroy() {
        super.onDestroy()
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
        showJetSortieControl(jetId)
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
            Toast.makeText(this, "Air Sortie dispatched ($order)!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, result.second, Toast.LENGTH_LONG).show()
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
            }
        }
    }

    override fun onUnitsUpdated(units: List<UnitItem>) {
        if (isMapReady) {
            syncUnitsToMap()
        }
        // Live update fuel in jet control card if visible
        activeSelectedJetId?.let { id ->
            units.find { it.id == id }?.let { u ->
                val fuel = u.fuelPercent.toInt()
                binding.tvJetFuelPercent.text = "$fuel%"
                binding.pbJetFuel.progress = fuel
                val orderDesc = when (u.order) {
                    "strike" -> "STRIKE MISSION • EN ROUTE"
                    "patrol" -> "AIR COMBAT PATROL (CAP)"
                    else -> "RETURNING TO AIRBASE (RTB)"
                }
                binding.tvJetControlStatus.text = orderDesc
            }
        }
    }

    override fun onAlert(title: String, message: String) {
        Toast.makeText(this, "$title: $message", Toast.LENGTH_SHORT).show()
    }

    override fun onStructureUpgradeCompleted(structure: Structure) {
        syncWorldToMap()
        Toast.makeText(this, "Upgrade Complete: ${structure.name} (L${structure.level})", Toast.LENGTH_LONG).show()
    }

    override fun onMissileFired(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, missileType: String) {
        if (isMapReady) {
            val script = "window.animateMissileLaunch($fromLat, $fromLng, $toLat, $toLng, '$missileType');"
            binding.mapWebView.evaluateJavascript(script, null)
        }
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
        if (structures.isNotEmpty()) {
            gameState.structures.clear()
            gameState.structures.addAll(structures)
            syncWorldToMap()
        }
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
                    online = sObj.optBoolean("online", true)
                )
                val idx = gameState.structures.indexOfFirst { it.id == id }
                if (idx != -1) {
                    gameState.structures[idx] = s
                } else {
                    gameState.structures.add(s)
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

        if (worldChanged) {
            syncWorldToMap()
        }
    }

    override fun onServerAlert(level: String, text: String) {
        Toast.makeText(this, "[Server] $text", Toast.LENGTH_SHORT).show()
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
        binding.btnNewsFilterAll.setOnClickListener { renderNewsFeed("all") }
        binding.btnNewsFilterWar.setOnClickListener { renderNewsFeed("combat") }
        binding.btnNewsFilterRadar.setOnClickListener { renderNewsFeed("radar") }
        binding.btnNewsFilterEconomy.setOnClickListener { renderNewsFeed("economy") }

        // Alliance Overlay Controls
        binding.btnCloseAlliance.setOnClickListener {
            binding.overlayAllianceScreen.visibility = View.GONE
        }
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
}
