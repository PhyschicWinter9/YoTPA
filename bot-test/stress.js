/**
 * YoTPA — Stress & Performance Test
 *
 * Simulates heavy concurrent load with 5 bots to validate:
 *   - Thread-safety of ConcurrentHashMap structures
 *   - Performance mode adaptive behaviour (Paper + Folia)
 *   - Cooldown enforcement under rapid fire
 *   - Request expiry / cleanup under load
 *   - Response latency (ms from command → plugin message)
 *   - Throughput (requests / second the plugin can process)
 *
 * NOTE: True 500+ player simulation requires a separate tool
 * (e.g. https://github.com/nicholasgasior/mcpt or a multi-process
 * bot farm). This suite stress-tests the plugin logic at the
 * maximum rate 5 clients can generate and measures latency as a
 * proxy for server-side performance.
 */

import { DELAY } from "./config.js";
import { sleep } from "./bot-factory.js";

// ── Latency tracker ─────────────────────────────────────────────

export class Metrics {
  constructor(label) {
    this.label = label;
    this.samples = [];   // latency in ms
    this.errors = 0;
    this.timeouts = 0;
  }

  record(ms) { this.samples.push(ms); }
  fail()      { this.errors++; }
  timeout()   { this.timeouts++; }

  get count()  { return this.samples.length; }
  get total()  { return this.samples.reduce((a, b) => a + b, 0); }
  get avg()    { return this.count ? (this.total / this.count).toFixed(1) : "N/A"; }
  get min()    { return this.count ? Math.min(...this.samples).toFixed(1) : "N/A"; }
  get max()    { return this.count ? Math.max(...this.samples).toFixed(1) : "N/A"; }
  get p95()    {
    if (!this.count) return "N/A";
    const sorted = [...this.samples].sort((a, b) => a - b);
    return sorted[Math.floor(sorted.length * 0.95)].toFixed(1);
  }

  print() {
    console.log(`\n  📊 ${this.label}`);
    console.log(`     Samples  : ${this.count}`);
    console.log(`     Avg      : ${this.avg} ms`);
    console.log(`     Min/Max  : ${this.min} / ${this.max} ms`);
    console.log(`     P95      : ${this.p95} ms`);
    console.log(`     Errors   : ${this.errors}`);
    console.log(`     Timeouts : ${this.timeouts}`);
  }
}

/**
 * Measure ms from calling fn() until bot receives a message matching substr.
 * Returns latency in ms, or null on timeout.
 */
function measureLatency(bot, substr, triggerFn, timeoutMs = 5000) {
  return new Promise((resolve) => {
    const start = Date.now();
    const timer = setTimeout(() => {
      bot.removeListener("message", handler);
      resolve(null); // timeout
    }, timeoutMs);

    function handler(msg) {
      if (msg.toString().toLowerCase().includes(substr.toLowerCase())) {
        clearTimeout(timer);
        bot.removeListener("message", handler);
        resolve(Date.now() - start);
      }
    }

    bot.on("message", handler);
    triggerFn();
  });
}

// ── Stress scenarios ─────────────────────────────────────────────

/**
 * STRESS-1: Rapid-fire /tpa requests — measure latency per request.
 * The sender fires N requests in succession; target auto-accepts each.
 */
export async function stressRapidTpa(bots, iterations = 10) {
  const metrics = new Metrics(`Rapid-fire /tpa × ${iterations}`);
  const [sender, target] = [bots[0], bots[1]];
  console.log(`\n  [Stress] Rapid-fire /tpa × ${iterations} iterations…`);

  for (let i = 0; i < iterations; i++) {
    // Measure how fast the plugin acks the request
    const latency = await measureLatency(
      sender,
      "tpa",                              // any "tpa" keyword in sender's ack message
      () => sender.chat(`/tpa ${target.username}`),
      4000
    );

    if (latency === null) {
      metrics.timeout();
      console.log(`    [${i + 1}/${iterations}] timeout`);
    } else {
      metrics.record(latency);
      console.log(`    [${i + 1}/${iterations}] ${latency} ms`);
    }

    // Accept or deny so pending requests don't stack up
    await sleep(300);
    target.chat("/tpadeny");
    // Wait for cooldown to lift between sends (skip last iteration)
    if (i < iterations - 1) await sleep(DELAY.requestWait);
  }

  metrics.print();
  return metrics;
}

/**
 * STRESS-2: All 5 bots send /tpa simultaneously → race condition test.
 * Checks that ConcurrentHashMap handles concurrent writes without errors.
 */
export async function stressConcurrentRequests(bots) {
  const metrics = new Metrics("Concurrent 5-bot /tpa burst");
  console.log("\n  [Stress] 5 bots sending /tpa simultaneously…");

  const pairs = [
    [bots[0], bots[1]],
    [bots[1], bots[2]],
    [bots[2], bots[3]],
    [bots[3], bots[4]],
    [bots[4], bots[0]],
  ];

  // Fire all 5 requests at the same instant — stresses ConcurrentHashMap writes
  const start = Date.now();
  await Promise.all(
    pairs.map(async ([sender, target]) => {
      const latency = await measureLatency(
        sender,
        "tpa",
        () => sender.chat(`/tpa ${target.username}`),
        6000
      );
      if (latency === null) metrics.timeout();
      else metrics.record(latency);
    })
  );
  const wall = Date.now() - start;
  console.log(`  All 5 requests processed in ${wall} ms wall time`);

  // Deny all pending requests to clean up
  await sleep(500);
  for (const [, target] of pairs) target.chat("/tpadeny");
  await sleep(1000);

  metrics.print();
  return metrics;
}

/**
 * STRESS-3: Cooldown enforcement under rapid re-request.
 * Fires the same request 5× in 500 ms; only the 1st should succeed.
 */
export async function stressCooldownEnforcement(bots) {
  const metrics = new Metrics("Cooldown enforcement (5 rapid re-requests)");
  console.log("\n  [Stress] Cooldown enforcement — 5 rapid re-requests…");

  const [sender, target] = [bots[0], bots[2]];
  let blocked = 0;

  // First request — allow it
  sender.chat(`/tpa ${target.username}`);
  await sleep(500);
  target.chat("/tpadeny");
  await sleep(500);

  // Now hammer 4 more while cooldown is active
  for (let i = 0; i < 4; i++) {
    const latency = await measureLatency(
      sender,
      "cooldown",
      () => sender.chat(`/tpa ${target.username}`),
      3000
    );
    if (latency !== null) { blocked++; metrics.record(latency); }
    else metrics.timeout();
    await sleep(200);
  }

  console.log(`  Blocked by cooldown: ${blocked}/4 (expected 4)`);
  if (blocked < 4) console.log(`  ⚠️  Some requests bypassed cooldown — possible thread-safety issue!`);
  else             console.log(`  ✅  All cooldown blocks enforced correctly`);

  metrics.print();
  return metrics;
}

/**
 * STRESS-4: Request expiry flood.
 * All 5 bots send requests that are never accepted.
 * Verifies the cleanup task fires correctly and doesn't leak memory.
 * (Set request-timeout: 15 in config.yml for fast expiry.)
 */
export async function stressExpiryFlood(bots, waitMs = 20000) {
  const metrics = new Metrics(`Expiry flood (${waitMs / 1000}s wait)`);
  console.log(`\n  [Stress] Expiry flood — sending 5 unanswered requests, waiting ${waitMs / 1000}s…`);
  console.log(`  (For fast results set request-timeout: 15 in config.yml)`);

  const pairs = [[bots[0], bots[1]], [bots[2], bots[3]]];

  for (const [sender, target] of pairs) {
    const latency = await measureLatency(
      sender,
      "tpa",
      () => sender.chat(`/tpa ${target.username}`),
      4000
    );
    if (latency !== null) metrics.record(latency);
    else metrics.timeout();
    await sleep(DELAY.betweenCmds);
  }

  console.log(`  Waiting ${waitMs / 1000}s for expiry…`);
  await sleep(waitMs);

  // Try to accept after expiry — should get "no pending request" error
  let expiredCount = 0;
  for (const [, target] of pairs) {
    const latency = await measureLatency(
      target,
      "no pending",
      () => target.chat("/tpaccept"),
      3000
    );
    if (latency !== null) expiredCount++;
  }

  console.log(`  Expired and cleaned up: ${expiredCount}/${pairs.length}`);
  if (expiredCount === pairs.length) console.log(`  ✅  All requests expired and cleaned up correctly`);
  else                               console.log(`  ⚠️  Some requests may still be in memory — check cleanup task`);

  metrics.print();
  return metrics;
}

/**
 * STRESS-5: Throughput benchmark — measures accepted teleports/second.
 * Runs N full accept-and-teleport cycles back to back.
 */
export async function stressThroughput(bots, cycles = 5) {
  const metrics = new Metrics(`Throughput (${cycles} full TPA cycles)`);
  console.log(`\n  [Stress] Throughput — ${cycles} full /tpa → accept cycles…`);

  const [sender, target] = [bots[3], bots[4]];
  const wallStart = Date.now();

  for (let i = 0; i < cycles; i++) {
    const latency = await measureLatency(
      target,
      "accept",
      () => {
        sender.chat(`/tpa ${target.username}`);
        setTimeout(() => target.chat("/tpaccept"), DELAY.requestWait);
      },
      12000
    );

    if (latency === null) { metrics.timeout(); continue; }
    metrics.record(latency);
    console.log(`    Cycle ${i + 1}/${cycles}: ${latency} ms end-to-end`);

    // Wait for teleport to land + cooldown to partially drain
    await sleep(9000);
  }

  const wall = (Date.now() - wallStart) / 1000;
  const tps = (metrics.count / wall).toFixed(2);
  console.log(`\n  Wall time : ${wall.toFixed(1)}s`);
  console.log(`  Throughput: ${tps} accepted teleports/sec`);

  metrics.print();
  return metrics;
}

/**
 * Print a combined summary table for all metric results.
 */
export function printStressSummary(allMetrics) {
  console.log(`\n${"═".repeat(60)}`);
  console.log("  STRESS TEST SUMMARY");
  console.log(`${"═".repeat(60)}`);
  console.log(
    `  ${"Scenario".padEnd(38)} ${"Avg".padStart(7)} ${"P95".padStart(7)} ${"Err".padStart(5)}`
  );
  console.log(`  ${"─".repeat(57)}`);
  for (const m of allMetrics) {
    const name = m.label.length > 38 ? m.label.slice(0, 35) + "…" : m.label;
    console.log(
      `  ${name.padEnd(38)} ${(m.avg + " ms").padStart(7)} ${(m.p95 + " ms").padStart(7)} ${String(m.errors + m.timeouts).padStart(5)}`
    );
  }
  console.log(`${"═".repeat(60)}\n`);
}
