"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.calculateDistanceMeters = calculateDistanceMeters;
exports.isPointInsideTerritory = isPointInsideTerritory;
exports.satisfiesStructureSeparation = satisfiesStructureSeparation;
exports.isSpeedValid = isSpeedValid;
exports.calculateCombinedDefenseHitChance = calculateCombinedDefenseHitChance;
const EARTH_RADIUS_METERS = 6371000;
/**
 * Calculates Haversine distance in meters between two geographic coordinates.
 */
function calculateDistanceMeters(p1, p2) {
    const dLat = ((p2.lat - p1.lat) * Math.PI) / 180;
    const dLng = ((p2.lng - p1.lng) * Math.PI) / 180;
    const lat1 = (p1.lat * Math.PI) / 180;
    const lat2 = (p2.lat * Math.PI) / 180;
    const a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.sin(dLng / 2) * Math.sin(dLng / 2) * Math.cos(lat1) * Math.cos(lat2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return EARTH_RADIUS_METERS * c;
}
/**
 * Checks if a point falls within any territory provider (Town Hall or settlement).
 */
function isPointInsideTerritory(point, structures, playerId) {
    return structures.some((s) => {
        if (s.ownerId !== playerId || !s.online)
            return false;
        const isTerritoryProvider = s.type === 'town_hall' ||
            s.type === 'village' ||
            s.type === 'town' ||
            s.type === 'city' ||
            s.type === 'metropolis';
        if (!isTerritoryProvider)
            return false;
        const dist = calculateDistanceMeters(point, s.position);
        return dist <= s.radiusMeters;
    });
}
/**
 * Checks if a candidate position satisfies the minimum separation distance from all existing structures.
 */
function satisfiesStructureSeparation(point, structures, minSeparationMeters) {
    for (const s of structures) {
        const dist = calculateDistanceMeters(point, s.position);
        if (dist < minSeparationMeters) {
            return false;
        }
    }
    return true;
}
/**
 * Checks for teleportation / excessive travel speed between location pings.
 */
function isSpeedValid(oldPos, newPos, elapsedSeconds, maxSpeedMps) {
    if (elapsedSeconds <= 0)
        return true;
    const distance = calculateDistanceMeters(oldPos, newPos);
    const speed = distance / elapsedSeconds;
    return speed <= maxSpeedMps;
}
/**
 * Calculates combined hit chance from multiple overlapping defense/SAM sites with diminishing returns.
 */
function calculateCombinedDefenseHitChance(hitChances) {
    if (hitChances.length === 0)
        return 0.0;
    // Combined probability of at least one hit = 1 - product(1 - p_i)
    let missProb = 1.0;
    for (const p of hitChances) {
        missProb *= (1.0 - Math.min(Math.max(p, 0), 1.0));
    }
    return Math.min(0.95, 1.0 - missProb); // Cap at 95%
}
