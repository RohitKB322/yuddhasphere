import { World } from './world';
import { PersistenceManager } from './persistence';
import { dbManager } from './db';

export class GameLoop {
  private world: World;
  private persistence: PersistenceManager;
  private intervalHandle: NodeJS.Timeout | null = null;
  private tickCount: number = 0;

  constructor(world: World, persistence: PersistenceManager) {
    this.world = world;
    this.persistence = persistence;
  }

  public start(): void {
    if (this.intervalHandle) return;
    this.intervalHandle = setInterval(() => this.tick(), 1000);
    console.log('[GameLoop] 1-second authoritative tick loop active.');
  }

  public stop(): void {
    if (this.intervalHandle) {
      clearInterval(this.intervalHandle);
      this.intervalHandle = null;
    }
  }

  private tick(): void {
    this.tickCount++;
    const now = Date.now();

    // 1. Advance timers on structures (e.g. upgrades)
    for (const s of Object.values(this.world.state.structures)) {
      if (s.upgradeFinishedAt && now >= s.upgradeFinishedAt) {
        s.upgradeFinishedAt = undefined;
        // Level completion logic
      }
    }

    // 2. Periodic persistence save every 30 seconds (Local File + MongoDB)
    if (this.tickCount % 30 === 0) {
      this.persistence.saveWorld(this.world);
      dbManager.saveFullWorld(this.world).catch(() => {});
    }

    // 3. Hourly timestamped backup (every 3600 seconds)
    if (this.tickCount % 3600 === 0) {
      this.persistence.createHourlyBackup(this.world);
    }
  }
}
