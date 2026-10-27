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
    // In-Memory Dirty Write-Behind Buffer (Zero-latency to live game loop)
    dirtyPlayers = new Map();
    dirtyStructures = new Map();
    dirtyAlliances = new Map();
    dirtyTreaties = new Map();
    dirtyReports = [];
    removedStructureIds = new Set();
    removedAllianceIds = new Set();
    isFlushing = false;
    flushTimer = null;
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
                connectTimeoutMS: 10000,
                maxPoolSize: 10 // Lean connection pool tailored for free tier
            });
            await this.client.connect();
            this.db = this.client.db('yuddhasphere');
            this.colPlayers = this.db.collection('players');
            this.colStructures = this.db.collection('structures');
            this.colAlliances = this.db.collection('alliances');
            this.colTreaties = this.db.collection('treaties');
            this.colReports = this.db.collection('reports');
            this.isConnected = true;
            console.log('[Database] Connected successfully to MongoDB Atlas! Asynchronous write-behind persistence ACTIVE.');
            // Start automatic debounced flush every 20 seconds
            if (!this.flushTimer) {
                this.flushTimer = setInterval(() => {
                    this.flushDirty().catch(err => {
                        console.error('[Database] Background flush error:', err.message);
                    });
                }, 20000);
            }
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
    // Buffers updates in memory (instant 0ms latency to caller)
    savePlayer(player) {
        this.dirtyPlayers.set(player.id, { ...player });
    }
    saveStructure(structure) {
        this.dirtyStructures.set(structure.id, { ...structure });
        this.removedStructureIds.delete(structure.id);
    }
    removeStructure(structureId) {
        this.dirtyStructures.delete(structureId);
        this.removedStructureIds.add(structureId);
    }
    saveAlliance(alliance) {
        this.dirtyAlliances.set(alliance.id, { ...alliance });
        this.removedAllianceIds.delete(alliance.id);
    }
    removeAlliance(allianceId) {
        this.dirtyAlliances.delete(allianceId);
        this.removedAllianceIds.add(allianceId);
    }
    saveTreaty(treaty) {
        this.dirtyTreaties.set(treaty.id, { ...treaty });
    }
    saveReport(report) {
        this.dirtyReports.push({ ...report });
    }
    // Flushes an individual player immediately (e.g. on disconnect)
    async flushPlayerImmediate(playerId, player) {
        if (!this.isConnected || !this.colPlayers)
            return;
        const p = player || this.dirtyPlayers.get(playerId);
        if (!p)
            return;
        this.dirtyPlayers.delete(playerId);
        try {
            await this.colPlayers.updateOne({ id: p.id }, { $set: p }, { upsert: true });
        }
        catch (err) {
            console.error(`[Database] Error saving player ${playerId}:`, err.message);
        }
    }
    // Non-blocking bulk write-behind
    async flushDirty() {
        if (!this.isConnected || this.isFlushing)
            return;
        const totalDirty = this.dirtyPlayers.size +
            this.dirtyStructures.size +
            this.removedStructureIds.size +
            this.dirtyAlliances.size +
            this.removedAllianceIds.size +
            this.dirtyTreaties.size +
            this.dirtyReports.length;
        if (totalDirty === 0)
            return;
        this.isFlushing = true;
        // Take shallow snapshots and clear dirty buffers
        const playersToFlush = Array.from(this.dirtyPlayers.values());
        this.dirtyPlayers.clear();
        const structuresToFlush = Array.from(this.dirtyStructures.values());
        this.dirtyStructures.clear();
        const removedStructures = Array.from(this.removedStructureIds.values());
        this.removedStructureIds.clear();
        const alliancesToFlush = Array.from(this.dirtyAlliances.values());
        this.dirtyAlliances.clear();
        const removedAlliances = Array.from(this.removedAllianceIds.values());
        this.removedAllianceIds.clear();
        const treatiesToFlush = Array.from(this.dirtyTreaties.values());
        this.dirtyTreaties.clear();
        const reportsToFlush = [...this.dirtyReports];
        this.dirtyReports = [];
        try {
            // 1. Bulk write players
            if (playersToFlush.length > 0 && this.colPlayers) {
                const ops = playersToFlush.map(p => ({
                    updateOne: {
                        filter: { id: p.id },
                        update: { $set: p },
                        upsert: true
                    }
                }));
                await this.colPlayers.bulkWrite(ops, { ordered: false });
            }
            // 2. Bulk write structures (updates + deletes)
            if ((structuresToFlush.length > 0 || removedStructures.length > 0) && this.colStructures) {
                const ops = structuresToFlush.map(s => ({
                    updateOne: {
                        filter: { id: s.id },
                        update: { $set: s },
                        upsert: true
                    }
                }));
                removedStructures.forEach(id => {
                    ops.push({
                        deleteOne: {
                            filter: { id }
                        }
                    });
                });
                if (ops.length > 0) {
                    await this.colStructures.bulkWrite(ops, { ordered: false });
                }
            }
            // 3. Bulk write alliances
            if ((alliancesToFlush.length > 0 || removedAlliances.length > 0) && this.colAlliances) {
                const ops = alliancesToFlush.map(a => ({
                    updateOne: {
                        filter: { id: a.id },
                        update: { $set: a },
                        upsert: true
                    }
                }));
                removedAlliances.forEach(id => {
                    ops.push({
                        deleteOne: {
                            filter: { id }
                        }
                    });
                });
                if (ops.length > 0) {
                    await this.colAlliances.bulkWrite(ops, { ordered: false });
                }
            }
            // 4. Bulk write treaties
            if (treatiesToFlush.length > 0 && this.colTreaties) {
                const ops = treatiesToFlush.map(t => ({
                    updateOne: {
                        filter: { id: t.id },
                        update: { $set: t },
                        upsert: true
                    }
                }));
                await this.colTreaties.bulkWrite(ops, { ordered: false });
            }
            // 5. Bulk write reports
            if (reportsToFlush.length > 0 && this.colReports) {
                const ops = reportsToFlush.map(r => ({
                    updateOne: {
                        filter: { id: r.id },
                        update: { $set: r },
                        upsert: true
                    }
                }));
                await this.colReports.bulkWrite(ops, { ordered: false });
            }
        }
        catch (err) {
            console.error('[Database] Background bulkWrite error:', err.message);
        }
        finally {
            this.isFlushing = false;
        }
    }
    // Periodic or bootstrap full snapshot using bulkWrite
    async saveFullWorld(world) {
        if (!this.isConnected)
            return;
        try {
            const players = Object.values(world.state.players);
            if (players.length > 0 && this.colPlayers) {
                const ops = players.map(p => ({
                    updateOne: { filter: { id: p.id }, update: { $set: p }, upsert: true }
                }));
                await this.colPlayers.bulkWrite(ops, { ordered: false });
            }
            const structures = Object.values(world.state.structures);
            if (structures.length > 0 && this.colStructures) {
                const ops = structures.map(s => ({
                    updateOne: { filter: { id: s.id }, update: { $set: s }, upsert: true }
                }));
                await this.colStructures.bulkWrite(ops, { ordered: false });
            }
            const alliances = world.alliances.getAlliances();
            if (alliances.length > 0 && this.colAlliances) {
                const ops = alliances.map(a => ({
                    updateOne: { filter: { id: a.id }, update: { $set: a }, upsert: true }
                }));
                await this.colAlliances.bulkWrite(ops, { ordered: false });
            }
            const treaties = world.alliances.getTreaties();
            if (treaties.length > 0 && this.colTreaties) {
                const ops = treaties.map(t => ({
                    updateOne: { filter: { id: t.id }, update: { $set: t }, upsert: true }
                }));
                await this.colTreaties.bulkWrite(ops, { ordered: false });
            }
        }
        catch (err) {
            console.error('[Database] Full snapshot bulk save error:', err.message);
        }
    }
    async close() {
        if (this.flushTimer) {
            clearInterval(this.flushTimer);
            this.flushTimer = null;
        }
        // Flush any pending dirty writes before closing
        await this.flushDirty();
        if (this.client) {
            await this.client.close();
            this.isConnected = false;
        }
    }
}
exports.DatabaseManager = DatabaseManager;
exports.dbManager = new DatabaseManager();
