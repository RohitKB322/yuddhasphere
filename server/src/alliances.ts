import { Alliance, AllianceMember, AllianceRole, Treaty } from '@yuddhasphere/shared-rules';

export class AllianceManager {
  private alliances: Map<string, Alliance> = new Map();
  private treaties: Map<string, Treaty> = new Map();

  constructor() {
    // Clean state: No dummy alliances pre-seeded
  }

  public getAlliances(): Alliance[] {
    return Array.from(this.alliances.values());
  }

  public getAlliance(id: string): Alliance | undefined {
    return this.alliances.get(id);
  }

  public createAlliance(
    name: string,
    tag: string,
    leaderId: string,
    leaderCallsign: string,
    motto: string = '',
    colorHex: string = '#00E5FF'
  ): Alliance {
    const cleanTag = tag.startsWith('[') ? tag : `[${tag.toUpperCase()}]`;
    const id = `all_${Date.now().toString(36)}`;
    const leaderMember: AllianceMember = {
      playerId: leaderId,
      callsign: leaderCallsign,
      role: 'Supreme Commander',
      joinedAt: Date.now()
    };

    const alliance: Alliance = {
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

  public joinAlliance(allianceId: string, playerId: string, callsign: string = 'Commander'): boolean {
    const all = this.alliances.get(allianceId);
    if (!all) return false;
    if (!all.memberIds.includes(playerId)) {
      all.memberIds.push(playerId);
      if (!all.members) all.members = [];
      all.members.push({
        playerId,
        callsign,
        role: 'Member',
        joinedAt: Date.now()
      });
    }
    return true;
  }

  public setTaxRate(allianceId: string, taxRate: number): boolean {
    const all = this.alliances.get(allianceId);
    if (!all) return false;
    all.taxRatePercent = Math.max(0, Math.min(30, taxRate));
    return true;
  }

  public setMemberRole(allianceId: string, playerId: string, role: AllianceRole): boolean {
    const all = this.alliances.get(allianceId);
    if (!all || !all.members) return false;
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

  public leaveAlliance(allianceId: string, playerId: string): boolean {
    const all = this.alliances.get(allianceId);
    if (!all) return false;
    all.memberIds = all.memberIds.filter(id => id !== playerId);
    if (all.members) {
      all.members = all.members.filter(m => m.playerId !== playerId);
    }
    if (all.memberIds.length === 0) {
      this.alliances.delete(allianceId);
    } else if (all.leaderId === playerId) {
      all.leaderId = all.memberIds[0];
      if (all.members && all.members.length > 0) {
        all.members[0].role = 'Supreme Commander';
        all.leaderCallsign = all.members[0].callsign;
      }
    }
    return true;
  }

  public getTreaties(): Treaty[] {
    return Array.from(this.treaties.values());
  }

  public getTreaty(allianceA: string, allianceB: string): Treaty | undefined {
    const key = [allianceA, allianceB].sort().join(':');
    return this.treaties.get(key);
  }

  public setTreaty(allianceA: string, allianceB: string, status: 'pact' | 'neutral' | 'war'): Treaty {
    const key = [allianceA, allianceB].sort().join(':');
    const treaty: Treaty = {
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
