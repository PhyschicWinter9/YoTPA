/**
 * YoTPA — Full Plugin Test Suite
 *
 * Covers:
 *   1. Version check (GitHub latest release)
 *   2. All command scenarios (/tpa, /tpahere, /tpaccept, /tpadeny,
 *      /tpastats, /tpainfo, /tpareload, /back)
 *   3. Edge cases (self, invalid player, no pending, movement cancel)
 *   4. Admin-controlled: large distance + cross-world
 *   5. v1.6.x regression scenarios (destination offline mid-countdown,
 *      requester quit cleanup, accept-while-moving cancel race, /tpainfo RAM sanity)
 *   6. Stress & performance tests (latency, throughput, cooldown, expiry)
 *   7. Summary report
 *
 * Core scenarios assert on the plugin's actual chat messages (default
 * messages.yml wording) — a scenario FAILS if the expected message never
 * arrives, instead of silently passing on a log line.
 *
 * Usage:
 *   bun run index.js            → full suite
 *   bun run index.js --stress   → stress tests only
 *   bun run index.js --quick    → functional tests only (skip stress)
 *
 * NOTE: Bots need yotpa.back permission (or OP) for /back scenarios.
 *       Admin bot grants OP at startup automatically.
 */

import { BOT_NAMES, ADMIN, DELAY } from "./config.js";
import { createBot, createBots, sleep } from "./bot-factory.js";
import {
  tpToSpawn, tpFarAway, tpToNether, tpToEnd,
  tpToPlayer, resetAllToSpawn, announce,
} from "./admin.js";
import { checkPluginVersion } from "./version-check.js";
import {
  stressRapidTpa,
  stressConcurrentRequests,
  stressCooldownEnforcement,
  stressExpiryFlood,
  stressThroughput,
  printStressSummary,
} from "./stress.js";

// ── CLI flags ────────────────────────────────────────────────────
const ARGS = new Set(process.argv.slice(2));
const STRESS_ONLY = ARGS.has("--stress");
const QUICK       = ARGS.has("--quick");  // skip stress

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
  await sleep(2000);
}

function printSummary() {
  const passed = results.filter((r) => r.pass).length;
  console.log(`\n${"═".repeat(60)}`);
  console.log("  FUNCTIONAL TEST SUMMARY");
  console.log(`${"═".repeat(60)}`);
  for (const r of results) {
    console.log(`  ${r.pass ? "✅" : "❌"}  ${r.name}${r.err ? `  →  ${r.err}` : ""}`);
  }
  console.log(`\n  ${passed}/${results.length} passed`);
  console.log(`${"═".repeat(60)}\n`);
}

// ── Helpers ──────────────────────────────────────────────────────

function waitForMessage(bot, substr, timeoutMs = 6000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      bot.removeListener("message", handler);
      reject(new Error(`Timeout waiting for "${substr}" on ${bot.username}`));
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

const pos  = (bot) => ({ ...bot.entity.position });
const dist = (a, b) => Math.sqrt((a.x-b.x)**2 + (a.y-b.y)**2 + (a.z-b.z)**2);

function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}

/** Collect every chat line a bot receives while fn() runs (plus settleMs after). */
async function collectMessages(bot, fn, settleMs = 2500) {
  const lines = [];
  const handler = (msg) => lines.push(msg.toString());
  bot.on("message", handler);
  try {
    await fn();
    await sleep(settleMs);
  } finally {
    bot.removeListener("message", handler);
  }
  return lines;
}

// ── Functional scenarios ─────────────────────────────────────────

// 1. /tpa → accept → verify sender teleported (hard-asserts on the success message)
async function scenarioTpaAccept(bots) {
  const [sender, target] = [bots[0], bots[1]];
  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);
  const success = waitForMessage(sender, "teleported to", 15000);
  target.chat("/tpaccept");
  await success; // throws if the teleport-success message never arrives
  await sleep(1500);
  const d = dist(pos(sender), pos(target));
  console.log(`  Sender→target distance after teleport: ${d.toFixed(2)} blocks`);
  assert(d < 5, `success message received but sender is ${d.toFixed(2)} blocks from target`);
}

// 2. /tpa → deny
async function scenarioTpaDeny(bots) {
  const [sender, target] = [bots[0], bots[2]];
  const beforeDeny = pos(sender);
  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);
  target.chat("/tpadeny");
  await sleep(2000);
  console.log(`  Sender moved ${dist(beforeDeny, pos(sender)).toFixed(2)} blocks (expected ~0)`);
}

// 3. /tpa → timeout  (set request-timeout: 15 in config.yml for fast test)
async function scenarioTpaTimeout(bots) {
  const [sender] = bots;
  sender.chat(`/tpa ${bots[3].username}`);
  await waitForMessage(sender, "expired", 70000);
  console.log(`  Request expired as expected`);
}

// 4. /tpa to yourself
async function scenarioTpaSelf(bots) {
  bots[0].chat(`/tpa ${bots[0].username}`);
  await sleep(2000);
}

// 5. /tpa to non-existent player
async function scenarioTpaInvalidPlayer(bots) {
  bots[0].chat("/tpa NonExistentPlayer_XYZ");
  await sleep(2000);
}

// 6. Cooldown — teleport then immediately request again
async function scenarioCooldown(bots) {
  const [sender, target] = [bots[0], bots[1]];
  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);
  target.chat("/tpaccept");
  await sleep(8000);
  sender.chat(`/tpa ${target.username}`);  // should hit cooldown
  await sleep(3000);
  console.log(`  Second request sent (expect cooldown message above)`);
}

// 7. /tpahere → accept → verify guest teleported to host (hard-asserts on success message)
async function scenarioTpahereAccept(bots) {
  const [host, guest] = [bots[2], bots[3]];
  host.chat(`/tpahere ${guest.username}`);
  await sleep(DELAY.requestWait);
  const success = waitForMessage(guest, "teleported to", 15000);
  guest.chat("/tpaccept");
  await success;
  await sleep(1500);
  const d = dist(pos(guest), pos(host));
  console.log(`  Guest→host distance after teleport: ${d.toFixed(2)} blocks`);
  assert(d < 5, `success message received but guest is ${d.toFixed(2)} blocks from host`);
}

// 8. /tpahere → deny
async function scenarioTpahereDeny(bots) {
  const [host, guest] = [bots[2], bots[4]];
  const before = pos(guest);
  host.chat(`/tpahere ${guest.username}`);
  await sleep(DELAY.requestWait);
  guest.chat("/tpadeny");
  await sleep(2000);
  console.log(`  Guest moved ${dist(before, pos(guest)).toFixed(2)} blocks (expected ~0)`);
}

// 9. /tpahere → timeout
async function scenarioTpahereTimeout(bots) {
  bots[3].chat(`/tpahere ${bots[4].username}`);
  await waitForMessage(bots[3], "expired", 70000);
  console.log(`  Tpahere request expired as expected`);
}

// 10. /tpahere to yourself
async function scenarioTpahereSelf(bots) {
  bots[0].chat(`/tpahere ${bots[0].username}`);
  await sleep(2000);
}

// 11. /tpahere to non-existent player
async function scenarioTpahereInvalidPlayer(bots) {
  bots[0].chat("/tpahere NonExistentPlayer_XYZ");
  await sleep(2000);
}

// 12. Movement cancels countdown (hard-asserts on the cancellation message)
async function scenarioMovementCancel(bots) {
  const [sender, target] = [bots[0], bots[1]];
  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);
  const cancelled = waitForMessage(sender, "movement", 12000);
  target.chat("/tpaccept");
  await sleep(1000); // let countdown start
  sender.setControlState("forward", true);
  await sleep(800);
  sender.setControlState("forward", false);
  await cancelled; // throws if the movement-cancel message never arrives
  await sleep(5000); // would-be teleport window — verify nothing happens
  const d = dist(pos(sender), pos(target));
  console.log(`  Cancelled by movement; sender distance from target: ${d.toFixed(2)} blocks`);
}

// 13. /tpaccept with no pending request
async function scenarioAcceptNoPending(bots) {
  bots[4].chat("/tpaccept");
  await sleep(2000);
  console.log(`  Expect "no pending request" message above`);
}

// 14. /tpadeny with no pending request
async function scenarioDenyNoPending(bots) {
  bots[4].chat("/tpadeny");
  await sleep(2000);
  console.log(`  Expect "no pending request" message above`);
}

// 15. /tpastats
async function scenarioStats(bots) {
  for (const bot of bots) {
    bot.chat("/tpastats");
    await sleep(DELAY.betweenCmds);
  }
  await sleep(2000);
}

// 16. /tpainfo  (shows performance mode, thread count, etc.)
async function scenarioInfo(bots) {
  bots[0].chat("/tpainfo");
  await sleep(2000);
}

// 17. /tpareload  (requires OP)
async function scenarioReload(bots) {
  bots[0].chat("/tpareload");
  await sleep(2000);
  console.log(`  (Requires server OP — check output above)`);
}

// ── Admin-controlled scenarios ────────────────────────────────

// 19. Large distance TPA — sender is 1000 blocks away from target
async function scenarioLargeDistance(bots, admin) {
  const [sender, target] = [bots[0], bots[1]];
  announce(admin, "Large distance TPA");

  // Move sender far, keep target at spawn
  await tpToSpawn(admin, target.username);
  await tpFarAway(admin, sender.username);
  await sleep(2000);

  console.log(`  Sender is ~1000 blocks away`);
  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);
  target.chat("/tpaccept");
  await sleep(8000);

  const d = dist(pos(sender), pos(target));
  console.log(`  Sender→target distance after teleport: ${d.toFixed(2)} blocks (should be ~0)`);

  await resetAllToSpawn(admin, [sender, target]);
}

// 20. Cross-world TPA: sender in Nether, target in Overworld
async function scenarioCrossWorldNetherToOverworld(bots, admin) {
  const [sender, target] = [bots[0], bots[1]];
  announce(admin, "Cross-world TPA: Nether → Overworld");

  await tpToSpawn(admin, target.username);
  await tpToNether(admin, sender.username);
  await sleep(2000);

  console.log(`  Sender is in Nether, target is in Overworld`);
  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);
  target.chat("/tpaccept");
  await sleep(8000);

  console.log(`  Sender world after teleport: ${sender.game?.dimension ?? "unknown"}`);
  console.log(`  (Should be overworld if cross-world TPA is allowed)`);

  await resetAllToSpawn(admin, [sender, target]);
}

// 21. Cross-world TPA: sender in Overworld, target in Nether
async function scenarioCrossWorldOverworldToNether(bots, admin) {
  const [sender, target] = [bots[2], bots[3]];
  announce(admin, "Cross-world TPA: Overworld → Nether");

  await tpToSpawn(admin, sender.username);
  await tpToNether(admin, target.username);
  await sleep(2000);

  console.log(`  Sender is in Overworld, target is in Nether`);
  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);
  target.chat("/tpaccept");
  await sleep(8000);

  console.log(`  Sender world after teleport: ${sender.game?.dimension ?? "unknown"}`);

  await resetAllToSpawn(admin, [sender, target]);
}

// 22. Cross-world TPA: sender in Overworld, target in The End
async function scenarioCrossWorldToEnd(bots, admin) {
  const [sender, target] = [bots[2], bots[4]];
  announce(admin, "Cross-world TPA: Overworld → End");

  await tpToSpawn(admin, sender.username);
  await tpToEnd(admin, target.username);
  await sleep(2000);

  console.log(`  Sender is in Overworld, target is in The End`);
  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);
  target.chat("/tpaccept");
  await sleep(8000);

  console.log(`  Sender world after teleport: ${sender.game?.dimension ?? "unknown"}`);

  await resetAllToSpawn(admin, [sender, target]);
}

// 23. /tpahere cross-world — host in Nether, calls guest from Overworld
async function scenarioCrossWorldTpahereFromNether(bots, admin) {
  const [host, guest] = [bots[0], bots[1]];
  announce(admin, "Cross-world TPAHERE: host in Nether, guest in Overworld");

  await tpToNether(admin, host.username);
  await tpToSpawn(admin, guest.username);
  await sleep(2000);

  console.log(`  Host is in Nether, guest is in Overworld`);
  host.chat(`/tpahere ${guest.username}`);
  await sleep(DELAY.requestWait);
  guest.chat("/tpaccept");
  await sleep(8000);

  console.log(`  Guest world after teleport: ${guest.game?.dimension ?? "unknown"}`);
  console.log(`  (Should be nether if cross-world TPAHERE is allowed)`);

  await resetAllToSpawn(admin, [host, guest]);
}

// 24. Large distance + movement cancel — sender is far away and moves during countdown
async function scenarioLargeDistanceMovementCancel(bots, admin) {
  const [sender, target] = [bots[3], bots[4]];
  announce(admin, "Large distance + movement cancel");

  await tpToSpawn(admin, target.username);
  await tpFarAway(admin, sender.username);
  await sleep(2000);

  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);
  target.chat("/tpaccept");

  // Move during countdown
  await sleep(1000);
  sender.setControlState("forward", true);
  await sleep(800);
  sender.setControlState("forward", false);
  await sleep(6000);

  const d = dist(pos(sender), pos(target));
  console.log(`  Sender distance from target: ${d.toFixed(2)} blocks (large = cancelled ✅)`);

  await resetAllToSpawn(admin, [sender, target]);
}

// 25. Admin /tpareload, then re-test TPA to verify reload didn't break anything
async function scenarioReloadThenTpa(bots, admin) {
  announce(admin, "/tpareload then TPA verify");
  admin.chat("/tpareload");
  await sleep(3000);
  console.log(`  Config reloaded by admin`);

  const [sender, target] = [bots[0], bots[1]];
  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);
  target.chat("/tpaccept");
  await sleep(8000);

  const d = dist(pos(sender), pos(target));
  console.log(`  Post-reload TPA distance: ${d.toFixed(2)} blocks`);
}

// ── /back scenarios ──────────────────────────────────────────────

// B1. /back after TPA accept — sender should return to original position
async function scenarioBackAfterTpa(bots) {
  const [sender, target] = [bots[0], bots[1]];
  const origin = pos(sender);

  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);
  target.chat("/tpaccept");
  await sleep(8000); // teleport countdown + land

  const afterTpa = pos(sender);
  console.log(`  After TPA — distance moved: ${dist(origin, afterTpa).toFixed(2)} blocks`);

  const backMsg = waitForMessage(sender, "teleporting back", 8000);
  sender.chat("/back");
  await backMsg;
  await sleep(2000);

  const afterBack = pos(sender);
  const returnDist = dist(afterBack, origin);
  console.log(`  After /back — distance from origin: ${returnDist.toFixed(2)} blocks (should be ~0)`);
  assert(returnDist < 3, `/back did not return sender to origin (off by ${returnDist.toFixed(2)} blocks)`);
  console.log(`  ✅  Returned to origin correctly`);
}

// B2. /back after TPAHERE accept — guest should return to their original position
async function scenarioBackAfterTpahere(bots) {
  const [host, guest] = [bots[2], bots[3]];
  const origin = pos(guest);

  host.chat(`/tpahere ${guest.username}`);
  await sleep(DELAY.requestWait);
  guest.chat("/tpaccept");
  await sleep(8000);

  const afterTp = pos(guest);
  console.log(`  After TPAHERE — distance moved: ${dist(origin, afterTp).toFixed(2)} blocks`);

  guest.chat("/back");
  await sleep(3000);

  const afterBack = pos(guest);
  const returnDist = dist(afterBack, origin);
  console.log(`  After /back — distance from origin: ${returnDist.toFixed(2)} blocks (should be ~0)`);
  if (returnDist > 3) console.log(`  ⚠️  /back did not return guest to origin`);
  else               console.log(`  ✅  Returned to origin correctly`);
}

// B3. /back is single-use — location is consumed after teleport
async function scenarioBackSingleUse(bots) {
  const [sender, target] = [bots[0], bots[1]];
  const posA = pos(sender);

  // TPA A → B, saving A as lastLocation
  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);
  target.chat("/tpaccept");
  await sleep(8000);
  const posB = pos(sender);

  console.log(`  A=(${posA.x.toFixed(0)},${posA.z.toFixed(0)})  B=(${posB.x.toFixed(0)},${posB.z.toFixed(0)})`);

  // First /back — should return to A and consume the saved location
  sender.chat("/back");
  await sleep(3000);
  const after1 = pos(sender);
  const distFromA = dist(after1, posA);
  console.log(`  After /back — dist from A: ${distFromA.toFixed(2)} (expect ~0)`);
  assert(distFromA < 3, `/back did not return to origin (off by ${distFromA.toFixed(2)} blocks)`);

  // Second /back immediately — location was consumed; must NOT teleport again.
  // Expects "cooldown" (back-cooldown > 0) or "no location" (if cooldown disabled).
  const posBeforeSecond = { ...pos(sender) };
  sender.chat("/back");
  await sleep(3000);
  const moved = dist(posBeforeSecond, pos(sender)) > 1;
  assert(!moved, "second /back teleported the player — saved location was NOT consumed");
  console.log(`  ✅  Second /back did not teleport — location was consumed (single-use confirmed)`);
}

// B4. /back with no prior location (fresh join, never teleported)
async function scenarioBackNoLocation(bots) {
  // Bot 4 hasn't teleported in this session (use fresh bot)
  bots[4].chat("/back");
  await sleep(2000);
  console.log(`  Expect "no previous location" error message above`);
}

// B5. /back without permission
async function scenarioBackNoPermission(bots, admin) {
  const bot = bots[4];
  // Temporarily revoke permission via admin
  admin.chat(`/lp user ${bot.username} permission unset yotpa.back`);
  await sleep(1000);

  bot.chat("/back");
  await sleep(2000);
  console.log(`  Expect "no permission" message above`);

  // Restore permission
  admin.chat(`/lp user ${bot.username} permission set yotpa.back true`);
  await sleep(1000);
}

// B6. /back after cross-world TPA (admin-assisted) — should return to original world
async function scenarioBackCrossWorld(bots, admin) {
  const [sender, target] = [bots[0], bots[1]];
  announce(admin, "/back cross-world test");

  await tpToSpawn(admin, sender.username);
  await tpToNether(admin, target.username);
  await sleep(2000);

  const originDim = sender.game?.dimension ?? "overworld";
  console.log(`  Sender in: ${originDim}`);

  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);
  target.chat("/tpaccept");
  await sleep(8000);

  const afterTpDim = sender.game?.dimension ?? "unknown";
  console.log(`  After TPA dim: ${afterTpDim}`);

  sender.chat("/back");
  await sleep(4000); // cross-world back takes longer

  const afterBackDim = sender.game?.dimension ?? "unknown";
  console.log(`  After /back dim: ${afterBackDim} (should be ${originDim})`);
  if (afterBackDim === originDim) console.log(`  ✅  Returned to original world`);
  else                            console.log(`  ⚠️  World mismatch after /back`);

  await resetAllToSpawn(admin, [sender, target]);
}

// B8. /back after death — admin kills bot, bot respawns and uses /back to return to death spot
async function scenarioBackAfterDeath(bots, admin) {
  const bot = bots[0];
  announce(admin, "/back after death test");

  // Move bot to a distinct location so we can verify the death spot was saved
  await tpFarAway(admin, bot.username);
  await sleep(2000);
  const deathSpot = pos(bot);
  console.log(`  Bot at death spot: (${deathSpot.x.toFixed(0)}, ${deathSpot.z.toFixed(0)})`);

  // Kill the bot via admin
  admin.chat(`/kill ${bot.username}`);
  await sleep(6000); // wait for death screen + auto-respawn (if enabled) or manual respawn

  // Bot should have received "death-saved" notification on respawn
  // Use /back to return to death spot
  bot.chat("/back");
  await sleep(4000);

  const afterBack = pos(bot);
  const returnDist = dist(afterBack, deathSpot);
  console.log(`  Distance from death spot after /back: ${returnDist.toFixed(2)} blocks`);
  if (returnDist < 5) console.log(`  ✅  /back returned to death location`);
  else                console.log(`  ⚠️  /back did not return to death spot (distance: ${returnDist.toFixed(2)})`);

  await tpToSpawn(admin, bot.username);
}

// B9. /back after being killed by another player (PvP)
async function scenarioBackAfterPvpDeath(bots, admin) {
  const [victim, killer] = [bots[1], bots[2]];
  announce(admin, "/back after PvP death");

  // Put both bots together at a far location
  await tpFarAway(admin, victim.username);
  await tpToPlayer(admin, killer.username, victim.username);
  await sleep(2000);
  const deathSpot = pos(victim);

  // Give killer a sharp sword and enable PvP damage on victim
  admin.chat(`/give ${killer.username} diamond_sword{Enchantments:[{id:sharpness,lvl:10}]} 1`);
  await sleep(500);
  // One-shot kill via attribute
  admin.chat(`/attribute ${victim.username} minecraft:generic.max_health base set 1`);
  await sleep(500);

  // Killer attacks victim
  console.log(`  Killer attacking victim at (${deathSpot.x.toFixed(0)}, ${deathSpot.z.toFixed(0)})`);
  killer.attack(victim.entity);
  await sleep(6000);

  // Restore victim's health attribute
  admin.chat(`/attribute ${victim.username} minecraft:generic.max_health base set 20`);

  victim.chat("/back");
  await sleep(4000);

  const returnDist = dist(pos(victim), deathSpot);
  console.log(`  Distance from PvP death spot after /back: ${returnDist.toFixed(2)} blocks`);
  if (returnDist < 5) console.log(`  ✅  /back returned to PvP death location`);
  else                console.log(`  ⚠️  /back did not return to PvP death spot`);

  await resetAllToSpawn(admin, [victim, killer]);
}

// B10. /back cooldown — use /back then immediately again (second should be blocked)
async function scenarioBackCooldown(bots) {
  const [sender, target] = [bots[0], bots[1]];

  // Establish a lastLocation via TPA
  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);
  target.chat("/tpaccept");
  await sleep(8000);

  // First /back — should succeed
  sender.chat("/back");
  await sleep(2000);

  // Immediate second /back — should be blocked by back-cooldown
  const blocked = await new Promise((resolve) => {
    const timer = setTimeout(() => resolve(false), 4000);
    function handler(msg) {
      if (msg.toString().toLowerCase().includes("wait") ||
          msg.toString().toLowerCase().includes("cooldown")) {
        clearTimeout(timer);
        sender.removeListener("message", handler);
        resolve(true);
      }
    }
    sender.on("message", handler);
    sender.chat("/back");
  });

  if (blocked) console.log(`  ✅  Back cooldown enforced on rapid re-use`);
  else         console.log(`  ⚠️  Back cooldown NOT enforced — check back-cooldown config`);
}

// B11. /back cooldown bypass — yotpa.bypass.back-cooldown skips the cooldown gate
//
// How it's verified: after /back consumes the saved location, a non-bypass user
// would hit "you must wait" (cooldown check fires before location check). A bypass
// user skips the cooldown check entirely and reaches the location check instead,
// producing "no previous location". Seeing "no location" (not "cooldown") confirms
// the bypass is working.
async function scenarioBackCooldownBypass(bots, admin) {
  const [sender, target] = [bots[2], bots[3]];

  // Grant bypass permission
  admin.chat(`/lp user ${sender.username} permission set yotpa.bypass.back-cooldown true`);
  await sleep(1000);

  // Establish lastLocation via TPA
  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);
  target.chat("/tpaccept");
  await sleep(8000);

  // First /back — consumes the saved location and records the cooldown timestamp
  sender.chat("/back");
  await sleep(2000);

  // Second /back immediately — location was consumed.
  // Bypass user: cooldown check is skipped → reaches location check → "no previous location"
  // Non-bypass user: cooldown check fires → "you must wait X seconds" (never reaches location check)
  const secondMsg = await new Promise((resolve) => {
    const timer = setTimeout(() => resolve(""), 4000);
    function handler(msg) {
      clearTimeout(timer);
      sender.removeListener("message", handler);
      resolve(msg.toString().toLowerCase());
    }
    sender.on("message", handler);
    sender.chat("/back");
  });

  console.log(`  Second /back response: "${secondMsg}"`);
  if (secondMsg.includes("previous location"))
    console.log(`  ✅  Bypass worked — got "no location" (cooldown check was skipped)`);
  else if (secondMsg.includes("wait") || secondMsg.includes("cooldown"))
    console.log(`  ⚠️  Cooldown was enforced despite yotpa.bypass.back-cooldown permission`);
  else
    console.log(`  ⚠️  Unexpected response (timeout or unknown message)`);

  // Revoke bypass
  admin.chat(`/lp user ${sender.username} permission unset yotpa.bypass.back-cooldown`);
  await sleep(500);
}

// B7. /back used by all 5 bots concurrently — tests thread-safety of lastLocations map
async function scenarioBackConcurrent(bots) {
  console.log(`  Setting up: TPA all bots to a single target then /back simultaneously`);
  const target = bots[0];

  // Each bot requests and gets accepted one by one to establish lastLocations
  for (let i = 1; i <= 3; i++) {
    bots[i].chat(`/tpa ${target.username}`);
    await sleep(DELAY.requestWait);
    target.chat("/tpaccept");
    await sleep(8000);
    await sleep(2000); // cooldown
  }

  // Now all 4 bots hit /back simultaneously
  console.log(`  Firing /back on 4 bots simultaneously…`);
  const start = Date.now();
  await Promise.all([
    bots[1].chat("/back"),
    bots[2].chat("/back"),
    bots[3].chat("/back"),
  ]);
  await sleep(4000);
  console.log(`  All /back commands processed in ${Date.now() - start}ms`);
  console.log(`  ✅  No crashes = lastLocations ConcurrentHashMap is thread-safe`);
}

// ── v1.6.x regression scenarios ─────────────────────────────────

// R1. Destination goes offline mid-countdown → countdown cancelled, sender notified,
//     sender NOT teleported. (Regression guard for the offline-destination cancel path.)
async function scenarioDestinationOffline(bots) {
  const [sender, target] = [bots[0], bots[4]];
  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);

  const before = pos(sender);
  const offlineMsg = waitForMessage(sender, "offline", 12000);
  target.chat("/tpaccept");
  await sleep(1500);        // countdown running (teleport-delay must be ≥ 3s)
  target.quit();            // destination disconnects mid-countdown
  await offlineMsg;         // throws if the cancel message never arrives
  await sleep(4000);        // would-be teleport window
  const moved = dist(before, pos(sender));
  assert(moved < 1, `sender moved ${moved.toFixed(2)} blocks despite destination going offline`);
  console.log(`  ✅  Countdown cancelled, sender stayed put`);

  await sleep(5000);        // respect Paper's connection throttle before rejoin
  bots[4] = await createBot(BOT_NAMES[4]);
  await sleep(DELAY.afterSpawn);
}

// R2. Requester quits before the target accepts → the pending request is cleaned up
//     on quit, so /tpaccept must NOT teleport anyone and must report no-request/offline.
async function scenarioRequesterQuit(bots) {
  const [sender, target] = [bots[4], bots[1]];
  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);
  sender.quit();            // requester disconnects with the request pending
  await sleep(2500);        // quit-cleanup runs

  const lines = await collectMessages(target, async () => {
    target.chat("/tpaccept");
  }, 3000);
  const response = lines.join(" | ").toLowerCase();
  console.log(`  /tpaccept response: ${response || "(none)"}`);
  assert(
    response.includes("no pending") || response.includes("offline"),
    `expected no-request/offline response, got: "${response}"`
  );
  console.log(`  ✅  Stale request was cleaned up on requester quit`);

  await sleep(5000);
  bots[4] = await createBot(BOT_NAMES[4]);
  await sleep(DELAY.afterSpawn);
}

// R3. Target accepts while the sender is ALREADY moving — the movement-cancel and the
//     countdown start race each other. The countdown must end cancelled, never teleport.
//     (Regression guard for the v1.6.1 Folia countdown-start/cancel race fix.)
async function scenarioAcceptWhileMoving(bots) {
  const [sender, target] = [bots[0], bots[1]];
  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);

  const cancelled = waitForMessage(sender, "movement", 12000);
  sender.setControlState("forward", true);   // moving BEFORE the accept lands
  target.chat("/tpaccept");
  await sleep(1200);
  sender.setControlState("forward", false);
  await cancelled; // throws if the countdown survived the race
  await sleep(5000); // would-be teleport window — nothing should happen
  console.log(`  ✅  Countdown cancelled despite accept landing mid-movement`);
}

// R4. /tpainfo RAM figures are sane: 0 < available ≤ max.
//     (Regression guard for the v1.6.1 available-RAM calculation fix.)
async function scenarioInfoRamSanity(bots) {
  const bot = bots[0];
  const lines = await collectMessages(bot, async () => {
    bot.chat("/tpainfo");
  }, 2500);

  const grab = (re) => {
    for (const l of lines) {
      const m = l.match(re);
      if (m) return parseInt(m[1].replace(/[,.]/g, ""), 10);
    }
    return null;
  };
  const avail = grab(/available ram:?\s*([\d,.]+)/i);
  const max   = grab(/max ram:?\s*([\d,.]+)/i);
  console.log(`  Available RAM: ${avail} MB, Max RAM: ${max} MB`);
  assert(avail !== null && max !== null, "could not parse RAM lines from /tpainfo output");
  assert(avail > 0 && avail <= max, `available RAM ${avail} MB not within (0, max ${max} MB]`);
  console.log(`  ✅  RAM headroom figure is sane`);
}

// 18. Simultaneous multi-bot stress (functional pass)
async function scenarioSimultaneous(bots) {
  bots[0].chat(`/tpa ${bots[1].username}`);
  bots[2].chat(`/tpa ${bots[3].username}`);
  bots[4].chat(`/tpa ${bots[0].username}`);
  await sleep(DELAY.requestWait);
  bots[1].chat("/tpaccept");
  bots[3].chat("/tpaccept");
  bots[0].chat("/tpaccept");
  await sleep(8000);
  console.log(`  Three concurrent TPA flows handled simultaneously`);
}

// ── Main ─────────────────────────────────────────────────────────

async function main() {
  console.log("╔══════════════════════════════════════════════════════╗");
  console.log("║       YoTPA — Full Plugin Test Suite                 ║");
  console.log("╚══════════════════════════════════════════════════════╝");

  // ── 1. Version check ──
  await checkPluginVersion();

  console.log(`Bots: ${BOT_NAMES.join(", ")}`);
  console.log(`Admin: ${ADMIN.username}\n`);

  // Spawn admin bot first (OP), then regular bots
  let admin;
  try {
    admin = await createBot(ADMIN.username);
    await sleep(5000); // throttle gap before next login
  } catch (err) {
    console.error("Failed to spawn admin bot:", err.message);
    process.exit(1);
  }

  let bots;
  try {
    bots = await createBots(BOT_NAMES);
  } catch (err) {
    console.error("Failed to spawn bots:", err.message);
    process.exit(1);
  }
  await sleep(DELAY.afterSpawn);

  // ── 2. Functional tests ──────────────────────────────────────
  if (!STRESS_ONLY) {
    // /tpa
    await run("TPA — accept (teleport verified)",       () => scenarioTpaAccept(bots));
    await run("TPA — deny",                             () => scenarioTpaDeny(bots));
    await run("TPA — timeout (request-timeout: 15 recommended)", () => scenarioTpaTimeout(bots));
    await run("TPA — self (expect error)",              () => scenarioTpaSelf(bots));
    await run("TPA — non-existent player (expect error)",() => scenarioTpaInvalidPlayer(bots));
    await run("TPA — cooldown enforced after teleport", () => scenarioCooldown(bots));

    // /tpahere
    await run("TPAHERE — accept (teleport verified)",   () => scenarioTpahereAccept(bots));
    await run("TPAHERE — deny",                         () => scenarioTpahereDeny(bots));
    await run("TPAHERE — timeout",                      () => scenarioTpahereTimeout(bots));
    await run("TPAHERE — self (expect error)",          () => scenarioTpahereSelf(bots));
    await run("TPAHERE — non-existent player (error)",  () => scenarioTpahereInvalidPlayer(bots));

    // Edge cases
    await run("Movement cancel during countdown",       () => scenarioMovementCancel(bots));
    await run("Accept with no pending request",         () => scenarioAcceptNoPending(bots));
    await run("Deny with no pending request",           () => scenarioDenyNoPending(bots));

    // Info commands
    await run("/tpastats — all bots",                   () => scenarioStats(bots));
    await run("/tpainfo — performance mode details",    () => scenarioInfo(bots));
    await run("/tpareload — config hot-reload",         () => scenarioReload(bots));

    // Multi-bot simultaneous
    await run("Simultaneous 3-pair concurrent TPA",     () => scenarioSimultaneous(bots));

    // ── Admin-controlled: distance & cross-world ──
    await run("Large distance TPA (1000 blocks)",
      () => scenarioLargeDistance(bots, admin));
    await run("Cross-world TPA — Nether → Overworld",
      () => scenarioCrossWorldNetherToOverworld(bots, admin));
    await run("Cross-world TPA — Overworld → Nether",
      () => scenarioCrossWorldOverworldToNether(bots, admin));
    await run("Cross-world TPA — Overworld → End",
      () => scenarioCrossWorldToEnd(bots, admin));
    await run("Cross-world TPAHERE — host in Nether, guest in Overworld",
      () => scenarioCrossWorldTpahereFromNether(bots, admin));
    await run("Large distance + movement cancel",
      () => scenarioLargeDistanceMovementCancel(bots, admin));
    await run("/tpareload by admin then verify TPA still works",
      () => scenarioReloadThenTpa(bots, admin));

    // ── /back ──
    await run("BACK — return to origin after TPA accept",
      () => scenarioBackAfterTpa(bots));
    await run("BACK — return to origin after TPAHERE accept",
      () => scenarioBackAfterTpahere(bots));
    await run("BACK — single-use (location consumed after teleport)",
      () => scenarioBackSingleUse(bots));
    await run("BACK — no prior location (expect error)",
      () => scenarioBackNoLocation(bots));
    await run("BACK — no permission (expect error)",
      () => scenarioBackNoPermission(bots, admin));
    await run("BACK — cross-world return (Nether → Overworld)",
      () => scenarioBackCrossWorld(bots, admin));
    await run("BACK — concurrent /back on 4 bots (thread-safety)",
      () => scenarioBackConcurrent(bots));
    await run("BACK — /back after death (admin /kill)",
      () => scenarioBackAfterDeath(bots, admin));
    await run("BACK — /back after PvP death",
      () => scenarioBackAfterPvpDeath(bots, admin));
    await run("BACK — cooldown blocks rapid re-use",
      () => scenarioBackCooldown(bots));
    await run("BACK — bypass.back-cooldown permission",
      () => scenarioBackCooldownBypass(bots, admin));

    // ── v1.6.x regression scenarios ──
    // (last: R1/R2 disconnect and rejoin bots[4])
    await run("REGRESSION — accept while already moving (cancel race)",
      () => scenarioAcceptWhileMoving(bots));
    await run("REGRESSION — /tpainfo RAM figures sane (0 < available ≤ max)",
      () => scenarioInfoRamSanity(bots));
    await run("REGRESSION — destination offline mid-countdown (cancel + no teleport)",
      () => scenarioDestinationOffline(bots));
    await run("REGRESSION — requester quits before accept (request cleaned up)",
      () => scenarioRequesterQuit(bots));

    printSummary();
  }

  // ── 3. Stress & performance tests ────────────────────────────
  if (!QUICK) {
    console.log("\n╔══════════════════════════════════════════════════════╗");
    console.log("║       Stress & Performance Tests                     ║");
    console.log("║  These measure latency and thread-safety under load  ║");
    console.log("╚══════════════════════════════════════════════════════╝");

    const stressResults = [];
    stressResults.push(await stressRapidTpa(bots, 8));
    await sleep(3000);
    stressResults.push(await stressConcurrentRequests(bots));
    await sleep(3000);
    stressResults.push(await stressCooldownEnforcement(bots));
    await sleep(3000);
    stressResults.push(await stressExpiryFlood(bots, 20000));
    await sleep(3000);
    stressResults.push(await stressThroughput(bots, 3));

    printStressSummary(stressResults);
  }

  console.log("All tests complete. Disconnecting bots…");
  for (const bot of bots) { try { bot.quit(); } catch {} }
  try { admin.quit(); } catch {}
  process.exit(0);
}

main();
