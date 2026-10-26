import { GeoPoint } from '@yuddhasphere/shared-rules';
export declare class SpatialGrid<T extends {
    id: string;
    position: GeoPoint;
}> {
    private cellSize;
    private grid;
    private entityCells;
    constructor(cellSizeDegrees?: number);
    private getCellKey;
    insert(item: T): void;
    update(item: T): void;
    remove(id: string): void;
    getNearby(lat: number, lng: number, radiusMeters: number): T[];
}
