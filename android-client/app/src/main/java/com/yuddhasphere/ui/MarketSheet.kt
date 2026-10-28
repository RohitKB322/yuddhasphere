package com.yuddhasphere.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.yuddhasphere.R
import com.yuddhasphere.models.MarketAsset
import com.yuddhasphere.models.PortfolioHolding
import com.yuddhasphere.state.LocalGameState

class MarketSheet(
    private val context: Context,
    private val gameState: LocalGameState,
    private val onTradeExecuted: (() -> Unit)? = null
) : LocalGameState.StateListener {

    private var dialog: BottomSheetDialog? = null
    private var currentTab = "all" // "all", "commodities", "equities", "holdings"

    // Trade sheet state
    private var activeTradeAsset: MarketAsset? = null
    private var tradeMode = "buy" // "buy" or "sell"
    private var tradeQuantity = 1

    private var tvPortfolioValue: TextView? = null
    private var tvPortfolioPnL: TextView? = null
    private var tvAvailableCash: TextView? = null
    private var tvTotalInvested: TextView? = null
    private var tvNewsTicker: TextView? = null
    private var containerAssets: LinearLayout? = null
    private var layoutTradeSheet: LinearLayout? = null

    // Trade sheet views
    private var tvTradeAssetTitle: TextView? = null
    private var tvTradeAssetHoldingInfo: TextView? = null
    private var btnTradeModeBuy: Button? = null
    private var btnTradeModeSell: Button? = null
    private var tvTradeQuantity: TextView? = null
    private var tvTradeTotalCost: TextView? = null
    private var tvTradeRemainingCash: TextView? = null
    private var btnExecuteTrade: Button? = null

    fun show() {
        dialog = BottomSheetDialog(context, R.style.Theme_YuddhaSphere)
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_market, null)
        dialog?.setContentView(view)

        dialog?.window?.apply {
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.0f)
        }

        view.findViewById<ImageButton>(R.id.btnCloseMarket).setOnClickListener {
            dialog?.dismiss()
        }

        tvNewsTicker = view.findViewById(R.id.tvMarketNewsTicker)
        tvNewsTicker?.isSelected = true // Enables marquee auto-scroll
        val latestNews = gameState.marketNews.firstOrNull()?.headline
            ?: "Global markets active across all tactical commodities and aerospace equities."
        tvNewsTicker?.text = latestNews

        tvPortfolioValue = view.findViewById(R.id.tvPortfolioValue)
        tvPortfolioPnL = view.findViewById(R.id.tvPortfolioPnL)
        tvAvailableCash = view.findViewById(R.id.tvAvailableCash)
        tvTotalInvested = view.findViewById(R.id.tvTotalInvested)
        containerAssets = view.findViewById(R.id.containerMarketAssets)
        layoutTradeSheet = view.findViewById(R.id.layoutTradeSheet)

        // Trade sheet controls
        tvTradeAssetTitle = view.findViewById(R.id.tvTradeAssetTitle)
        tvTradeAssetHoldingInfo = view.findViewById(R.id.tvTradeAssetHoldingInfo)
        btnTradeModeBuy = view.findViewById(R.id.btnTradeModeBuy)
        btnTradeModeSell = view.findViewById(R.id.btnTradeModeSell)
        tvTradeQuantity = view.findViewById(R.id.tvTradeQuantity)
        tvTradeTotalCost = view.findViewById(R.id.tvTradeTotalCost)
        tvTradeRemainingCash = view.findViewById(R.id.tvTradeRemainingCash)
        btnExecuteTrade = view.findViewById(R.id.btnExecuteTrade)

        view.findViewById<ImageButton>(R.id.btnCloseTradeSheet).setOnClickListener {
            layoutTradeSheet?.visibility = View.GONE
            activeTradeAsset = null
        }

        // Setup Trade Mode Toggle
        btnTradeModeBuy?.setOnClickListener {
            tradeMode = "buy"
            updateTradeSheetModeUI()
            recalculateTradePreview()
        }
        btnTradeModeSell?.setOnClickListener {
            tradeMode = "sell"
            updateTradeSheetModeUI()
            recalculateTradePreview()
        }

        // Setup Quantity Presets
        view.findViewById<Button>(R.id.btnQty1).setOnClickListener { setTradeQty(1) }
        view.findViewById<Button>(R.id.btnQty5).setOnClickListener { setTradeQty(5) }
        view.findViewById<Button>(R.id.btnQty10).setOnClickListener { setTradeQty(10) }
        view.findViewById<Button>(R.id.btnQty50).setOnClickListener { setTradeQty(50) }
        view.findViewById<Button>(R.id.btnQtyMax).setOnClickListener {
            val asset = activeTradeAsset ?: return@setOnClickListener
            if (tradeMode == "buy") {
                val maxCanBuy = (gameState.player.wealth / asset.currentPrice).toInt().coerceAtLeast(1)
                setTradeQty(maxCanBuy)
            } else {
                val owned = gameState.player.holdings[asset.id]?.quantity ?: 0
                setTradeQty(owned.coerceAtLeast(1))
            }
        }

        // Setup Stepper
        view.findViewById<Button>(R.id.btnQtyMinus).setOnClickListener {
            setTradeQty((tradeQuantity - 1).coerceAtLeast(1))
        }
        view.findViewById<Button>(R.id.btnQtyPlus).setOnClickListener {
            setTradeQty(tradeQuantity + 1)
        }

        // Execute Trade Button
        btnExecuteTrade?.setOnClickListener {
            val asset = activeTradeAsset ?: return@setOnClickListener
            val result = if (tradeMode == "buy") {
                gameState.executeMarketBuy(asset.id, tradeQuantity)
            } else {
                gameState.executeMarketSell(asset.id, tradeQuantity)
            }

            Toast.makeText(context, result.second, Toast.LENGTH_SHORT).show()
            if (result.first) {
                onTradeExecuted?.invoke()
                updatePortfolioSummary()
                renderAssetList()
                openTradeSheet(asset) // Refresh trade sheet with updated holdings & balances
            }
        }

        // Setup Category Filter Tabs
        val btnTabAll = view.findViewById<Button>(R.id.btnMarketTabAll)
        val btnTabCommodities = view.findViewById<Button>(R.id.btnMarketTabCommodities)
        val btnTabEquities = view.findViewById<Button>(R.id.btnMarketTabEquities)
        val btnTabHoldings = view.findViewById<Button>(R.id.btnMarketTabHoldings)

        fun updateTabs() {
            val tabs = listOf(
                btnTabAll to "all",
                btnTabCommodities to "commodities",
                btnTabEquities to "equities",
                btnTabHoldings to "holdings"
            )
            tabs.forEach { (btn, tabKey) ->
                if (currentTab == tabKey) {
                    btn.setBackgroundResource(R.drawable.bg_accent_button)
                    btn.setTextColor(context.getColor(R.color.bg_tactical_dark))
                } else {
                    btn.setBackgroundResource(R.drawable.bg_secondary_button)
                    btn.setTextColor(Color.WHITE)
                }
            }
            renderAssetList()
        }

        btnTabAll.setOnClickListener { currentTab = "all"; updateTabs() }
        btnTabCommodities.setOnClickListener { currentTab = "commodities"; updateTabs() }
        btnTabEquities.setOnClickListener { currentTab = "equities"; updateTabs() }
        btnTabHoldings.setOnClickListener { currentTab = "holdings"; updateTabs() }

        updatePortfolioSummary()
        renderAssetList()

        gameState.addListener(this)
        dialog?.setOnDismissListener {
            gameState.removeListener(this)
        }

        dialog?.show()
    }

    private fun setTradeQty(qty: Int) {
        tradeQuantity = qty
        tvTradeQuantity?.text = tradeQuantity.toString()
        recalculateTradePreview()
    }

    private fun updateTradeSheetModeUI() {
        if (tradeMode == "buy") {
            btnTradeModeBuy?.setBackgroundResource(R.drawable.bg_accent_button)
            btnTradeModeBuy?.setTextColor(context.getColor(R.color.bg_tactical_dark))
            btnTradeModeSell?.setBackgroundResource(R.drawable.bg_secondary_button)
            btnTradeModeSell?.setTextColor(context.getColor(R.color.accent_orange))
            btnExecuteTrade?.text = "CONFIRM BUY ($tradeQuantity SHARES)"
            btnExecuteTrade?.setBackgroundResource(R.drawable.bg_accent_button)
            btnExecuteTrade?.setTextColor(context.getColor(R.color.bg_tactical_dark))
        } else {
            btnTradeModeSell?.setBackgroundResource(R.drawable.bg_accent_button)
            btnTradeModeSell?.setTextColor(context.getColor(R.color.bg_tactical_dark))
            btnTradeModeBuy?.setBackgroundResource(R.drawable.bg_secondary_button)
            btnTradeModeBuy?.setTextColor(context.getColor(R.color.accent_cyan))
            btnExecuteTrade?.text = "CONFIRM SELL ($tradeQuantity SHARES)"
            btnExecuteTrade?.setBackgroundResource(R.drawable.bg_danger_button)
            btnExecuteTrade?.setTextColor(Color.WHITE)
        }
    }

    private fun recalculateTradePreview() {
        val asset = activeTradeAsset ?: return
        val total = (asset.currentPrice * tradeQuantity).toInt()
        if (tradeMode == "buy") {
            tvTradeTotalCost?.text = "Total Cost: $$total"
            tvTradeTotalCost?.setTextColor(context.getColor(R.color.accent_cyan))
            val remaining = gameState.player.wealth - total
            tvTradeRemainingCash?.text = "Balance After: $$remaining"
            btnExecuteTrade?.text = "CONFIRM BUY ($tradeQuantity @ $${asset.currentPrice})"
        } else {
            tvTradeTotalCost?.text = "Est. Proceeds: +$$total"
            tvTradeTotalCost?.setTextColor(context.getColor(R.color.accent_green))
            val afterSell = gameState.player.wealth + total
            tvTradeRemainingCash?.text = "Balance After: $$afterSell"
            btnExecuteTrade?.text = "CONFIRM SELL ($tradeQuantity @ $${asset.currentPrice})"
        }
    }

    private fun openTradeSheet(asset: MarketAsset) {
        activeTradeAsset = asset
        layoutTradeSheet?.visibility = View.VISIBLE

        val holding = gameState.player.holdings[asset.id]
        val ownedQty = holding?.quantity ?: 0
        val pnl = if (holding != null) (asset.currentPrice - holding.avgCostBasis) * holding.quantity else 0.0

        tvTradeAssetTitle?.text = "TRADE: ${asset.name.uppercase()} (${asset.symbol})"
        tvTradeAssetHoldingInfo?.text = "Owned: $ownedQty shares • Market Price: $${asset.currentPrice}" +
                if (ownedQty > 0) " • P&L: %s$%.2f".format(if (pnl >= 0) "+" else "", pnl) else ""

        updateTradeSheetModeUI()
        recalculateTradePreview()
    }

    private fun updatePortfolioSummary() {
        val pVal = gameState.getPortfolioValue()
        val pnl = gameState.getTotalProfitLoss()
        val pnlPct = gameState.getTotalProfitLossPercent()

        tvPortfolioValue?.text = "$%,.2f".format(pVal)
        val pnlSign = if (pnl >= 0) "+" else ""
        tvPortfolioPnL?.text = "%s$%,.2f (%s%.1f%%)".format(pnlSign, pnl, pnlSign, pnlPct)
        tvPortfolioPnL?.setTextColor(if (pnl >= 0) context.getColor(R.color.accent_green) else context.getColor(R.color.accent_red))

        tvAvailableCash?.text = "Cash: $%,d".format(gameState.player.wealth)
        tvTotalInvested?.text = "Invested: $%,.2f".format(gameState.getTotalInvested())
    }

    private fun renderAssetList() {
        val container = containerAssets ?: return
        container.removeAllViews()

        val assets = gameState.marketAssets.values.filter { asset ->
            when (currentTab) {
                "commodities" -> asset.type == "commodity"
                "equities" -> asset.type == "company"
                "holdings" -> (gameState.player.holdings[asset.id]?.quantity ?: 0) > 0
                else -> true
            }
        }

        if (assets.isEmpty()) {
            val tvEmpty = TextView(context).apply {
                text = if (currentTab == "holdings") "No active holdings. Trade commodities or defense equities above!" else "No assets found."
                setTextColor(context.getColor(R.color.text_secondary))
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 32, 0, 32)
            }
            container.addView(tvEmpty)
            return
        }

        for (asset in assets) {
            val itemView = LayoutInflater.from(context).inflate(R.layout.item_market_asset, container, false)
            val tvSymbol = itemView.findViewById<TextView>(R.id.tvAssetSymbol)
            val tvName = itemView.findViewById<TextView>(R.id.tvAssetName)
            val tvCategory = itemView.findViewById<TextView>(R.id.tvAssetCategory)
            val tvHoldings = itemView.findViewById<TextView>(R.id.tvAssetHoldings)
            val sparkline = itemView.findViewById<SparklineView>(R.id.sparklineView)
            val tvPrice = itemView.findViewById<TextView>(R.id.tvAssetPrice)
            val tvChange = itemView.findViewById<TextView>(R.id.tvAssetChange)
            val btnTrade = itemView.findViewById<Button>(R.id.btnTradeAsset)

            tvSymbol.text = asset.symbol
            tvName.text = asset.name
            tvCategory.text = "${asset.category} • ${asset.type.replaceFirstChar { it.uppercase() }}"

            val holding = gameState.player.holdings[asset.id]
            if (holding != null && holding.quantity > 0) {
                tvHoldings.visibility = View.VISIBLE
                val curVal = holding.quantity * asset.currentPrice
                tvHoldings.text = "Owned: ${holding.quantity} ($%,.2f) • Avg: $%,.2f".format(curVal, holding.avgCostBasis)
            } else {
                tvHoldings.visibility = View.GONE
            }

            sparkline.setData(asset.priceHistory, asset.changePercent >= 0)
            tvPrice.text = "$%,.2f".format(asset.currentPrice)

            val sign = if (asset.changePercent >= 0) "+" else ""
            tvChange.text = "%s%.1f%%".format(sign, asset.changePercent)
            if (asset.changePercent >= 0) {
                tvChange.setTextColor(context.getColor(R.color.accent_green))
            } else {
                tvChange.setTextColor(context.getColor(R.color.accent_red))
            }

            val onOpenTrade = View.OnClickListener {
                openTradeSheet(asset)
            }
            itemView.setOnClickListener(onOpenTrade)
            btnTrade.setOnClickListener(onOpenTrade)

            container.addView(itemView)
        }
    }

    // --- StateListener callbacks ---
    override fun onPlayerUpdated(player: com.yuddhasphere.models.Player) {
        dialog?.window?.decorView?.post {
            updatePortfolioSummary()
        }
    }

    override fun onMarketUpdated(assets: List<MarketAsset>, holdings: Map<String, PortfolioHolding>) {
        dialog?.window?.decorView?.post {
            updatePortfolioSummary()
            renderAssetList()
            if (activeTradeAsset != null) {
                val updated = assets.find { it.id == activeTradeAsset!!.id }
                if (updated != null) {
                    activeTradeAsset = updated
                    recalculateTradePreview()
                }
            }
            val latestNews = gameState.marketNews.firstOrNull()?.headline
            if (latestNews != null) {
                tvNewsTicker?.text = latestNews
            }
        }
    }

    override fun onStructuresUpdated(structures: List<com.yuddhasphere.models.Structure>) {}
    override fun onUnitsUpdated(units: List<com.yuddhasphere.models.UnitItem>) {}
    override fun onAlert(title: String, message: String) {}
    override fun onStructureUpgradeCompleted(structure: com.yuddhasphere.models.Structure) {}

    override fun onProjectileDestroyedInMidAir(missileId: String, lat: Double, lng: Double) {}

    override fun onMissileFired(
        missileId: String,
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double,
        missileType: String,
        targetUnitId: String?,
        targetMissileId: String?,
        targetName: String,
        speedMach: Double,
        hitProbability: Double,
        flightDurationSeconds: Double,
        progress: Double
    ) {}
}
