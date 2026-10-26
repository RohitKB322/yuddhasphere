"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.PersistenceManager = void 0;
const fs = __importStar(require("fs"));
const path = __importStar(require("path"));
class PersistenceManager {
    dataDir;
    worldFilePath;
    backupsDir;
    loadSucceeded = false;
    constructor(dataDir = path.join(__dirname, '..', 'data')) {
        this.dataDir = dataDir;
        this.worldFilePath = path.join(dataDir, 'world.json');
        this.backupsDir = path.join(dataDir, 'backups');
        if (!fs.existsSync(this.dataDir)) {
            fs.mkdirSync(this.dataDir, { recursive: true });
        }
        if (!fs.existsSync(this.backupsDir)) {
            fs.mkdirSync(this.backupsDir, { recursive: true });
        }
    }
    loadWorld(world) {
        if (!fs.existsSync(this.worldFilePath)) {
            console.log('[Persistence] No world.json found. Initializing fresh world.');
            this.loadSucceeded = true;
            return true;
        }
        try {
            const raw = fs.readFileSync(this.worldFilePath, 'utf-8');
            const parsed = JSON.parse(raw);
            world.state = parsed;
            this.loadSucceeded = true;
            console.log(`[Persistence] Successfully loaded world (version ${world.state.version})`);
            return true;
        }
        catch (err) {
            console.error('[Persistence] FATAL: Error reading world.json. Refusing to overwrite.', err);
            this.loadSucceeded = false;
            return false;
        }
    }
    saveWorld(world) {
        if (!this.loadSucceeded) {
            console.error('[Persistence] Save aborted: startup load previously failed. Protecting data.');
            return false;
        }
        const tempPath = path.join(this.dataDir, `world.tmp.${Date.now()}`);
        try {
            world.state.lastTickTime = Date.now();
            const serialized = JSON.stringify(world.state, null, 2);
            // 1. Atomic write to temp file
            fs.writeFileSync(tempPath, serialized, 'utf-8');
            // 2. Rename temp file to target
            fs.renameSync(tempPath, this.worldFilePath);
            return true;
        }
        catch (err) {
            console.error('[Persistence] Error saving world state:', err);
            if (fs.existsSync(tempPath)) {
                try {
                    fs.unlinkSync(tempPath);
                }
                catch { }
            }
            return false;
        }
    }
    createHourlyBackup(world) {
        if (!this.loadSucceeded)
            return;
        try {
            const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
            const backupPath = path.join(this.backupsDir, `world-${timestamp}.json`);
            fs.copyFileSync(this.worldFilePath, backupPath);
            console.log(`[Persistence] Hourly backup created: ${backupPath}`);
        }
        catch (err) {
            console.error('[Persistence] Backup error:', err);
        }
    }
}
exports.PersistenceManager = PersistenceManager;
