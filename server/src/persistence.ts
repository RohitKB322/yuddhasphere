import * as fs from 'fs';
import * as path from 'path';
import { World } from './world';

export class PersistenceManager {
  private dataDir: string;
  private worldFilePath: string;
  private backupsDir: string;
  private loadSucceeded: boolean = false;

  constructor(dataDir: string = path.join(__dirname, '..', 'data')) {
    this.dataDir = dataDir;
    this.worldFilePath = path.join(dataDir, 'world.json');
    this.backupsDir = path.join(dataDir, 'backups');

    if (!fs.existsSync(this.dataDir)) {
      fs.mkdirSync(this.dataDir, { recursive: true });
    }
    if (!fs.existsSync(this.backupsDir)) {
      fs.mkdirSync(this.backupsDir, { recursive: true });
    }
  }

  public loadWorld(world: World): boolean {
    if (!fs.existsSync(this.worldFilePath)) {
      console.log('[Persistence] No world.json found. Initializing fresh world.');
      this.loadSucceeded = true;
      return true;
    }

    try {
      const raw = fs.readFileSync(this.worldFilePath, 'utf-8');
      const parsed = JSON.parse(raw);
      world.state = parsed;
      this.loadSucceeded = true;
      console.log(`[Persistence] Successfully loaded world (version ${world.state.version})`);
      return true;
    } catch (err) {
      console.error('[Persistence] FATAL: Error reading world.json. Refusing to overwrite.', err);
      this.loadSucceeded = false;
      return false;
    }
  }

  public saveWorld(world: World): boolean {
    if (!this.loadSucceeded) {
      console.error('[Persistence] Save aborted: startup load previously failed. Protecting data.');
      return false;
    }

    const tempPath = path.join(this.dataDir, `world.tmp.${Date.now()}`);
    try {
      world.state.lastTickTime = Date.now();
      const serialized = JSON.stringify(world.state, null, 2);

      // 1. Atomic write to temp file
      fs.writeFileSync(tempPath, serialized, 'utf-8');

      // 2. Rename temp file to target
      fs.renameSync(tempPath, this.worldFilePath);
      return true;
    } catch (err) {
      console.error('[Persistence] Error saving world state:', err);
      if (fs.existsSync(tempPath)) {
        try { fs.unlinkSync(tempPath); } catch {}
      }
      return false;
    }
  }

  public createHourlyBackup(world: World): void {
    if (!this.loadSucceeded) return;
    try {
      const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
      const backupPath = path.join(this.backupsDir, `world-${timestamp}.json`);
      fs.copyFileSync(this.worldFilePath, backupPath);
      console.log(`[Persistence] Hourly backup created: ${backupPath}`);
    } catch (err) {
      console.error('[Persistence] Backup error:', err);
    }
  }
}
