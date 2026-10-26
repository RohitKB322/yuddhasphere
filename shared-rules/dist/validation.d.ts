import { GeoPoint, Player, Structure, StructureType } from './types';
export interface ValidationResult {
    valid: boolean;
    reason?: string;
    cost?: number;
}
export declare function validatePlacement(player: Player, type: StructureType, targetPos: GeoPoint, allStructures: Structure[], config: any): ValidationResult;
