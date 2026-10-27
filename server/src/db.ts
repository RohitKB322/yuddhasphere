import { MongoClient, Db, Collection } from 'mongodb';
import { Player, Structure, Alliance, Treaty, CombatReport } from '@yuddhasphere/shared-rules';
import { World } from './world';

export class DatabaseManager {
  private client: MongoClient | null = null;
  private db: Db | null = null;
  public isConnected: boolean = false;

  private colPlayers: Collection | null = null;
  private colStructures: Collection | null = null;
  private colAlliances: Collection | null = null;
  private colTreaties: Collection | null = null;
  private colReports: Collection | null = null;

  // In-Memory Dirty Write-Behind Buffer (Zero-latency to live game loop)
  private dirtyPlayers: Map<string, Player> = new Map();
  private dirtyStructures: Map<string, Structure> = new Map();
  private dirtyAlliances: Map<string, Alliance> = new Map();
  private dirtyTreaties: Map<string, Treaty> = new Map();
  private dirtyReports: CombatReport[] = [];

  private removedStructureIds: Set<string> = new Set();
  private removedAllianceIds: Set<string> = new Set();

  private isFlushing: boolean = false;
  private flushTimer: NodeJS.Timeout | null = null;

  public async connect(uri?: string): Promise<boolean> {
    const mongoUri = uri || process.env.MONGODB_URI;

    if (!mongoUri) {
      console.log('[Database] No MONGODB_URI provided. Running in local file/fallback mode.');
      return false;
    }

    try {
      console.log('[Database] Connecting to MongoDB Atlas...');
      this.client = new MongoClient(mongoUri, {
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
    } catch (err: any) {
      console.error('[Database] Failed to connect to MongoDB Atlas:', err.message);
      this.isConnected = false;
      return false;
    }
  }

  public async loadWorldState(world: World): Promise<boolean> {
    if (!this.isConnected || !this.db) {
      return false;
    }

    try {
      console.log('[Database] Restoring world state from MongoDB...');

      // 1. Load players
      const players = await this.colPlayers!.find({}).toArray();
      players.forEach((p: any) => {
        delete p._id;
        world.state.players[p.id] = p as Player;
      });

      // 2. Load structures
      const structures = await this.colStructures!.find({}).toArray();
      structures.forEach((s: any) => {
        delete s._id;
        world.state.structures[s.id] = s as Structure;
        world.spatialStructures.insert(s as Structure);
      });

      // 3. Load alliances
      const alliances = await this.colAlliances!.find({}).toArray();
      alliances.forEach((a: any) => {
        delete a._id;
        (world.alliances as any).alliances.set(a.id, a as Alliance);
      });

      // 4. Load treaties
      const treaties = await this.colTreaties!.find({}).toArray();
      treaties.forEach((t: any) => {
        delete t._id;
        const key = [t.allianceIdA, t.allianceIdB].sort().join(':');
        (world.alliances as any).treaties.set(key, t as Treaty);
      });

      // 5. Load reports
      const reports = await this.colReports!.find({}).sort({ timestamp: -1 }).limit(50).toArray();
      const reportList: CombatReport[] = reports.map((r: any) => {
        delete r._id;
        return r as CombatReport;
      });
      if (reportList.length > 0) {
        (world.reports as any).reports = reportList;
      }

      console.log(`[Database] World restored: ${players.length} players, ${structures.length} structures, ${alliances.length} alliances, ${treaties.length} treaties.`);
      return true;
    } catch (err: any) {
      console.error('[Database] Error loading world from MongoDB:', err.message);
      return false;
    }
  }

  // Buffers updates in memory (instant 0ms latency to caller)
  public savePlayer(player: Player): void {
    this.dirtyPlayers.set(player.id, { ...player });
  }

  public saveStructure(structure: Structure): void {
    this.dirtyStructures.set(structure.id, { ...structure });
    this.removedStructureIds.delete(structure.id);
  }

  public removeStructure(structureId: string): void {
    this.dirtyStructures.delete(structureId);
    this.removedStructureIds.add(structureId);
  }

  public saveAlliance(alliance: Alliance): void {
    this.dirtyAlliances.set(alliance.id, { ...alliance });
    this.removedAllianceIds.delete(alliance.id);
  }

  public removeAlliance(allianceId: string): void {
    this.dirtyAlliances.delete(allianceId);
    this.removedAllianceIds.add(allianceId);
  }

  public saveTreaty(treaty: Treaty): void {
    this.dirtyTreaties.set(treaty.id, { ...treaty });
  }

  public saveReport(report: CombatReport): void {
    this.dirtyReports.push({ ...report });
  }

  // Flushes an individual player immediately (e.g. on disconnect)
  public async flushPlayerImmediate(playerId: string, player?: Player): Promise<void> {
    if (!this.isConnected || !this.colPlayers) return;
    const p = player || this.dirtyPlayers.get(playerId);
    if (!p) return;
    this.dirtyPlayers.delete(playerId);
    try {
      await this.colPlayers.updateOne(
        { id: p.id },
        { $set: p },
        { upsert: true }
      );
    } catch (err: any) {
      console.error(`[Database] Error saving player ${playerId}:`, err.message);
    }
  }

  // Non-blocking bulk write-behind
  public async flushDirty(): Promise<void> {
    if (!this.isConnected || this.isFlushing) return;

    const totalDirty = this.dirtyPlayers.size +
      this.dirtyStructures.size +
      this.removedStructureIds.size +
      this.dirtyAlliances.size +
      this.removedAllianceIds.size +
      this.dirtyTreaties.size +
      this.dirtyReports.length;

    if (totalDirty === 0) return;

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
        const ops: any[] = structuresToFlush.map(s => ({
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
        const ops: any[] = alliancesToFlush.map(a => ({
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
    } catch (err: any) {
      console.error('[Database] Background bulkWrite error:', err.message);
    } finally {
      this.isFlushing = false;
    }
  }

  // Periodic or bootstrap full snapshot using bulkWrite
  public async saveFullWorld(world: World): Promise<void> {
    if (!this.isConnected) return;
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
    } catch (err: any) {
      console.error('[Database] Full snapshot bulk save error:', err.message);
    }
  }

  public async close(): Promise<void> {
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

export const dbManager = new DatabaseManager();
