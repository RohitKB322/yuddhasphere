import { World } from './world';
export declare class PersistenceManager {
    private dataDir;
    private worldFilePath;
    private backupsDir;
    private loadSucceeded;
    constructor(dataDir?: string);
    loadWorld(world: World): boolean;
    saveWorld(world: World): boolean;
    createHourlyBackup(world: World): void;
}
