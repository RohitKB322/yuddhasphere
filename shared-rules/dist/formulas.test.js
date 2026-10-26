"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const node_assert_1 = __importDefault(require("node:assert"));
const node_test_1 = __importDefault(require("node:test"));
const formulas_1 = require("./formulas");
(0, node_test_1.default)('Haversine distance calculates accurately', () => {
    const p1 = { lat: 19.0760, lng: 72.8777 }; // Mumbai
    const p2 = { lat: 19.0770, lng: 72.8777 }; // ~111 meters North
    const distance = (0, formulas_1.calculateDistanceMeters)(p1, p2);
    node_assert_1.default.ok(distance > 110 && distance < 112, `Expected ~111m, got ${distance}`);
});
(0, node_test_1.default)('Separation check fails if closer than min distance', () => {
    const structures = [
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
    node_assert_1.default.strictEqual((0, formulas_1.satisfiesStructureSeparation)(closePoint, structures, 30), false);
    // Point 50 meters away
    const farPoint = { lat: 19.0765, lng: 72.8777 };
    node_assert_1.default.strictEqual((0, formulas_1.satisfiesStructureSeparation)(farPoint, structures, 30), true);
});
(0, node_test_1.default)('Speed validation catches teleportation', () => {
    const p1 = { lat: 19.0760, lng: 72.8777 };
    const p2 = { lat: 19.1000, lng: 72.8777 }; // ~2.6 km
    // In 2 seconds = 1300 m/s > 35 m/s threshold
    node_assert_1.default.strictEqual((0, formulas_1.isSpeedValid)(p1, p2, 2, 35.0), false);
    // In 300 seconds = ~8.8 m/s <= 35 m/s threshold
    node_assert_1.default.strictEqual((0, formulas_1.isSpeedValid)(p1, p2, 300, 35.0), true);
});
