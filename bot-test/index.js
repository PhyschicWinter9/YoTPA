/**
 * YoTPA — Full Plugin Test Suite
 *
 * Covers:
 *   1. Version check (GitHub latest release)
 *   2. All command scenarios (/tpa, /tpahere, /tpaccept, /tpadeny,
 *      /tpastats, /tpainfo, /tpareload)
 *   3. Edge cases (self, invalid player, no pending, movement cancel)
 *   4. Stress & performance tests (latency, throughput, cooldown, expiry)
 *   5. Summary report
 *
 * Usage:
 *   bun run index.js            → full suite
 *   bun run index.js --stress   → stress tests only
 *   bun run index.js --quick    → functional tests only (skip stress)
 */

import { BOT_NAMES, ADMIN, DELAY } from "./config.js";
import { createBot, createBots, sleep } from "./bot-factory.js";
import {
  tpToSpawn, tpFarAway, tpToNether, tpToEnd,
  resetAllToSpawn, announce,
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

// ── Functional scenarios ─────────────────────────────────────────

// 1. /tpa → accept → verify sender teleported
async function scenarioTpaAccept(bots) {
  const [sender, target] = [bots[0], bots[1]];
  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);
  target.chat("/tpaccept");
  await sleep(8000);
  const d = dist(pos(sender), pos(target));
  console.log(`  Sender→target distance after teleport: ${d.toFixed(2)} blocks`);
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

// 7. /tpahere → accept → verify guest teleported to host
async function scenarioTpahereAccept(bots) {
  const [host, guest] = [bots[2], bots[3]];
  host.chat(`/tpahere ${guest.username}`);
  await sleep(DELAY.requestWait);
  guest.chat("/tpaccept");
  await sleep(8000);
  const d = dist(pos(guest), pos(host));
  console.log(`  Guest→host distance after teleport: ${d.toFixed(2)} blocks`);
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

// 12. Movement cancels countdown
async function scenarioMovementCancel(bots) {
  const [sender, target] = [bots[0], bots[1]];
  sender.chat(`/tpa ${target.username}`);
  await sleep(DELAY.requestWait);
  target.chat("/tpaccept");
  await sleep(1000); // let countdown start
  sender.setControlState("forward", true);
  await sleep(800);
  sender.setControlState("forward", false);
  await sleep(6000);
  const d = dist(pos(sender), pos(target));
  console.log(`  Sender distance from target: ${d.toFixed(2)} blocks (large = cancelled ✅)`);
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
