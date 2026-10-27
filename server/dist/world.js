"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.World = void 0;
const spatial_1 = require("./spatial");
const alliances_1 = require("./alliances");
const reports_1 = require("./reports");
const leaderboards_1 = require("./leaderboards");
class World {
    state = {
        version: 1,
        lastTickTime: Date.now(),
        players: {},
        structures: {},
        units: {},
        projectiles: {},
        loot: {}
    };
    spatialStructures = new spatial_1.SpatialGrid(0.01);
    spatialUnits = new spatial_1.SpatialGrid(0.01);
    alliances = new alliances_1.AllianceManager();
    reports = new reports_1.ReportManager();
    leaderboards = new leaderboards_1.LeaderboardManager();
    getPlayer(id) {
        return this.state.players[id];
    }
    setPlayer(player) {
        this.state.players[player.id] = player;
    }
    getStructures() {
        return Object.values(this.state.structures);
    }
    addStructure(structure) {
        this.state.structures[structure.id] = structure;
        this.spatialStructures.insert(structure);
    }
    removeStructure(id) {
        delete this.state.structures[id];
        this.spatialStructures.remove(id);
    }
    catchUp(elapsedSeconds) {
        if (elapsedSeconds <= 0)
            return 0;
        const cappedSeconds = Math.min(elapsedSeconds, 7 * 86400); // Up to 7 days
        const hours = cappedSeconds / 3600;
        const now = Date.now();
        for (const player of Object.values(this.state.players)) {
            const rate = player.incomePerHour || 100;
            const gain = Math.floor(rate * hours);
            player.wealth = (player.wealth || 0) + gain;
            player.lastActive = now;
        }
        for (const s of Object.values(this.state.structures)) {
            if (s.upgradeFinishedAt && now >= s.upgradeFinishedAt) {
                s.upgradeFinishedAt = undefined;
                s.level += 1;
                s.hp = s.maxHp = (s.maxHp || 200) + 150;
            }
            if (s.slots && s.slots.queue) {
                const ready = s.slots.queue.filter(q => q.finishesAt <= now);
                s.slots.queue = s.slots.queue.filter(q => q.finishesAt > now);
                ready.forEach(r => s.slots.storedUnits.push(r.itemId));
            }
        }
        this.state.lastTickTime = now;
        console.log(`[World] Global catch-up executed for ${cappedSeconds}s across ${Object.keys(this.state.players).length} players.`);
        return cappedSeconds;
    }
    getUnits() {
        return Object.values(this.state.units);
    }
    addUnit(unit) {
        this.state.units[unit.id] = unit;
        this.spatialUnits.insert(unit);
    }
    updateUnit(unit) {
        this.state.units[unit.id] = unit;
        this.spatialUnits.update(unit);
    }
    removeUnit(id) {
        delete this.state.units[id];
        this.spatialUnits.remove(id);
    }
    getNearbyStructures(point, radiusMeters) {
        return this.spatialStructures.getNearby(point.lat, point.lng, radiusMeters);
    }
    getNearbyUnits(point, radiusMeters) {
        return this.spatialUnits.getNearby(point.lat, point.lng, radiusMeters);
    }
}
exports.World = World;
