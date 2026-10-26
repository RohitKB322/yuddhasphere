import { Player, Structure, Alliance, Treaty, CombatReport } from '@yuddhasphere/shared-rules';
import { World } from './world';
export declare class DatabaseManager {
    private client;
    private db;
    private isConnected;
    private colPlayers;
    private colStructures;
    private colAlliances;
    private colTreaties;
    private colReports;
    connect(uri?: string): Promise<boolean>;
    loadWorldState(world: World): Promise<boolean>;
    savePlayer(player: Player): Promise<void>;
    saveStructure(structure: Structure): Promise<void>;
    removeStructure(structureId: string): Promise<void>;
    saveAlliance(alliance: Alliance): Promise<void>;
    removeAlliance(allianceId: string): Promise<void>;
    saveTreaty(treaty: Treaty): Promise<void>;
    saveReport(report: CombatReport): Promise<void>;
    saveFullWorld(world: World): Promise<void>;
    close(): Promise<void>;
}
export declare const dbManager: DatabaseManager;
