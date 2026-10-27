import { World } from './world';
export declare class PersistenceManager {
    private dataDir;
    private worldFilePath;
    private backupsDir;
    private loadSucceeded;
    constructor(dataDir?: string);
    loadWorld(world: World): boolean;
    setLoaded(loaded?: boolean): void;
    saveWorld(world: World): boolean;
    createHourlyBackup(world: World): void;
}
