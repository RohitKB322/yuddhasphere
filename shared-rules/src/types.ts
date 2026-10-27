export interface GeoPoint {
  lat: number;
  lng: number;
  altitude?: number;
}

export type TeamId = string;

export interface Player {
  id: string;
  deviceId: string;
  callsign: string;
  team: TeamId;
  teamColor: string;
  position: GeoPoint;
  accuracy: number;
  wealth: number;
  incomePerHour: number;
  hp: number;
  maxHp: number;
  allianceId?: string;
  isOnline: boolean;
  lastActive: number;
  protectionExpiresAt?: number;
  bannedState: 'none' | 'warned' | 'temp_ban' | 'perm_ban';
}

export type StructureType =
  | 'village'
  | 'town'
  | 'city'
  | 'metropolis'
  | 'town_hall'
  | 'barracks'
  | 'airbase'
  | 'port'
  | 'mine'
  | 'sam'
  | 'radar'
  | 'wall';

export interface Structure {
  id: string;
  ownerId: string;
  ownerCallsign: string;
  team: TeamId;
  teamColor: string;
  type: StructureType;
  name: string;
  level: number;
  position: GeoPoint;
  hp: number;
  maxHp: number;
  radiusMeters: number;
  radiusType: 'territory' | 'influence' | 'rally' | 'operations' | 'gather' | 'defense' | 'vision' | 'fortify';
  online: boolean;
  createdAt: number;
  upgradeFinishedAt?: number;
  slots: {
    storedUnits: string[];
    queue: { itemId: string; finishesAt: number }[];
  };
  hangarJets?: Array<{
    id: string;
    catalogId: string;
    name: string;
    role: string;
    hp: number;
    maxHp: number;
    status: string;
  }>;
  samMunitions?: number;
}

export interface Unit {
  id: string;
  ownerId: string;
  team: TeamId;
  teamColor: string;
  catalogId: string;
  name: string;
  position: GeoPoint;
  originPosition: GeoPoint;
  targetPosition?: GeoPoint;
  hp: number;
  maxHp: number;
  speedMps: number;
  rangeMeters: number;
  fuelPercent: number;
  order: 'idle' | 'patrol' | 'sortie' | 'recall' | 'attack';
  homeBaseId: string;
  targetEntityId?: string;
}

export interface Projectile {
  id: string;
  type: 'interceptor' | 'cruise_missile' | 'artillery';
  launcherId: string;
  targetUnitId?: string;
  origin: GeoPoint;
  target: GeoPoint;
  launchedAt: number;
  impactAt: number;
  hitChance: number;
  resolved: boolean;
}

export interface LootItem {
  id: string;
  position: GeoPoint;
  credits: number;
  createdAt: number;
  expiresAt: number;
}

export type AllianceRole = 'Supreme Commander' | 'Financial Minister' | 'Foreign Minister' | 'Member';

export interface AllianceMember {
  playerId: string;
  callsign: string;
  role: AllianceRole;
  netWorth?: number;
  joinedAt: number;
}

export interface Alliance {
  id: string;
  name: string;
  tag: string;
  leaderId: string;
  leaderCallsign: string;
  memberIds: string[];
  members?: AllianceMember[];
  colorHex: string;
  motto: string;
  totalWealth: number;
  territorySqKm: number;
  taxRatePercent?: number;
  treasuryCredits?: number;
  createdAt: number;
}

export interface Treaty {
  id: string;
  allianceIdA: string;
  allianceIdB: string;
  status: 'pact' | 'neutral' | 'war';
  updatedAt: number;
}

export interface CombatReport {
  id: string;
  timestamp: number;
  category: 'combat' | 'economy' | 'radar' | 'diplomacy';
  title: string;
  detail: string;
  level: 'info' | 'warn' | 'danger';
  location?: GeoPoint;
}

export interface LeaderboardEntry {
  rank: number;
  id: string;
  name: string;
  tag: string;
  score: number;
  type: 'player' | 'alliance';
  metric: 'wealth' | 'territory' | 'xp';
}
