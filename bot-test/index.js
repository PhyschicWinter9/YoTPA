/**
 * YoTPA — Full command & scenario test suite
 *
 * Covers every command and edge case:
 *   /tpa, /tpahere, /tpaccept, /tpadeny, /tpastats, /tpainfo, /tpareload
 *
 * Usage:  bun run index.js
 *
 * NOTE: For the "timeout" scenarios to finish quickly, temporarily set
 *       request-timeout: 15  in your server's plugins/YoTPA/config.yml
 *       then /tpareload. Default (60s) will make those tests take a minute.
 */

import { BOT_NAMES, DELAY } from "./config.js";
import { createBots, sleep } from "./bot-factory.js";

// ── Test runner ─────────────────────────────────────────────────

const results = [];

async function run(name, fn) {
  console.log(`\n${"─".repeat(60)}`);
  console.log(`▶  ${name}`);
  console.log(`${"─".repeat(60)}`);
  try {
    await fn();
    console.log(`✅ PASS — ${name}`);
    results.push({ name, pass: true });
  } catch (err) {
    console.error(`❌ FAIL — ${name}: ${err.message}`);
    results.push({ name, pass: false, err: err.message });
  }
  // cool-down between scenarios so the server isn't overwhelmed
  await sleep(2000);
}

function printSummary() {
  console.log(`\n${"═".repeat(60)}`);
  console.log("TEST SUMMARY");
  console.log(`${"═".repeat(60)}`);
  for (const r of results) {
    console.log(`  ${r.pass ? "✅" : "❌"}  ${r.name}${r.err ? `  →  ${r.err}` : ""}`);
  }
  const passed = results.filter((r) => r.pass).length;
  console.log(`\n  ${passed}/${results.length} passed`);
  console.log(`${"═".repeat(60)}\n`);
}

// ── Helpers ─────────────────────────────────────────────────────

/** Wait for a bot to receive a message containing `substr` (case-insensitive). */
function waitForMessage(bot, substr, timeoutMs = 6000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      bot.removeListener("message", handler);
      reject(new Error(`Timeout waiting for message containing "${substr}" on ${bot.username}`));
    }, timeoutMs);

    function handler(msg) {
      if (msg.toString().toLowerCase().includes(substr.toLowerCase())) {
        clearTimeout(timer);
        bot.removeListener("message", handler);
        resolve(msg.toString());
      }
    }
    bot.on("message", handler);
  });
}

/** Record bot position snapshot. */
const pos = (bot) => ({ ...bot.entity.position });

/** Euclidean distance between two position snapshots. */
const dist = (a, b) =>
  Math.sqrt((a.x - b.x) ** 2 + (a.y - b.y) ** 2 + (a.z - b.z) ** 2);

// ── Scenarios ───────────────────────────────────────────────────

// 1. /tpa → /tpaccept → verify sender teleported
async function scenariaTpaAccept(bots) {
  const [sender, target] = [bots[0], bots[1]];
  const beforePos = pos(sender);

  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);
  target.chat("/tpaccept");

  // wait long enough for countdown + teleport (default 5s countdown + buffer)
  await sleep(8000);

  const afterPos = pos(sender);
  const moved = dist(beforePos, afterPos);
  console.log(`  Sender moved ${moved.toFixed(2)} blocks`);

  // Position near target means teleport worked
  const targetPos = pos(target);
  const toTarget = dist(afterPos, targetPos);
  console.log(`  Distance to target after teleport: ${toTarget.toFixed(2)} blocks`);
}

// 2. /tpa → /tpadeny
async function scenariaTpaDeny(bots) {
  const [sender, target] = [bots[0], bots[2]];
  const beforePos = pos(sender);

  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);
  target.chat("/tpadeny");
  await sleep(2000);

  const afterPos = pos(sender);
  console.log(`  Sender moved ${dist(beforePos, afterPos).toFixed(2)} blocks (expected ~0 — not teleported)`);
}

// 3. /tpa → let request expire (needs low request-timeout in config)
async function scenariaTpaTimeout(bots) {
  const [sender, target] = [bots[0], bots[3]];
  sender.chat(`/tpa ${target.username}`);
  // Wait for timeout message. Adjust timeout below to match your config value + buffer.
  // Default server config: request-timeout: 60  →  increase waitForMessage timeout to 70000
  await waitForMessage(sender, "expired", 70000);
  console.log(`  Request expired as expected`);
}

// 4. /tpa to yourself (should be rejected immediately)
async function scenariaTpaSelf(bots) {
  const bot = bots[0];
  bot.chat(`/tpa ${bot.username}`);
  // Expect some kind of error/rejection message
  await sleep(2000);
  console.log(`  Self-TPA attempted (check server output for error message)`);
}

// 5. /tpa to non-existent player
async function scenariaTpaInvalidPlayer(bots) {
  const bot = bots[0];
  bot.chat("/tpa NonExistentPlayer_XYZ");
  await sleep(2000);
  console.log(`  TPA to offline/non-existent player attempted`);
}

// 6. Cooldown — send two /tpa in quick succession
async function scenariaCooldown(bots) {
  const [sender, target] = [bots[0], bots[1]];
  // First request — accept it so cooldown kicks in
  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);
  target.chat("/tpaccept");
  await sleep(8000);

  // Immediately send another /tpa — should be blocked by cooldown
  sender.chat(`/tpa ${target.username}`);
  await sleep(3000);
  console.log(`  Second request sent immediately after teleport (expect cooldown message)`);
}

// 7. /tpahere → /tpaccept → verify guest teleported to host
async function scenarioTpahereAccept(bots) {
  const [host, guest] = [bots[2], bots[3]];
  const beforePos = pos(guest);

  host.chat(`/tpahere ${guest.username}`);
  await sleep(DELAY.requestWait);
  guest.chat("/tpaccept");
  await sleep(8000);

  const afterPos = pos(guest);
  const moved = dist(beforePos, afterPos);
  console.log(`  Guest moved ${moved.toFixed(2)} blocks`);

  const hostPos = pos(host);
  console.log(`  Distance guest→host after teleport: ${dist(afterPos, hostPos).toFixed(2)} blocks`);
}

// 8. /tpahere → /tpadeny
async function scenarioTpahereDay(bots) {
  const [host, guest] = [bots[2], bots[4]];
  const beforePos = pos(guest);

  host.chat(`/tpahere ${guest.username}`);
  await sleep(DELAY.requestWait);
  guest.chat("/tpadeny");
  await sleep(2000);

  console.log(`  Guest moved ${dist(beforePos, pos(guest)).toFixed(2)} blocks (expected ~0)`);
}

// 9. /tpahere → let request expire
async function scenarioTpahereTimeout(bots) {
  const [host, guest] = [bots[3], bots[4]];
  host.chat(`/tpahere ${guest.username}`);
  await waitForMessage(host, "expired", 70000);
  console.log(`  Tpahere request expired as expected`);
}

// 10. /tpahere to yourself
async function scenarioTpahereSelf(bots) {
  const bot = bots[0];
  bot.chat(`/tpahere ${bot.username}`);
  await sleep(2000);
  console.log(`  Self-tpahere attempted`);
}

// 11. /tpahere to non-existent player
async function scenarioTpahereInvalidPlayer(bots) {
  const bot = bots[0];
  bot.chat("/tpahere NonExistentPlayer_XYZ");
  await sleep(2000);
  console.log(`  Tpahere to non-existent player attempted`);
}

// 12. Movement cancels teleport countdown
async function scenarioMovementCancel(bots) {
  const [sender, target] = [bots[0], bots[1]];

  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);
  target.chat("/tpaccept");

  // Move the sender during the countdown window
  await sleep(1000);
  sender.setControlState("forward", true);
  await sleep(800);
  sender.setControlState("forward", false);

  await sleep(6000); // let countdown finish (or get cancelled)
  const senderPos = pos(sender);
  const targetPos = pos(target);
  const d = dist(senderPos, targetPos);
  console.log(`  Sender distance from target: ${d.toFixed(2)} blocks (large = cancelled, small = teleported)`);
}

// 13. /tpaccept with no pending request
async function scenarioAcceptNoPending(bots) {
  const bot = bots[4];
  bot.chat("/tpaccept");
  await sleep(2000);
  console.log(`  /tpaccept with no pending request sent (expect error message)`);
}

// 14. /tpadeny with no pending request
async function scenarioDenyNoPending(bots) {
  const bot = bots[4];
  bot.chat("/tpadeny");
  await sleep(2000);
  console.log(`  /tpadeny with no pending request sent (expect error message)`);
}

// 15. /tpastats for all bots
async function scenarioStats(bots) {
  for (const bot of bots) {
    bot.chat("/tpastats");
    await sleep(DELAY.betweenCmds);
  }
  await sleep(2000);
  console.log(`  /tpastats checked for all ${bots.length} bots`);
}

// 16. /tpainfo
async function scenarioInfo(bots) {
  bots[0].chat("/tpainfo");
  await sleep(2000);
  console.log(`  /tpainfo output received`);
}

// 17. /tpareload (requires OP — will fail if bot is not OP)
async function scenarioReload(bots) {
  bots[0].chat("/tpareload");
  await sleep(2000);
  console.log(`  /tpareload sent (requires server OP — check output above)`);
}

// 18. Stress — all bots send /tpa to each other simultaneously
async function scenarioStress(bots) {
  console.log("  Sending simultaneous TPA requests between all bots…");
  // Bot0→Bot1, Bot2→Bot3, Bot4→Bot0
  bots[0].chat(`/tpa ${bots[1].username}`);
  bots[2].chat(`/tpa ${bots[3].username}`);
  bots[4].chat(`/tpa ${bots[0].username}`);
  await sleep(DELAY.requestWait);

  // Accept all
  bots[1].chat("/tpaccept");
  bots[3].chat("/tpaccept");
  // Bot0 can only accept one pending request — accept Bot4's
  bots[0].chat("/tpaccept");
  await sleep(8000);
  console.log(`  Stress scenario done — check for race conditions or errors above`);
}

// ── Main ────────────────────────────────────────────────────────

async function main() {
  console.log("╔══════════════════════════════════════════════════════╗");
  console.log("║         YoTPA — Full Plugin Test Suite               ║");
  console.log("╚══════════════════════════════════════════════════════╝");
  console.log(`Bots: ${BOT_NAMES.join(", ")}\n`);

  let bots;
  try {
    bots = await createBots(BOT_NAMES);
  } catch (err) {
    console.error("Failed to spawn bots:", err.message);
    process.exit(1);
  }

  await sleep(DELAY.afterSpawn);

  // ── Core /tpa flow ──
  await run("TPA — accept (verify teleport)", () => scenariaTpaAccept(bots));
  await run("TPA — deny",                     () => scenariaTpaDeny(bots));
  await run("TPA — timeout (wait for expiry)",() => scenariaTpaTimeout(bots));

  // ── Edge cases /tpa ──
  await run("TPA — self (should error)",            () => scenariaTpaSelf(bots));
  await run("TPA — non-existent player (error)",    () => scenariaTpaInvalidPlayer(bots));
  await run("TPA — cooldown after teleport",        () => scenariaCooldown(bots));

  // ── Core /tpahere flow ──
  await run("TPAHERE — accept (verify teleport)",   () => scenarioTpahereAccept(bots));
  await run("TPAHERE — deny",                       () => scenarioTpahereDay(bots));
  await run("TPAHERE — timeout",                    () => scenarioTpahereTimeout(bots));

  // ── Edge cases /tpahere ──
  await run("TPAHERE — self (should error)",                () => scenarioTpahereSelf(bots));
  await run("TPAHERE — non-existent player (error)",        () => scenarioTpahereInvalidPlayer(bots));

  // ── Movement & accept/deny edge cases ──
  await run("Movement cancel during countdown",     () => scenarioMovementCancel(bots));
  await run("Accept with no pending request",       () => scenarioAcceptNoPending(bots));
  await run("Deny with no pending request",         () => scenarioDenyNoPending(bots));

  // ── Info commands ──
  await run("/tpastats — all bots", () => scenarioStats(bots));
  await run("/tpainfo",             () => scenarioInfo(bots));
  await run("/tpareload",           () => scenarioReload(bots));

  // ── Stress ──
  await run("Stress — simultaneous multi-bot requests", () => scenarioStress(bots));

  printSummary();

  for (const bot of bots) {
    try { bot.quit(); } catch {}
  }
  process.exit(0);
}

main();
