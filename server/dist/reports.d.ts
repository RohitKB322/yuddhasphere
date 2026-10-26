import { CombatReport, GeoPoint } from '@yuddhasphere/shared-rules';
export declare class ReportManager {
    private reports;
    private maxReports;
    constructor();
    private createStarterReports;
    addReport(category: 'combat' | 'economy' | 'radar' | 'diplomacy', title: string, detail: string, level?: 'info' | 'warn' | 'danger', location?: GeoPoint): CombatReport;
    getReports(category?: string, limit?: number): CombatReport[];
}
