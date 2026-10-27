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

    // 1. Live per-second wealth accumulation across all players in the world
    for (const player of Object.values(this.world.state.players)) {
      const perSec = (player.incomePerHour || 100) / 3600.0;
      player.wealth = (player.wealth || 0) + perSec;
    }

    // 2. Advance timers on structures (e.g. upgrades & production queues)
    for (const s of Object.values(this.world.state.structures)) {
      if (s.upgradeFinishedAt && now >= s.upgradeFinishedAt) {
        s.upgradeFinishedAt = undefined;
        s.level += 1;
        s.hp = s.maxHp = (s.maxHp || 200) + 150;
        dbManager.saveStructure(s);
      }
      if (s.slots && s.slots.queue && s.slots.queue.length > 0) {
        const ready = s.slots.queue.filter(q => q.finishesAt <= now);
        if (ready.length > 0) {
          s.slots.queue = s.slots.queue.filter(q => q.finishesAt > now);
          ready.forEach(r => s.slots.storedUnits.push(r.itemId));
          dbManager.saveStructure(s);
        }
      }
    }

    // 3. Periodic persistence save every 30 seconds (Local File + MongoDB)
    if (this.tickCount % 30 === 0) {
      this.world.state.lastTickTime = now;
      this.persistence.saveWorld(this.world);
      dbManager.saveFullWorld(this.world).catch(() => {});
    }

    // 4. Hourly timestamped backup (every 3600 seconds)
    if (this.tickCount % 3600 === 0) {
      this.persistence.createHourlyBackup(this.world);
    }
  }
}
