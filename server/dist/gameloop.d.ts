import { World } from './world';
import { PersistenceManager } from './persistence';
export declare class GameLoop {
    private world;
    private persistence;
    private intervalHandle;
    private tickCount;
    constructor(world: World, persistence: PersistenceManager);
    start(): void;
    stop(): void;
    private tick;
}
