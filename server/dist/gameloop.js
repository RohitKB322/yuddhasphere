"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.GameLoop = void 0;
class GameLoop {
    world;
    persistence;
    intervalHandle = null;
    tickCount = 0;
    constructor(world, persistence) {
        this.world = world;
        this.persistence = persistence;
    }
    start() {
        if (this.intervalHandle)
            return;
        this.intervalHandle = setInterval(() => this.tick(), 1000);
        console.log('[GameLoop] 1-second authoritative tick loop active.');
    }
    stop() {
        if (this.intervalHandle) {
            clearInterval(this.intervalHandle);
            this.intervalHandle = null;
        }
    }
    tick() {
        this.tickCount++;
        const now = Date.now();
        // 1. Advance timers on structures (e.g. upgrades)
        for (const s of Object.values(this.world.state.structures)) {
            if (s.upgradeFinishedAt && now >= s.upgradeFinishedAt) {
                s.upgradeFinishedAt = undefined;
                // Level completion logic
            }
        }
        // 2. Periodic atomic persistence save every 30 seconds
        if (this.tickCount % 30 === 0) {
            this.persistence.saveWorld(this.world);
        }
        // 3. Hourly timestamped backup (every 3600 seconds)
        if (this.tickCount % 3600 === 0) {
            this.persistence.createHourlyBackup(this.world);
        }
    }
}
exports.GameLoop = GameLoop;
