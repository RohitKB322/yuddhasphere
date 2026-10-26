import { CombatReport, GeoPoint } from '@yuddhasphere/shared-rules';

export class ReportManager {
  private reports: CombatReport[] = [];
  private maxReports: number = 100;

  constructor() {
    this.createStarterReports();
  }

  private createStarterReports(): void {
    this.addReport('diplomacy', 'Alliance Treaty Signed', 'Solar Vanguard [SOL] and Aegis Coalition [AEG] ratified a mutual defense treaty.', 'info');
    this.addReport('combat', 'Airspace Incursion Intercepted', 'SAM Battery Patriot shot down incoming strike sortie over Sector 7.', 'warn');
    this.addReport('economy', 'Territory Tax Distribution', 'Local settlements disbursed +$1,250 in hourly regional revenue ledgers.', 'info');
    this.addReport('radar', 'Hostile Sortie Vector Tracked', 'Long-range radar detected supersonic strike package 4.2km North-East.', 'danger');
  }

  public addReport(
    category: 'combat' | 'economy' | 'radar' | 'diplomacy',
    title: string,
    detail: string,
    level: 'info' | 'warn' | 'danger' = 'info',
    location?: GeoPoint
  ): CombatReport {
    const report: CombatReport = {
      id: `rep_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`,
      timestamp: Date.now(),
      category,
      title,
      detail,
      level,
      location
    };
    this.reports.unshift(report);
    if (this.reports.length > this.maxReports) {
      this.reports.pop();
    }
    return report;
  }

  public getReports(category?: string, limit: number = 30): CombatReport[] {
    if (!category || category === 'all') {
      return this.reports.slice(0, limit);
    }
    return this.reports.filter(r => r.category === category).slice(0, limit);
  }
}
