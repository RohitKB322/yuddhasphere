import { Player, Structure, Unit, Projectile, LootItem, GeoPoint } from '@yuddhasphere/shared-rules';
import { SpatialGrid } from './spatial';
import { AllianceManager } from './alliances';
import { ReportManager } from './reports';
import { LeaderboardManager } from './leaderboards';
export interface WorldState {
    version: number;
    lastTickTime: number;
    players: Record<string, Player>;
    structures: Record<string, Structure>;
    units: Record<string, Unit>;
    projectiles: Record<string, Projectile>;
    loot: Record<string, LootItem>;
}
export declare class World {
    state: WorldState;
    spatialStructures: SpatialGrid<Structure>;
    spatialUnits: SpatialGrid<Unit>;
    alliances: AllianceManager;
    reports: ReportManager;
    leaderboards: LeaderboardManager;
    getPlayer(id: string): Player | undefined;
    setPlayer(player: Player): void;
    getStructures(): Structure[];
    addStructure(structure: Structure): void;
    removeStructure(id: string): void;
    catchUp(elapsedSeconds: number): number;
    getUnits(): Unit[];
    addUnit(unit: Unit): void;
    updateUnit(unit: Unit): void;
    removeUnit(id: string): void;
    getNearbyStructures(point: GeoPoint, radiusMeters: number): Structure[];
    getNearbyUnits(point: GeoPoint, radiusMeters: number): Unit[];
}
