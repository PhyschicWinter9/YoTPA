import mineflayer from "mineflayer";
import { SERVER } from "./config.js";

/**
 * Creates a single offline (cracked) bot and waits until it spawns.
 * Retries automatically when kicked for connection throttle.
 * @param {string} username
 * @param {number} maxRetries
 * @returns {Promise<import("mineflayer").Bot>}
 */
export function createBot(username, maxRetries = 5) {
  return new Promise((resolve, reject) => {
    let attempt = 0;

    function connect() {
      attempt++;
      const bot = mineflayer.createBot({
        host: SERVER.host,
        port: SERVER.port,
        version: SERVER.version,
        username,
        auth: "offline", // cracked / offline-mode server
      });

      bot.once("spawn", () => {
        console.log(`[+] ${username} spawned`);
        resolve(bot);
      });

      bot.on("error", (err) => {
        // "Unknown dimension" fires when a bot is cross-world teleported and
        // mineflayer doesn't have that dimension in its registry. Safe to ignore —
        // the bot will respawn in the new world on its own.
        if (err.message?.toLowerCase().includes("unknown dimension")) {
          console.warn(`[~] ${username} unknown dimension (cross-world tp) — ignoring`);
          return;
        }
        // Ignore ECONNRESET on throttle retries — the kick handler covers it
        if (attempt >= maxRetries) {
          console.error(`[!] ${username} error: ${err.message}`);
          reject(err);
        }
      });

      bot.on("kicked", (reason) => {
        const msg = typeof reason === "string" ? reason : JSON.stringify(reason);
        if (msg.toLowerCase().includes("throttled") && attempt < maxRetries) {
          const wait = 5000 * attempt; // back off: 5s, 10s, 15s …
          console.warn(`[~] ${username} throttled (attempt ${attempt}/${maxRetries}), retrying in ${wait / 1000}s…`);
          setTimeout(connect, wait);
        } else {
          console.warn(`[!] ${username} kicked: ${msg}`);
          reject(new Error(`Kicked: ${msg}`));
        }
      });

      bot.on("end", () => {
        console.log(`[-] ${username} disconnected`);
      });

      // Print chat so you can see plugin responses in the terminal
      bot.on("message", (msg) => {
        const text = msg.toString();
        if (text.trim()) console.log(`[${username}] ${text}`);
      });
    }

    connect();
  });
}

/**
 * Spawns multiple bots with a stagger delay so the server isn't
 * hit with simultaneous logins.
 * Paper's connection throttle defaults to 4000ms, so stagger >= 5000ms.
 * @param {string[]} names
 * @param {number} staggerMs  delay between each bot login (default 5s)
 * @returns {Promise<import("mineflayer").Bot[]>}
 */
export async function createBots(names, staggerMs = 5000) {
  const bots = [];
  for (const name of names) {
    const bot = await createBot(name);
    bots.push(bot);
    if (staggerMs > 0 && name !== names.at(-1)) {
      console.log(`[…] waiting ${staggerMs / 1000}s before next login…`);
      await sleep(staggerMs);
    }
  }
  return bots;
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
