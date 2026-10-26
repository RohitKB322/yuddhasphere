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
    const dbConnected = await db_1.dbManager.connect();
    if (dbConnected) {
        await db_1.dbManager.loadWorldState(world);
    }
    else {
        persistence.loadWorld(world);
    }
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
                    lastActive: Date.now(),
                    bannedState: 'none'
                };
                world.setPlayer(player);
            }
            else {
                player.callsign = command.callsign || player.callsign;
                player.isOnline = true;
                player.lastActive = Date.now();
            }
            db_1.dbManager.savePlayer(player);
            clients.set(ws, player.id);
            // Send InitSync with world snapshot and alliance
            const initMsg = {
                type: 'InitSync',
                player,
                structures: world.getStructures(),
                units: world.getUnits(),
                serverTime: Date.now(),
                alliance: player.allianceId ? world.alliances.getAlliance(player.allianceId) : null
            };
            ws.send(JSON.stringify(initMsg));
            console.log(`[Server] Commander registered & synced: ${player.callsign} (${player.id})`);
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
            const newStructure = {
                id: structId,
                ownerId: player ? player.id : 'p_commander',
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
                slots: { storedUnits: [], queue: [] }
            };
            world.addStructure(newStructure);
            db_1.dbManager.saveStructure(newStructure);
            world.reports.addReport('economy', `Construction Completed`, `Constructed ${newStructure.name} at coordinates.`, 'info');
            // Broadcast state difference to all connected players
            broadcast({
                type: 'StateDiff',
                serverTime: Date.now(),
                updatedStructures: [newStructure]
            });
            break;
        }
        case 'UpgradeStructure': {
            const s = world.state.structures[command.structureId];
            if (s) {
                s.level += 1;
                db_1.dbManager.saveStructure(s);
                broadcast({
                    type: 'StateDiff',
                    serverTime: Date.now(),
                    updatedStructures: [s]
                });
            }
            break;
        }
        case 'SellStructure': {
            world.removeStructure(command.structureId);
            db_1.dbManager.removeStructure(command.structureId);
            broadcast({
                type: 'StateDiff',
                serverTime: Date.now(),
                removedStructureIds: [command.structureId]
            });
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
