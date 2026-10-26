import { Alliance, AllianceRole, Treaty } from '@yuddhasphere/shared-rules';
export declare class AllianceManager {
    private alliances;
    private treaties;
    constructor();
    getAlliances(): Alliance[];
    getAlliance(id: string): Alliance | undefined;
    createAlliance(name: string, tag: string, leaderId: string, leaderCallsign: string, motto?: string, colorHex?: string): Alliance;
    joinAlliance(allianceId: string, playerId: string, callsign?: string): boolean;
    setTaxRate(allianceId: string, taxRate: number): boolean;
    setMemberRole(allianceId: string, playerId: string, role: AllianceRole): boolean;
    leaveAlliance(allianceId: string, playerId: string): boolean;
    getTreaties(): Treaty[];
    getTreaty(allianceA: string, allianceB: string): Treaty | undefined;
    setTreaty(allianceA: string, allianceB: string, status: 'pact' | 'neutral' | 'war'): Treaty;
}
