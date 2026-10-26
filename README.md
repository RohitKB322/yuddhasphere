# 🌐 YuddhaSphere — Global GPS Real-Time Strategy (RTS)

**YuddhaSphere** is a lightweight, asset-free, GPS-based real-time strategy (RTS) multiplayer game. Players claim real-world territories, construct military bases, scramble supersonic fighter sorties, commission sovereign alliances with cabinet posts, and intercept enemy airspace using real-world location tracking and an authoritative real-time server.

---

## 🏛️ System Architecture

```mermaid
graph TD
    Client1["Android Client (Commander 1)<br/>Kotlin + Leaflet (OSM Free Tiles)"] -->|WebSocket (ws / wss)| Server["Authoritative Server (Node.js + TypeScript)<br/>O(1) SpatialGrid + GameLoop"]
    Client2["Android Client (Commander 2)<br/>Kotlin + Leaflet (OSM Free Tiles)"] -->|WebSocket (ws / wss)| Server
    Server -->|Atomic Persistence| DB[("world.json & Hourly Backups")]
    Server -->|Broadcasting StateDiff| Client1
    Server -->|Broadcasting StateDiff| Client2
```

* **`android-client/`**: Modern native Android application written in Kotlin, featuring zero-dependency free OpenStreetMap tiles, tactical dark glassmorphism, transparent full-page overlays, live GPS tracking, and OkHttp WebSocket sync.
* **`server/`**: Authoritative game engine running on Node.js & TypeScript with 1-second game ticks, O(1) spatial grid indexing (~1.1 km cells), alliance and cabinet management, intelligence reporting, and atomic disk persistence.
* **`shared-rules/`**: Shared TypeScript data models, protocols, combat math, and spatial calculations.

---

## 🚀 Deploy to Render (100% Free Forever)

This repository includes a multi-stage **`Dockerfile`** and **`render.yaml`** ready for 1-click deployment on [Render.com](https://render.com).

### Step-by-Step Render Deployment:
1. Fork or push this repository to your GitHub account (`https://github.com/RohitKB322/yuddhasphere.git`).
2. Log into [Render.com](https://render.com) and click **New +** $\rightarrow$ **Web Service**.
3. Select this GitHub repository.
4. Set the runtime environment to **Docker**.
5. Select the **Free** instance plan ($0/month).
6. Click **Create Web Service**.
7. Render will build the Docker container and give you a public secure URL:
   ```
   https://yuddhasphere-server.onrender.com (or wss://yuddhasphere-server.onrender.com)
   ```
8. In the **YuddhaSphere Android App**:
   * Open the **Menu** tab.
   * Enter your Render domain (e.g. `yuddhasphere-server.onrender.com`) in the **Host** field.
   * Tap **SAVE SETTINGS & RECONNECT**.
   * Your device will connect directly over secure `wss://`!

---

## 💻 Running the Server Locally

```powershell
# In PowerShell:
.\start-server.ps1
```
The server will start on `http://0.0.0.0:8080` and `ws://0.0.0.0:8080`.

To run the automated two-client live sync verification suite:
```powershell
powershell -ExecutionPolicy Bypass -File server/run_live_test.ps1
```

---

## 📱 Features by Milestone

* **v0.1**: Core GPS mapping, player position badge, resource generation, and starter Citadel.
* **v0.2**: OpenStreetMap free raster tiles, construction bounds, and territory radius validation.
* **v0.3**: Dynamic visual Airbase Hangar Bays & SAM Silos, starting funds ($10,000), Delta-Wing supersonic fighter jet icons with heading rotation, radar aperture vision sectors (30°, 60°, 90°), and Fox-2 missile animations.
* **v0.4**:
  * **24/7 War Room Breaking News Wire**: Dedicated full-screen broadcast feed with live marquee ticker, alert banners, and category dispatch wires.
  * **Alliance Command Nexus**: Clean-slate faction system, cabinet posts (**Supreme Commander**, **Financial Minister**, **Foreign Minister**, **Member**), tax rate controls (0%–20%), member promotions, and war/pact diplomatic treaties.
  * **Multiplayer Server Settings**: Host/port input, fast presets (`10.0.2.2`, `127.0.0.1`), and live ping status badge.
* **v0.5**:
  * Multi-client real-time state diff broadcasting over WebSocket.
  * Docker containerization and Render cloud deployment readiness.
  * Automatic cloud URL resolution (`wss://` and SSL support).
