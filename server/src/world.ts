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

export class World {
  public state: WorldState = {
    version: 1,
    lastTickTime: Date.now(),
    players: {},
    structures: {},
    units: {},
    projectiles: {},
    loot: {}
  };

  public spatialStructures = new SpatialGrid<Structure>(0.01);
  public spatialUnits = new SpatialGrid<Unit>(0.01);
  public alliances = new AllianceManager();
  public reports = new ReportManager();
  public leaderboards = new LeaderboardManager();

  public getPlayer(id: string): Player | undefined {
    return this.state.players[id];
  }

  public setPlayer(player: Player): void {
    this.state.players[player.id] = player;
  }

  public getStructures(): Structure[] {
    return Object.values(this.state.structures);
  }

  public addStructure(structure: Structure): void {
    this.state.structures[structure.id] = structure;
    this.spatialStructures.insert(structure);
  }

  public removeStructure(id: string): void {
    delete this.state.structures[id];
    this.spatialStructures.remove(id);
  }

  public getUnits(): Unit[] {
    return Object.values(this.state.units);
  }

  public addUnit(unit: Unit): void {
    this.state.units[unit.id] = unit;
    this.spatialUnits.insert(unit);
  }

  public updateUnit(unit: Unit): void {
    this.state.units[unit.id] = unit;
    this.spatialUnits.update(unit);
  }

  public removeUnit(id: string): void {
    delete this.state.units[id];
    this.spatialUnits.remove(id);
  }

  public getNearbyStructures(point: GeoPoint, radiusMeters: number): Structure[] {
    return this.spatialStructures.getNearby(point.lat, point.lng, radiusMeters);
  }

  public getNearbyUnits(point: GeoPoint, radiusMeters: number): Unit[] {
    return this.spatialUnits.getNearby(point.lat, point.lng, radiusMeters);
  }
}
