import { GeoPoint, Structure } from './types';
/**
 * Calculates Haversine distance in meters between two geographic coordinates.
 */
export declare function calculateDistanceMeters(p1: GeoPoint, p2: GeoPoint): number;
/**
 * Checks if a point falls within any territory provider (Town Hall or settlement).
 */
export declare function isPointInsideTerritory(point: GeoPoint, structures: Structure[], playerId: string): boolean;
/**
 * Checks if a candidate position satisfies the minimum separation distance from all existing structures.
 */
export declare function satisfiesStructureSeparation(point: GeoPoint, structures: Structure[], minSeparationMeters: number): boolean;
/**
 * Checks for teleportation / excessive travel speed between location pings.
 */
export declare function isSpeedValid(oldPos: GeoPoint, newPos: GeoPoint, elapsedSeconds: number, maxSpeedMps: number): boolean;
/**
 * Calculates combined hit chance from multiple overlapping defense/SAM sites with diminishing returns.
 */
export declare function calculateCombinedDefenseHitChance(hitChances: number[]): number;
