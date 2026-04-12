/**
 * admin.js — Admin bot helper
 *
 * TestBot_Adminz must be OP on the server.
 * Provides commands to teleport bots to specific positions/worlds
 * so that distance and cross-world TPA scenarios can be tested.
 *
 * Dimension IDs used in /execute in <dim> run tp:
 *   minecraft:overworld   minecraft:the_nether   minecraft:the_end
 *
 * All functions accept the admin bot instance and a target bot name.
 */

import { ADMIN } from "./config.js";
import { sleep } from "./bot-factory.js";

/**
 * Teleport a player to an exact coordinate in a dimension.
 * Uses /execute in <dimension-id> run tp to handle cross-world.
 * @param {import("mineflayer").Bot} adminBot
 * @param {string} playerName
 * @param {{ dim: string, x: number, y: number, z: number }} pos
 */
export async function tpToPos(adminBot, playerName, pos) {
  adminBot.chat(`/execute in ${pos.dim} run tp ${playerName} ${pos.x} ${pos.y} ${pos.z}`);
  // Cross-world teleport takes longer — allow 2.5s for chunk loading
  await sleep(2500);
}

/**
 * Teleport a player to another player using /tp.
 */
export async function tpToPlayer(adminBot, fromName, toName) {
  adminBot.chat(`/tp ${fromName} ${toName}`);
  await sleep(1500);
}

/**
 * Teleport a player to the overworld spawn (0 64 0).
 */
export async function tpToSpawn(adminBot, playerName) {
  return tpToPos(adminBot, playerName, ADMIN.positions.spawn);
}

/**
 * Teleport a player 1000 blocks away in the overworld.
 */
export async function tpFarAway(adminBot, playerName) {
  return tpToPos(adminBot, playerName, ADMIN.positions.farAway);
}

/**
 * Teleport a player into the Nether.
 */
export async function tpToNether(adminBot, playerName) {
  return tpToPos(adminBot, playerName, ADMIN.positions.nether);
}

/**
 * Teleport a player into The End.
 */
export async function tpToEnd(adminBot, playerName) {
  return tpToPos(adminBot, playerName, ADMIN.positions.end);
}

/**
 * Freeze a bot in place (slowness 255, hidden particles).
 */
export async function freeze(adminBot, playerName) {
  adminBot.chat(`/effect give ${playerName} slowness 999 255 true`);
  await sleep(500);
}

/**
 * Remove all effects (unfreeze).
 */
export async function unfreeze(adminBot, playerName) {
  adminBot.chat(`/effect clear ${playerName}`);
  await sleep(500);
}

/**
 * Reset all test bots back to spawn so each scenario starts clean.
 */
export async function resetAllToSpawn(adminBot, bots) {
  for (const bot of bots) {
    await tpToSpawn(adminBot, bot.username);
    await sleep(300);
  }
  await sleep(1000);
}

/**
 * Announce a scenario label in server chat (visible in console logs).
 */
export function announce(adminBot, label) {
  adminBot.chat(`/say [TEST] ${label}`);
}
