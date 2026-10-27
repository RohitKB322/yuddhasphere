"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const http_1 = __importDefault(require("http"));
const ws_1 = require("ws");
const world_1 = require("./world");
const persistence_1 = require("./persistence");
const gameloop_1 = require("./gameloop");
const db_1 = require("./db");
const PORT = parseInt(process.env.PORT || '8080', 10);
const world = new world_1.World();
const persistence = new persistence_1.PersistenceManager();
const gameLoop = new gameloop_1.GameLoop(world, persistence);
async function bootstrap() {
    persistence.loadWorld(world);
    const dbConnected = await db_1.dbManager.connect();
    if (dbConnected) {
        await db_1.dbManager.loadWorldState(world);
        persistence.setLoaded(true);
    }
    // Global world catch-up: compute offline income & timers across all players
    const now = Date.now();
    const lastTick = world.state.lastTickTime || now;
    const elapsedSeconds = Math.max(0, Math.floor((now - lastTick) / 1000));
    if (elapsedSeconds > 1) {
        world.catchUp(elapsedSeconds);
        if (dbConnected) {
            await db_1.dbManager.saveFullWorld(world);
        }
    }
    persistence.saveWorld(world);
    gameLoop.start();
}
bootstrap();
const server = http_1.default.createServer((req, res) => {
    res.writeHead(200, {
        'Content-Type': 'application/json',
        'Access-Control-Allow-Origin': '*'
    });
    res.end(JSON.stringify({
        status: 'ONLINE',
        game: 'YuddhaSphere Authoritative RTS Server',
        version: '0.5.2',
        port: PORT,
        playersOnline: clients.size,
        dbConnected: db_1.dbManager.isConnected || false,
        serverTime: Date.now()
    }, null, 2));
});
const wss = new ws_1.WebSocketServer({ server });
server.listen(PORT, '0.0.0.0', () => {
    console.log(`[Server] YuddhaSphere authoritative server running on http://0.0.0.0:${PORT} & ws://0.0.0.0:${PORT}`);
});
const clients = new Map();
const buildingCosts = {
    village: { cost: 350, income: 50 },
    town: { cost: 800, income: 150 },
    city: { cost: 2500, income: 400 },
    metropolis: { cost: 8000, income: 1000 },
    town_hall: { cost: 1500, income: 100 },
    barracks: { cost: 600, income: 0 },
    airbase: { cost: 1800, income: 0 },
    port: { cost: 1200, income: 100 },
    mine: { cost: 500, income: 120 },
    sam: { cost: 900, income: 0 },
    radar: { cost: 750, income: 0 },
    wall: { cost: 150, income: 0 }
};
function recalculatePlayerIncome(player) {
    let total = 100; // Base territory HQ income
    for (const s of Object.values(world.state.structures)) {
        if (s.ownerId === player.id && s.online) {
            const meta = buildingCosts[s.type];
            if (meta) {
                total += meta.income * s.level;
            }
        }
    }
    player.incomePerHour = total;
}
function broadcast(msg, excludeWs) {
    const data = JSON.stringify(msg);
    for (const [clientWs] of clients) {
        if (clientWs !== excludeWs && clientWs.readyState === ws_1.WebSocket.OPEN) {
            clientWs.send(data);
        }
    }
}
wss.on('connection', (ws) => {
    console.log('[Server] Client connected.');
    ws.on('message', (data) => {
        try {
            const command = JSON.parse(data.toString());
            handleCommand(ws, command);
        }
        catch (err) {
            console.error('[Server] Invalid message received:', err);
        }
    });
    ws.on('close', () => {
        const playerId = clients.get(ws);
        if (playerId) {
            console.log(`[Server] Client disconnected: ${playerId}`);
            clients.delete(ws);
        }
    });
});
function handleCommand(ws, command) {
    switch (command.type) {
        case 'Register': {
            let player = world.getPlayer(command.deviceId);
            const now = Date.now();
            let offlineEarned = 0;
            if (!player) {
                player = {
                    id: command.deviceId,
                    deviceId: command.deviceId,
                    callsign: command.callsign || 'Commander',
                    team: command.requestedTeam || 'blue',
                    teamColor: '#00E5FF',
                    position: { lat: 19.0760, lng: 72.8777 },
                    accuracy: 10,
                    wealth: 10000,
                    incomePerHour: 100,
                    hp: 1000,
                    maxHp: 1000,
                    isOnline: true,
                    lastActive: now,
                    bannedState: 'none'
                };
                world.setPlayer(player);
            }
            else {
                if (command.callsign && command.callsign.trim().length > 0) {
                    player.callsign = command.callsign;
                }
                // Authoritatively recalculate current income based on all owned active structures
                recalculatePlayerIncome(player);
                player.isOnline = true;
                if (player.lastActive && now > player.lastActive) {
                    const offlineHours = Math.min((now - player.lastActive) / 3600000, 168);
                    if (offlineHours > 0.005) { // Accrue if away for > 18 seconds
                        offlineEarned = Math.floor((player.incomePerHour || 100) * offlineHours);
                        player.wealth = Math.floor(player.wealth || 0) + offlineEarned;
                    }
                }
                player.lastActive = now;
            }
            db_1.dbManager.savePlayer(player);
            clients.set(ws, player.id);
            // Send InitSync with world snapshot and alliance
            const initMsg = {
                type: 'InitSync',
                player: { ...player, wealth: Math.floor(player.wealth) },
                structures: world.getStructures(),
                units: world.getUnits(),
                serverTime: now,
                alliance: player.allianceId ? world.alliances.getAlliance(player.allianceId) : null
            };
            ws.send(JSON.stringify(initMsg));
            if (offlineEarned > 0) {
                ws.send(JSON.stringify({
                    type: 'Alert',
                    level: 'info',
                    text: `Welcome back, Commander! Your territory generated +$${offlineEarned} while you were offline.`
                }));
            }
            console.log(`[Server] Commander registered: ${player.callsign} (${player.id}), wealth: $${Math.floor(player.wealth)}, rate: +$${player.incomePerHour}/h, structures: ${world.getStructures().length}`);
            break;
        }
        case 'ReportLocation': {
            const playerId = clients.get(ws);
            if (playerId) {
                const player = world.getPlayer(playerId);
                if (player) {
                    player.position = { lat: command.lat, lng: command.lng };
                    player.accuracy = command.accuracy;
                    player.lastActive = Date.now();
                }
            }
            break;
        }
        case 'BuildStructure': {
            const playerId = clients.get(ws);
            const player = playerId ? world.getPlayer(playerId) : undefined;
            const structId = `str_${Date.now().toString(36)}_${Math.random().toString(36).substring(2, 6)}`;
            const meta = buildingCosts[command.structureType] || { cost: 300, income: 50 };
            if (player) {
                player.wealth = Math.max(0, (player.wealth || 10000) - meta.cost);
            }
            const newStructure = {
                id: structId,
                ownerId: player ? player.id : (playerId || 'p_commander'),
                ownerCallsign: player ? player.callsign : 'Commander',
                team: player ? player.team : 'blue',
                teamColor: player ? player.teamColor : '#00E5FF',
                type: command.structureType,
                name: command.structureType.replace('_', ' ').toUpperCase(),
                level: 1,
                position: { lat: command.lat, lng: command.lng },
                hp: 200,
                maxHp: 200,
                radiusMeters: 250,
                radiusType: 'influence',
                online: true,
                createdAt: Date.now(),
                slots: { storedUnits: [], queue: [] },
                hangarJets: [],
                samMunitions: command.structureType === 'sam' ? 6 : 0
            };
            world.addStructure(newStructure);
            if (player) {
                recalculatePlayerIncome(player);
                db_1.dbManager.savePlayer(player);
            }
            db_1.dbManager.saveStructure(newStructure);
            persistence.saveWorld(world);
            console.log(`[Server] Built structure ${newStructure.name} for ${newStructure.ownerCallsign}. Player wealth: $${player ? Math.floor(player.wealth) : 'N/A'}`);
            world.reports.addReport('economy', `Construction Completed`, `Constructed ${newStructure.name} at coordinates.`, 'info');
            // Broadcast state difference to all connected players
            broadcast({
                type: 'StateDiff',
                serverTime: Date.now(),
                updatedStructures: [newStructure],
                playerUpdate: player ? { wealth: Math.floor(player.wealth), incomePerHour: player.incomePerHour } : undefined
            });
            break;
        }
        case 'UpgradeStructure': {
            const s = world.state.structures[command.structureId];
            if (s) {
                const playerId = clients.get(ws);
                const player = playerId ? world.getPlayer(playerId) : undefined;
                const upgradeCost = s.level === 1 ? 800 : (s.level === 2 ? 2500 : 8000);
                if (player) {
                    player.wealth = Math.max(0, (player.wealth || 0) - upgradeCost);
                    recalculatePlayerIncome(player);
                    db_1.dbManager.savePlayer(player);
                }
                s.level += 1;
                s.hp = s.maxHp = (s.maxHp || 200) + 150;
                db_1.dbManager.saveStructure(s);
                persistence.saveWorld(world);
                broadcast({
                    type: 'StateDiff',
                    serverTime: Date.now(),
                    updatedStructures: [s],
                    playerUpdate: player ? { wealth: Math.floor(player.wealth), incomePerHour: player.incomePerHour } : undefined
                });
            }
            break;
        }
        case 'SellStructure': {
            const s = world.state.structures[command.structureId];
            if (s) {
                const playerId = clients.get(ws);
                const player = playerId ? world.getPlayer(playerId) : undefined;
                const refund = Math.floor((buildingCosts[s.type]?.cost || 300) * 0.5);
                if (player) {
                    player.wealth = (player.wealth || 0) + refund;
                    recalculatePlayerIncome(player);
                    db_1.dbManager.savePlayer(player);
                }
                world.removeStructure(command.structureId);
                db_1.dbManager.removeStructure(command.structureId);
                persistence.saveWorld(world);
                broadcast({
                    type: 'StateDiff',
                    serverTime: Date.now(),
                    removedStructureIds: [command.structureId],
                    playerUpdate: player ? { wealth: Math.floor(player.wealth), incomePerHour: player.incomePerHour } : undefined
                });
            }
            break;
        }
        case 'BuyItem': {
            const s = world.state.structures[command.structureId];
            if (s) {
                const playerId = clients.get(ws);
                const player = playerId ? world.getPlayer(playerId) : undefined;
                const jetCosts = {
                    jet_f16: 900,
                    jet_mirage2000: 850,
                    jet_f15: 1200,
                    jet_su27: 1100,
                    jet_a10: 800
                };
                const cost = jetCosts[command.catalogId] || 800;
                if (player) {
                    player.wealth = Math.max(0, (player.wealth || 0) - cost);
                    db_1.dbManager.savePlayer(player);
                }
                if (!s.hangarJets)
                    s.hangarJets = [];
                const jet = {
                    id: `jet_${Date.now().toString(36)}_${Math.random().toString(36).substring(2, 6)}`,
                    catalogId: command.catalogId,
                    name: command.catalogId.replace('jet_', '').toUpperCase(),
                    role: 'Fighter',
                    hp: 100,
                    maxHp: 100,
                    status: 'Ready in Hangar'
                };
                s.hangarJets.push(jet);
                db_1.dbManager.saveStructure(s);
                persistence.saveWorld(world);
                broadcast({
                    type: 'StateDiff',
                    serverTime: Date.now(),
                    updatedStructures: [s],
                    playerUpdate: player ? { wealth: Math.floor(player.wealth), incomePerHour: player.incomePerHour } : undefined
                });
            }
            break;
        }
        case 'RepairStructure': {
            const s = world.state.structures[command.structureId];
            if (s) {
                const playerId = clients.get(ws);
                const player = playerId ? world.getPlayer(playerId) : undefined;
                if (player && player.wealth >= 50) {
                    player.wealth -= 50;
                    db_1.dbManager.savePlayer(player);
                }
                s.hp = s.maxHp;
                db_1.dbManager.saveStructure(s);
                persistence.saveWorld(world);
                broadcast({
                    type: 'StateDiff',
                    serverTime: Date.now(),
                    updatedStructures: [s],
                    playerUpdate: player ? { wealth: Math.floor(player.wealth), incomePerHour: player.incomePerHour } : undefined
                });
            }
            break;
        }
        case 'CreateAlliance': {
            const playerId = clients.get(ws);
            const player = playerId ? world.getPlayer(playerId) : undefined;
            if (player) {
                const alliance = world.alliances.createAlliance(command.name, command.tag, player.id, player.callsign, command.motto, command.colorHex || '#00E5FF');
                player.allianceId = alliance.id;
                db_1.dbManager.saveAlliance(alliance);
                db_1.dbManager.savePlayer(player);
                world.reports.addReport('diplomacy', 'Alliance Founded', `Commander ${player.callsign} established alliance ${alliance.name} ${alliance.tag}.`, 'info');
                ws.send(JSON.stringify({
                    type: 'AllianceUpdate',
                    alliance,
                    members: [player],
                    treaties: world.alliances.getTreaties()
                }));
            }
            break;
        }
        case 'JoinAlliance': {
            const playerId = clients.get(ws);
            const player = playerId ? world.getPlayer(playerId) : undefined;
            if (player && command.allianceId) {
                const success = world.alliances.joinAlliance(command.allianceId, player.id);
                if (success) {
                    player.allianceId = command.allianceId;
                    const all = world.alliances.getAlliance(command.allianceId);
                    if (all)
                        db_1.dbManager.saveAlliance(all);
                    db_1.dbManager.savePlayer(player);
                    world.reports.addReport('diplomacy', 'Alliance Member Joined', `${player.callsign} enlisted into ${all?.name} ${all?.tag}.`, 'info');
                    ws.send(JSON.stringify({
                        type: 'AllianceUpdate',
                        alliance: all,
                        treaties: world.alliances.getTreaties()
                    }));
                }
            }
            break;
        }
        case 'ProposeTreaty': {
            const playerId = clients.get(ws);
            const player = playerId ? world.getPlayer(playerId) : undefined;
            if (player && player.allianceId) {
                const treaty = world.alliances.setTreaty(player.allianceId, command.targetAllianceId, command.status);
                db_1.dbManager.saveTreaty(treaty);
                world.reports.addReport('diplomacy', 'Diplomatic Treaty Ratified', `Status between factions updated to ${command.status.toUpperCase()}.`, command.status === 'war' ? 'danger' : 'info');
                broadcast({
                    type: 'AllianceUpdate',
                    alliance: world.alliances.getAlliance(player.allianceId) || null,
                    treaties: world.alliances.getTreaties()
                });
            }
            break;
        }
        case 'LeaveAlliance': {
            const playerId = clients.get(ws);
            const player = playerId ? world.getPlayer(playerId) : undefined;
            if (player && player.allianceId) {
                const oldAllId = player.allianceId;
                world.alliances.leaveAlliance(oldAllId, player.id);
                player.allianceId = undefined;
                const all = world.alliances.getAlliance(oldAllId);
                if (all)
                    db_1.dbManager.saveAlliance(all);
                else
                    db_1.dbManager.removeAlliance(oldAllId);
                db_1.dbManager.savePlayer(player);
                ws.send(JSON.stringify({
                    type: 'AllianceUpdate',
                    alliance: null,
                    treaties: world.alliances.getTreaties()
                }));
            }
            break;
        }
        case 'SetTaxRate': {
            const playerId = clients.get(ws);
            const player = playerId ? world.getPlayer(playerId) : undefined;
            if (player && player.allianceId) {
                world.alliances.setTaxRate(player.allianceId, command.taxPercent);
                const all = world.alliances.getAlliance(player.allianceId);
                if (all)
                    db_1.dbManager.saveAlliance(all);
                broadcast({
                    type: 'AllianceUpdate',
                    alliance: all || null,
                    treaties: world.alliances.getTreaties()
                });
            }
            break;
        }
        case 'SetMemberRole': {
            const playerId = clients.get(ws);
            const player = playerId ? world.getPlayer(playerId) : undefined;
            if (player && player.allianceId) {
                world.alliances.setMemberRole(player.allianceId, command.targetPlayerId, command.role);
                const all = world.alliances.getAlliance(player.allianceId);
                if (all)
                    db_1.dbManager.saveAlliance(all);
                broadcast({
                    type: 'AllianceUpdate',
                    alliance: all || null,
                    treaties: world.alliances.getTreaties()
                });
            }
            break;
        }
        case 'GetLeaderboards': {
            const entries = world.leaderboards.computeLeaderboards(world, command.metric || 'wealth');
            ws.send(JSON.stringify({
                type: 'LeaderboardSync',
                entries,
                metric: command.metric || 'wealth'
            }));
            break;
        }
    }
}
