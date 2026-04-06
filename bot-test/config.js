// ── Server config ──────────────────────────────────────────────
export const SERVER = {
  host: "203.159.92.198", // replace with your server IP or hostname
  port: 25595,              // replace with your server port if not default
  // Version mineflayer will handshake with.
  // ViaBackward on the server lets older clients in, so use the
  // latest version mineflayer actually supports (check `mineflayer`
  // release notes if your server is newer than 1.21.4).
  version: "1.21.11",
};

// ── Bot accounts (offline / cracked) ───────────────────────────
export const BOT_NAMES = [
  "TestBot_Alpha",
  "TestBot_Beta",
  "TestBot_Gamma",
  "TestBot_Delta",
  "TestBot_Epsilon"
];


// ── YoTPA plugin version check ─────────────────────────────────
export const PLUGIN = {
  currentVersion: '1.5.1',
  githubRepo: 'PhyschicWinter9/YoTPA',
};


// ── Admin bot (must be OP on the server) ───────────────────────
// This bot controls positions and worlds for distance/world tests.
export const ADMIN = {
  username: 'TestBot_Adminz',
  // must match test bots for simplicity, but can be different if needed
  // Worlds available on your server (must match exact world names)
  worlds: {
    overworld: 'world',
    nether:    'world_nether',
    end:       'world_the_end',
  },
  // Dimension IDs used in /execute in <dim> run tp
  // and folder names used for world detection.
  // Overworld folder is 'world', dim ID is 'minecraft:overworld'
  dimensions: {
    overworld: 'minecraft:overworld',
    nether:    'minecraft:the_nether',
    end:       'minecraft:the_end',
  },
  // Test positions — adjust to valid spots on your server
  positions: {
    spawn:    { dim: 'minecraft:overworld', x: 0,    y: 64,  z: 0    },
    farAway:  { dim: 'minecraft:overworld', x: 1000, y: 64,  z: 1000 },
    nether:   { dim: 'minecraft:the_nether', x: 0,  y: 64,  z: 0    },
    end:      { dim: 'minecraft:the_end',   x: 100, y: 64,  z: 0    },
  },
};
// ── Timing (ms) ────────────────────────────────────────────────
export const DELAY = {
  afterSpawn: 2000,   // wait after login before sending commands
  betweenCmds: 500,   // gap between consecutive commands
  requestWait: 3000,  // time for target bot to see and accept a request
};
