import { GeoPoint, Player, Structure, StructureType } from './types';
import { calculateDistanceMeters, isPointInsideTerritory, satisfiesStructureSeparation } from './formulas';

export interface ValidationResult {
  valid: boolean;
  reason?: string;
  cost?: number;
}

export function validatePlacement(
  player: Player,
  type: StructureType,
  targetPos: GeoPoint,
  allStructures: Structure[],
  config: any
): ValidationResult {
  // 1. Player proximity check (anti-cheat)
  const distFromPlayer = calculateDistanceMeters(player.position, targetPos);
  const maxProximity = config.game.maxPlayerProximityMeters || 600;
  if (distFromPlayer > maxProximity) {
    return {
      valid: false,
      reason: `Placement too far from your current GPS position (${Math.round(distFromPlayer)}m > ${maxProximity}m).`
    };
  }

  // 2. Minimum structure separation
  const minSep = config.game.structureSeparationMeters || 30;
  if (!satisfiesStructureSeparation(targetPos, allStructures, minSep)) {
    return {
      valid: false,
      reason: `Too close to another existing structure (minimum ${minSep}m separation required).`
    };
  }

  // 3. Cost check
  let cost = 0;
  if (config.settlementChain[type]) {
    cost = config.settlementChain[type].buildCost;
  } else if (config.otherBuildings[type]) {
    cost = config.otherBuildings[type].buildCost;
  }

  if (player.wealth < cost) {
    return {
      valid: false,
      reason: `Insufficient funds. Cost is $${cost}, but you have $${player.wealth}.`
    };
  }

  // 4. Territory requirement (HQ / Town Hall doesn't need existing territory; all others do)
  if (type !== 'town_hall') {
    const insideTerritory = isPointInsideTerritory(targetPos, allStructures, player.id);
    if (!insideTerritory) {
      return {
        valid: false,
        reason: 'Must be placed inside your established territory radius.'
      };
    }
  }

  return { valid: true, cost };
}
