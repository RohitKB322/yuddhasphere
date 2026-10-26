import { MongoClient, Db, Collection } from 'mongodb';
import { Player, Structure, Alliance, Treaty, CombatReport } from '@yuddhasphere/shared-rules';
import { World } from './world';

export class DatabaseManager {
  private client: MongoClient | null = null;
  private db: Db | null = null;
  private isConnected: boolean = false;

  private colPlayers: Collection | null = null;
  private colStructures: Collection | null = null;
  private colAlliances: Collection | null = null;
  private colTreaties: Collection | null = null;
  private colReports: Collection | null = null;

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
        connectTimeoutMS: 10000
      });

      await this.client.connect();
      this.db = this.client.db('yuddhasphere');
      this.colPlayers = this.db.collection('players');
      this.colStructures = this.db.collection('structures');
      this.colAlliances = this.db.collection('alliances');
      this.colTreaties = this.db.collection('treaties');
      this.colReports = this.db.collection('reports');

      this.isConnected = true;
      console.log('[Database] Connected successfully to MongoDB Atlas! Permanent persistence ACTIVE.');
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

  public async savePlayer(player: Player): Promise<void> {
    if (!this.isConnected || !this.colPlayers) return;
    try {
      await this.colPlayers.updateOne(
        { id: player.id },
        { $set: player },
        { upsert: true }
      );
    } catch (err: any) {
      console.error(`[Database] Error saving player ${player.callsign}:`, err.message);
    }
  }

  public async saveStructure(structure: Structure): Promise<void> {
    if (!this.isConnected || !this.colStructures) return;
    try {
      await this.colStructures.updateOne(
        { id: structure.id },
        { $set: structure },
        { upsert: true }
      );
    } catch (err: any) {
      console.error(`[Database] Error saving structure ${structure.id}:`, err.message);
    }
  }

  public async removeStructure(structureId: string): Promise<void> {
    if (!this.isConnected || !this.colStructures) return;
    try {
      await this.colStructures.deleteOne({ id: structureId });
    } catch (err: any) {
      console.error(`[Database] Error deleting structure ${structureId}:`, err.message);
    }
  }

  public async saveAlliance(alliance: Alliance): Promise<void> {
    if (!this.isConnected || !this.colAlliances) return;
    try {
      await this.colAlliances.updateOne(
        { id: alliance.id },
        { $set: alliance },
        { upsert: true }
      );
    } catch (err: any) {
      console.error(`[Database] Error saving alliance ${alliance.tag}:`, err.message);
    }
  }

  public async removeAlliance(allianceId: string): Promise<void> {
    if (!this.isConnected || !this.colAlliances) return;
    try {
      await this.colAlliances.deleteOne({ id: allianceId });
    } catch (err: any) {
      console.error(`[Database] Error deleting alliance ${allianceId}:`, err.message);
    }
  }

  public async saveTreaty(treaty: Treaty): Promise<void> {
    if (!this.isConnected || !this.colTreaties) return;
    try {
      await this.colTreaties.updateOne(
        { id: treaty.id },
        { $set: treaty },
        { upsert: true }
      );
    } catch (err: any) {
      console.error(`[Database] Error saving treaty ${treaty.id}:`, err.message);
    }
  }

  public async saveReport(report: CombatReport): Promise<void> {
    if (!this.isConnected || !this.colReports) return;
    try {
      await this.colReports.updateOne(
        { id: report.id },
        { $set: report },
        { upsert: true }
      );
    } catch (err: any) {
      console.error(`[Database] Error saving report ${report.id}:`, err.message);
    }
  }

  public async saveFullWorld(world: World): Promise<void> {
    if (!this.isConnected) return;
    try {
      const players = Object.values(world.state.players);
      for (const p of players) {
        await this.savePlayer(p);
      }

      const structures = Object.values(world.state.structures);
      for (const s of structures) {
        await this.saveStructure(s);
      }

      const alliances = world.alliances.getAlliances();
      for (const a of alliances) {
        await this.saveAlliance(a);
      }

      const treaties = world.alliances.getTreaties();
      for (const t of treaties) {
        await this.saveTreaty(t);
      }
    } catch (err: any) {
      console.error('[Database] Periodic full save error:', err.message);
    }
  }

  public async close(): Promise<void> {
    if (this.client) {
      await this.client.close();
      this.isConnected = false;
    }
  }
}

export const dbManager = new DatabaseManager();
