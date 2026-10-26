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
