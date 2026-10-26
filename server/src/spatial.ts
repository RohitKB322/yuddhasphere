import { GeoPoint } from '@yuddhasphere/shared-rules';

export class SpatialGrid<T extends { id: string; position: GeoPoint }> {
  private cellSize: number;
  private grid: Map<string, Set<T>> = new Map();
  private entityCells: Map<string, string> = new Map();

  constructor(cellSizeDegrees: number = 0.01) { // ~1.1km cell size
    this.cellSize = cellSizeDegrees;
  }

  private getCellKey(lat: number, lng: number): string {
    const latIndex = Math.floor(lat / this.cellSize);
    const lngIndex = Math.floor(lng / this.cellSize);
    return `${latIndex}:${lngIndex}`;
  }

  public insert(item: T): void {
    const key = this.getCellKey(item.position.lat, item.position.lng);
    if (!this.grid.has(key)) {
      this.grid.set(key, new Set());
    }
    this.grid.get(key)!.add(item);
    this.entityCells.set(item.id, key);
  }

  public update(item: T): void {
    const oldKey = this.entityCells.get(item.id);
    const newKey = this.getCellKey(item.position.lat, item.position.lng);

    if (oldKey !== newKey) {
      if (oldKey && this.grid.has(oldKey)) {
        this.grid.get(oldKey)!.delete(item);
      }
      if (!this.grid.has(newKey)) {
        this.grid.set(newKey, new Set());
      }
      this.grid.get(newKey)!.add(item);
      this.entityCells.set(item.id, newKey);
    }
  }

  public remove(id: string): void {
    const key = this.entityCells.get(id);
    if (key && this.grid.has(key)) {
      const cellSet = this.grid.get(key)!;
      for (const item of cellSet) {
        if (item.id === id) {
          cellSet.delete(item);
          break;
        }
      }
    }
    this.entityCells.delete(id);
  }

  public getNearby(lat: number, lng: number, radiusMeters: number): T[] {
    const results: T[] = [];
    const cellRadius = Math.ceil(radiusMeters / 111320 / this.cellSize);
    const centerLatIndex = Math.floor(lat / this.cellSize);
    const centerLngIndex = Math.floor(lng / this.cellSize);

    for (let dLat = -cellRadius; dLat <= cellRadius; dLat++) {
      for (let dLng = -cellRadius; dLng <= cellRadius; dLng++) {
        const key = `${centerLatIndex + dLat}:${centerLngIndex + dLng}`;
        const items = this.grid.get(key);
        if (items) {
          for (const item of items) {
            results.push(item);
          }
        }
      }
    }
    return results;
  }
}
