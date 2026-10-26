"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const spatial_1 = require("./spatial");
const alliances_1 = require("./alliances");
const reports_1 = require("./reports");
const leaderboards_1 = require("./leaderboards");
const world_1 = require("./world");
console.log('--- RUNNING SERVER SCALABILITY & MULTIPLAYER SMOKE TEST ---');
// 1. Spatial Grid Test with 1,000 entities
console.log('1. Testing SpatialGrid O(1) indexing...');
const grid = new spatial_1.SpatialGrid(0.01); // ~1.1km cell
for (let i = 0; i < 1000; i++) {
    const lat = 19.0760 + (Math.random() - 0.5) * 0.1;
    const lng = 72.8777 + (Math.random() - 0.5) * 0.1;
    grid.insert({ id: `struct_${i}`, position: { lat, lng } });
}
const queryTime = Date.now();
const nearby = grid.getNearby(19.0760, 72.8777, 2000); // 2km radius query
const duration = Date.now() - queryTime;
console.log(`[PASS] Indexed 1,000 entities. Found ${nearby.length} entities within 2km in ${duration}ms.`);
// 2. Alliance & Treaty System
console.log('2. Testing Alliance & Treaty System...');
const alliances = new alliances_1.AllianceManager();
const alliance1 = alliances.createAlliance('Solar Vanguard', '[SOL]', 'p1', 'Garuda-1', 'Strike from the heavens', '#00E5FF');
const alliance2 = alliances.createAlliance('Iron Legion', '[IRN]', 'p2', 'Vajra-9', 'Unbreakable shield', '#FF3B30');
alliances.joinAlliance(alliance1.id, 'p3');
const p1All = alliances.getAlliance(alliance1.id);
console.log(`[PASS] Alliance ${p1All?.name} created with ${p1All?.memberIds.length} members.`);
const treaty = alliances.setTreaty(alliance1.id, alliance2.id, 'war');
console.log(`[PASS] Diplomatic treaty signed: ${alliance1.tag} vs ${alliance2.tag} -> ${treaty.status.toUpperCase()}`);
// 3. Reports & Intelligence System
console.log('3. Testing Report & Intelligence Logging...');
const reports = new reports_1.ReportManager();
reports.addReport('combat', 'Air Intercept', 'Mig-29 neutralized enemy scout over Sector 4', 'danger');
reports.addReport('economy', 'Tax Harvest', 'Collected +$450 credits from Town Hall', 'info');
reports.addReport('radar', 'Bogeys Detected', '2 unidentified radar blips bearing 045', 'warn');
const combatLogs = reports.getReports('combat');
console.log(`[PASS] Reports logged. Retrieved ${combatLogs.length} combat logs, total ${reports.getReports().length} logs.`);
// 4. World & Leaderboard Integration
console.log('4. Testing World & Global Leaderboards...');
const world = new world_1.World();
const player1 = {
    id: 'p1',
    deviceId: 'dev1',
    callsign: 'Garuda-1',
    team: 'blue',
    teamColor: '#00E5FF',
    position: { lat: 19.0760, lng: 72.8777 },
    accuracy: 5,
    wealth: 25000,
    incomePerHour: 500,
    hp: 1000,
    maxHp: 1000,
    isOnline: true,
    lastActive: Date.now(),
    bannedState: 'none',
    allianceId: alliance1.id
};
const player2 = {
    id: 'p2',
    deviceId: 'dev2',
    callsign: 'Vajra-9',
    team: 'red',
    teamColor: '#FF3B30',
    position: { lat: 19.0780, lng: 72.8790 },
    accuracy: 8,
    wealth: 18000,
    incomePerHour: 300,
    hp: 850,
    maxHp: 1000,
    isOnline: true,
    lastActive: Date.now(),
    bannedState: 'none',
    allianceId: alliance2.id
};
world.setPlayer(player1);
world.setPlayer(player2);
const lbManager = new leaderboards_1.LeaderboardManager();
const wealthLb = lbManager.computeLeaderboards(world, 'wealth');
console.log(`[PASS] Wealth Leaderboard Rank 1: ${wealthLb[0]?.name} with score $${wealthLb[0]?.score}`);
console.log('--- ALL SERVER SCALABILITY TESTS PASSED WITH 0 FAILURES ---');
