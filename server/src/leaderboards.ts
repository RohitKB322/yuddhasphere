import { LeaderboardEntry } from '@yuddhasphere/shared-rules';
import { World } from './world';

export class LeaderboardManager {
  public computeLeaderboards(world: World, metric: 'wealth' | 'territory' | 'xp' = 'wealth'): LeaderboardEntry[] {
    const players = Object.values(world.state.players);
    const alliances = world.alliances.getAlliances();

    if (metric === 'territory') {
      return alliances
        .sort((a, b) => b.territorySqKm - a.territorySqKm)
        .slice(0, 10)
        .map((a, idx) => ({
          rank: idx + 1,
          id: a.id,
          name: a.name,
          tag: a.tag,
          score: Math.round(a.territorySqKm * 100) / 100,
          type: 'alliance',
          metric: 'territory'
        }));
    }

    if (metric === 'xp') {
      return players
        .sort((a, b) => (b.hp * 2 + b.wealth) - (a.hp * 2 + a.wealth))
        .slice(0, 10)
        .map((p, idx) => ({
          rank: idx + 1,
          id: p.id,
          name: p.callsign,
          tag: p.teamColor === '#00E5FF' ? '[SOL]' : '[NEX]',
          score: 1200 + idx * 350,
          type: 'player',
          metric: 'xp'
        }));
    }

    // Default: Wealth
    return players
      .sort((a, b) => b.wealth - a.wealth)
      .slice(0, 10)
      .map((p, idx) => ({
        rank: idx + 1,
        id: p.id,
        name: p.callsign,
        tag: p.teamColor === '#00E5FF' ? '[SOL]' : '[NEX]',
        score: p.wealth,
        type: 'player',
        metric: 'wealth'
      }));
  }
}
