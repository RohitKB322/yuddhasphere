import assert from 'node:assert';
import test from 'node:test';
import { calculateDistanceMeters, isPointInsideTerritory, satisfiesStructureSeparation, isSpeedValid } from './formulas';
import { Structure } from './types';

test('Haversine distance calculates accurately', () => {
  const p1 = { lat: 19.0760, lng: 72.8777 }; // Mumbai
  const p2 = { lat: 19.0770, lng: 72.8777 }; // ~111 meters North
  const distance = calculateDistanceMeters(p1, p2);
  assert.ok(distance > 110 && distance < 112, `Expected ~111m, got ${distance}`);
});

test('Separation check fails if closer than min distance', () => {
  const structures: Structure[] = [
    {
      id: 's1',
      ownerId: 'p1',
      ownerCallsign: 'Alpha',
      team: 'blue',
      teamColor: '#00E5FF',
      type: 'village',
      name: 'Alpha Village',
      level: 1,
      position: { lat: 19.0760, lng: 72.8777 },
      hp: 100,
      maxHp: 100,
      radiusMeters: 150,
      radiusType: 'influence',
      online: true,
      createdAt: Date.now(),
      slots: { storedUnits: [], queue: [] }
    }
  ];

  // Point 10 meters away
  const closePoint = { lat: 19.07609, lng: 72.8777 };
  assert.strictEqual(satisfiesStructureSeparation(closePoint, structures, 30), false);

  // Point 50 meters away
  const farPoint = { lat: 19.0765, lng: 72.8777 };
  assert.strictEqual(satisfiesStructureSeparation(farPoint, structures, 30), true);
});

test('Speed validation catches teleportation', () => {
  const p1 = { lat: 19.0760, lng: 72.8777 };
  const p2 = { lat: 19.1000, lng: 72.8777 }; // ~2.6 km
  // In 2 seconds = 1300 m/s > 35 m/s threshold
  assert.strictEqual(isSpeedValid(p1, p2, 2, 35.0), false);
  // In 300 seconds = ~8.8 m/s <= 35 m/s threshold
  assert.strictEqual(isSpeedValid(p1, p2, 300, 35.0), true);
});
