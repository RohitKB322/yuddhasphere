"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.dbManager = exports.DatabaseManager = void 0;
const mongodb_1 = require("mongodb");
class DatabaseManager {
    client = null;
    db = null;
    isConnected = false;
    colPlayers = null;
    colStructures = null;
    colAlliances = null;
    colTreaties = null;
    colReports = null;
    async connect(uri) {
        const mongoUri = uri || process.env.MONGODB_URI;
        if (!mongoUri) {
            console.log('[Database] No MONGODB_URI provided. Running in local file/fallback mode.');
            return false;
        }
        try {
            console.log('[Database] Connecting to MongoDB Atlas...');
            this.client = new mongodb_1.MongoClient(mongoUri, {
                serverSelectionTimeoutMS: 5000,
                connectTimeoutMS: 10000
            });
            await this.client.connect();
            this.db = this.client.db('yuddhasphere');
            this.colPlayers = this.db.collection('players');
            this.colStructures = this.db.collection('structures');
            this.colAlliances = this.db.collection('alliances');
            this.colTreaties = this.db.collection('treaties');
            this.colReports = this.db.collection('reports');
            this.isConnected = true;
            console.log('[Database] Connected successfully to MongoDB Atlas! Permanent persistence ACTIVE.');
            return true;
        }
        catch (err) {
            console.error('[Database] Failed to connect to MongoDB Atlas:', err.message);
            this.isConnected = false;
            return false;
        }
    }
    async loadWorldState(world) {
        if (!this.isConnected || !this.db) {
            return false;
        }
        try {
            console.log('[Database] Restoring world state from MongoDB...');
            // 1. Load players
            const players = await this.colPlayers.find({}).toArray();
            players.forEach((p) => {
                delete p._id;
                world.state.players[p.id] = p;
            });
            // 2. Load structures
            const structures = await this.colStructures.find({}).toArray();
            structures.forEach((s) => {
                delete s._id;
                world.state.structures[s.id] = s;
                world.spatialStructures.insert(s);
            });
            // 3. Load alliances
            const alliances = await this.colAlliances.find({}).toArray();
            alliances.forEach((a) => {
                delete a._id;
                world.alliances.alliances.set(a.id, a);
            });
            // 4. Load treaties
            const treaties = await this.colTreaties.find({}).toArray();
            treaties.forEach((t) => {
                delete t._id;
                const key = [t.allianceIdA, t.allianceIdB].sort().join(':');
                world.alliances.treaties.set(key, t);
            });
            // 5. Load reports
            const reports = await this.colReports.find({}).sort({ timestamp: -1 }).limit(50).toArray();
            const reportList = reports.map((r) => {
                delete r._id;
                return r;
            });
            if (reportList.length > 0) {
                world.reports.reports = reportList;
            }
            console.log(`[Database] World restored: ${players.length} players, ${structures.length} structures, ${alliances.length} alliances, ${treaties.length} treaties.`);
            return true;
        }
        catch (err) {
            console.error('[Database] Error loading world from MongoDB:', err.message);
            return false;
        }
    }
    async savePlayer(player) {
        if (!this.isConnected || !this.colPlayers)
            return;
        try {
            await this.colPlayers.updateOne({ id: player.id }, { $set: player }, { upsert: true });
        }
        catch (err) {
            console.error(`[Database] Error saving player ${player.callsign}:`, err.message);
        }
    }
    async saveStructure(structure) {
        if (!this.isConnected || !this.colStructures)
            return;
        try {
            await this.colStructures.updateOne({ id: structure.id }, { $set: structure }, { upsert: true });
        }
        catch (err) {
            console.error(`[Database] Error saving structure ${structure.id}:`, err.message);
        }
    }
    async removeStructure(structureId) {
        if (!this.isConnected || !this.colStructures)
            return;
        try {
            await this.colStructures.deleteOne({ id: structureId });
        }
        catch (err) {
            console.error(`[Database] Error deleting structure ${structureId}:`, err.message);
        }
    }
    async saveAlliance(alliance) {
        if (!this.isConnected || !this.colAlliances)
            return;
        try {
            await this.colAlliances.updateOne({ id: alliance.id }, { $set: alliance }, { upsert: true });
        }
        catch (err) {
            console.error(`[Database] Error saving alliance ${alliance.tag}:`, err.message);
        }
    }
    async removeAlliance(allianceId) {
        if (!this.isConnected || !this.colAlliances)
            return;
        try {
            await this.colAlliances.deleteOne({ id: allianceId });
        }
        catch (err) {
            console.error(`[Database] Error deleting alliance ${allianceId}:`, err.message);
        }
    }
    async saveTreaty(treaty) {
        if (!this.isConnected || !this.colTreaties)
            return;
        try {
            await this.colTreaties.updateOne({ id: treaty.id }, { $set: treaty }, { upsert: true });
        }
        catch (err) {
            console.error(`[Database] Error saving treaty ${treaty.id}:`, err.message);
        }
    }
    async saveReport(report) {
        if (!this.isConnected || !this.colReports)
            return;
        try {
            await this.colReports.updateOne({ id: report.id }, { $set: report }, { upsert: true });
        }
        catch (err) {
            console.error(`[Database] Error saving report ${report.id}:`, err.message);
        }
    }
    async saveFullWorld(world) {
        if (!this.isConnected)
            return;
        try {
            const players = Object.values(world.state.players);
            for (const p of players) {
                await this.savePlayer(p);
            }
            const structures = Object.values(world.state.structures);
            for (const s of structures) {
                await this.saveStructure(s);
            }
            const alliances = world.alliances.getAlliances();
            for (const a of alliances) {
                await this.saveAlliance(a);
            }
            const treaties = world.alliances.getTreaties();
            for (const t of treaties) {
                await this.saveTreaty(t);
            }
        }
        catch (err) {
            console.error('[Database] Periodic full save error:', err.message);
        }
    }
    async close() {
        if (this.client) {
            await this.client.close();
            this.isConnected = false;
        }
    }
}
exports.DatabaseManager = DatabaseManager;
exports.dbManager = new DatabaseManager();
