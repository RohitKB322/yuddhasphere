import { Alliance, CombatReport, GeoPoint, LeaderboardEntry, Player, Structure, StructureType, Treaty, Unit } from './types';
export type ClientCommand = {
    type: 'Register';
    deviceId: string;
    callsign: string;
    requestedTeam?: string;
} | {
    type: 'ReportLocation';
    lat: number;
    lng: number;
    accuracy: number;
    timestamp: number;
} | {
    type: 'BuildStructure';
    structureType: StructureType;
    lat: number;
    lng: number;
} | {
    type: 'UpgradeStructure';
    structureId: string;
} | {
    type: 'SellStructure';
    structureId: string;
} | {
    type: 'RepairStructure';
    structureId: string;
} | {
    type: 'BuyItem';
    structureId: string;
    catalogId: string;
} | {
    type: 'LaunchSortie';
    airbaseId: string;
    unitId: string;
    targetLat: number;
    targetLng: number;
} | {
    type: 'CreateAlliance';
    name: string;
    tag: string;
    motto?: string;
    colorHex?: string;
} | {
    type: 'JoinAlliance';
    allianceId: string;
} | {
    type: 'LeaveAlliance';
} | {
    type: 'SetTaxRate';
    taxPercent: number;
} | {
    type: 'SetMemberRole';
    targetPlayerId: string;
    role: 'Supreme Commander' | 'Financial Minister' | 'Foreign Minister' | 'Member';
} | {
    type: 'ProposeTreaty';
    targetAllianceId: string;
    status: 'pact' | 'neutral' | 'war';
} | {
    type: 'GetLeaderboards';
    metric?: 'wealth' | 'territory' | 'xp';
} | {
    type: 'Respawn';
};
export type ServerMessage = {
    type: 'InitSync';
    player: Player;
    structures: Structure[];
    units: Unit[];
    serverTime: number;
    alliance?: Alliance | null;
} | {
    type: 'StateDiff';
    serverTime: number;
    updatedStructures?: Structure[];
    removedStructureIds?: string[];
    updatedUnits?: Unit[];
    removedUnitIds?: string[];
    playerUpdate?: Partial<Player>;
} | {
    type: 'AllianceUpdate';
    alliance: Alliance | null;
    members?: Player[];
    treaties?: Treaty[];
} | {
    type: 'ReportsUpdate';
    reports: CombatReport[];
} | {
    type: 'LeaderboardSync';
    entries: LeaderboardEntry[];
    metric: string;
} | {
    type: 'Alert';
    level: 'info' | 'warn' | 'danger';
    text: string;
    location?: GeoPoint;
} | {
    type: 'Error';
    code: string;
    message: string;
};
