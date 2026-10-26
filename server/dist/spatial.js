"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.SpatialGrid = void 0;
class SpatialGrid {
    cellSize;
    grid = new Map();
    entityCells = new Map();
    constructor(cellSizeDegrees = 0.01) {
        this.cellSize = cellSizeDegrees;
    }
    getCellKey(lat, lng) {
        const latIndex = Math.floor(lat / this.cellSize);
        const lngIndex = Math.floor(lng / this.cellSize);
        return `${latIndex}:${lngIndex}`;
    }
    insert(item) {
        const key = this.getCellKey(item.position.lat, item.position.lng);
        if (!this.grid.has(key)) {
            this.grid.set(key, new Set());
        }
        this.grid.get(key).add(item);
        this.entityCells.set(item.id, key);
    }
    update(item) {
        const oldKey = this.entityCells.get(item.id);
        const newKey = this.getCellKey(item.position.lat, item.position.lng);
        if (oldKey !== newKey) {
            if (oldKey && this.grid.has(oldKey)) {
                this.grid.get(oldKey).delete(item);
            }
            if (!this.grid.has(newKey)) {
                this.grid.set(newKey, new Set());
            }
            this.grid.get(newKey).add(item);
            this.entityCells.set(item.id, newKey);
        }
    }
    remove(id) {
        const key = this.entityCells.get(id);
        if (key && this.grid.has(key)) {
            const cellSet = this.grid.get(key);
            for (const item of cellSet) {
                if (item.id === id) {
                    cellSet.delete(item);
                    break;
                }
            }
        }
        this.entityCells.delete(id);
    }
    getNearby(lat, lng, radiusMeters) {
        const results = [];
        const cellRadius = Math.ceil(radiusMeters / 111320 / this.cellSize);
        const centerLatIndex = Math.floor(lat / this.cellSize);
        const centerLngIndex = Math.floor(lng / this.cellSize);
        for (let dLat = -cellRadius; dLat <= cellRadius; dLat++) {
            for (let dLng = -cellRadius; dLng <= cellRadius; dLng++) {
                const key = `${centerLatIndex + dLat}:${centerLngIndex + dLng}`;
                const items = this.grid.get(key);
                if (items) {
                    for (const item of items) {
                        results.push(item);
                    }
                }
            }
        }
        return results;
    }
}
exports.SpatialGrid = SpatialGrid;
