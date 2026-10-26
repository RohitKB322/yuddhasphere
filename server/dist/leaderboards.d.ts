import { LeaderboardEntry } from '@yuddhasphere/shared-rules';
import { World } from './world';
export declare class LeaderboardManager {
    computeLeaderboards(world: World, metric?: 'wealth' | 'territory' | 'xp'): LeaderboardEntry[];
}
