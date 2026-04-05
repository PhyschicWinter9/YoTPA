// ── Server config ──────────────────────────────────────────────
export const SERVER = {
  host: "your.server.ip", // replace with your server IP or hostname
  port: 25565,              // replace with your server port if not default
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
  "TestBot_Epsilon",
];

// ── Timing (ms) ────────────────────────────────────────────────
export const DELAY = {
  afterSpawn: 2000,   // wait after login before sending commands
  betweenCmds: 500,   // gap between consecutive commands
  requestWait: 3000,  // time for target bot to see and accept a request
};
