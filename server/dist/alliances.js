"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.AllianceManager = void 0;
class AllianceManager {
    alliances = new Map();
    treaties = new Map();
    constructor() {
        // Clean state: No dummy alliances pre-seeded
    }
    getAlliances() {
        return Array.from(this.alliances.values());
    }
    getAlliance(id) {
        return this.alliances.get(id);
    }
    createAlliance(name, tag, leaderId, leaderCallsign, motto = '', colorHex = '#00E5FF') {
        const cleanTag = tag.startsWith('[') ? tag : `[${tag.toUpperCase()}]`;
        const id = `all_${Date.now().toString(36)}`;
        const leaderMember = {
            playerId: leaderId,
            callsign: leaderCallsign,
            role: 'Supreme Commander',
            joinedAt: Date.now()
        };
        const alliance = {
            id,
            name,
            tag: cleanTag,
            leaderId,
            leaderCallsign,
            memberIds: [leaderId],
            members: [leaderMember],
            colorHex,
            motto: motto || 'Ready for tactical operations.',
            totalWealth: 10000,
            territorySqKm: 1.0,
            taxRatePercent: 10,
            treasuryCredits: 5000,
            createdAt: Date.now()
        };
        this.alliances.set(id, alliance);
        return alliance;
    }
    joinAlliance(allianceId, playerId, callsign = 'Commander') {
        const all = this.alliances.get(allianceId);
        if (!all)
            return false;
        if (!all.memberIds.includes(playerId)) {
            all.memberIds.push(playerId);
            if (!all.members)
                all.members = [];
            all.members.push({
                playerId,
                callsign,
                role: 'Member',
                joinedAt: Date.now()
            });
        }
        return true;
    }
    setTaxRate(allianceId, taxRate) {
        const all = this.alliances.get(allianceId);
        if (!all)
            return false;
        all.taxRatePercent = Math.max(0, Math.min(30, taxRate));
        return true;
    }
    setMemberRole(allianceId, playerId, role) {
        const all = this.alliances.get(allianceId);
        if (!all || !all.members)
            return false;
        const m = all.members.find(mem => mem.playerId === playerId);
        if (m) {
            m.role = role;
            if (role === 'Supreme Commander') {
                all.leaderId = playerId;
                all.leaderCallsign = m.callsign;
            }
            return true;
        }
        return false;
    }
    leaveAlliance(allianceId, playerId) {
        const all = this.alliances.get(allianceId);
        if (!all)
            return false;
        all.memberIds = all.memberIds.filter(id => id !== playerId);
        if (all.members) {
            all.members = all.members.filter(m => m.playerId !== playerId);
        }
        if (all.memberIds.length === 0) {
            this.alliances.delete(allianceId);
        }
        else if (all.leaderId === playerId) {
            all.leaderId = all.memberIds[0];
            if (all.members && all.members.length > 0) {
                all.members[0].role = 'Supreme Commander';
                all.leaderCallsign = all.members[0].callsign;
            }
        }
        return true;
    }
    getTreaties() {
        return Array.from(this.treaties.values());
    }
    getTreaty(allianceA, allianceB) {
        const key = [allianceA, allianceB].sort().join(':');
        return this.treaties.get(key);
    }
    setTreaty(allianceA, allianceB, status) {
        const key = [allianceA, allianceB].sort().join(':');
        const treaty = {
            id: `try_${key}`,
            allianceIdA: allianceA,
            allianceIdB: allianceB,
            status,
            updatedAt: Date.now()
        };
        this.treaties.set(key, treaty);
        return treaty;
    }
}
exports.AllianceManager = AllianceManager;
