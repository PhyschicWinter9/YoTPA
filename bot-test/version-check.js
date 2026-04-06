/**
 * YoTPA — GitHub release version checker
 * Fetches the latest release tag from GitHub and compares against currentVersion in config.js
 */

import { PLUGIN } from "./config.js";

/**
 * Semantic-version comparison (major.minor.patch).
 * Returns  1 if a > b,  -1 if a < b,  0 if equal.
 * Falls back to string compare for non-semver tags.
 */
function compareSemver(a, b) {
  const parse = (v) => v.replace(/^v/, "").split(".").map(Number);
  const [aMaj, aMin = 0, aPat = 0] = parse(a);
  const [bMaj, bMin = 0, bPat = 0] = parse(b);
  if (aMaj !== bMaj) return aMaj > bMaj ? 1 : -1;
  if (aMin !== bMin) return aMin > bMin ? 1 : -1;
  if (aPat !== bPat) return aPat > bPat ? 1 : -1;
  return 0;
}

export async function checkPluginVersion() {
  const { currentVersion, githubRepo } = PLUGIN;
  const apiUrl = `https://api.github.com/repos/${githubRepo}/releases/latest`;

  console.log(`\n${"─".repeat(60)}`);
  console.log(`  YoTPA Version Check`);
  console.log(`  Installed : v${currentVersion}`);
  process.stdout.write(`  Latest    : checking…`);

  try {
    const res = await fetch(apiUrl, {
      headers: {
        "User-Agent": "YoTPA-bot-test",
        Accept: "application/vnd.github+json",
      },
      signal: AbortSignal.timeout(8000),
    });

    // Overwrite the "checking…" line
    process.stdout.write("\r" + " ".repeat(40) + "\r");

    if (res.status === 404) {
      console.log(`  Latest    : (no releases published yet)`);
      console.log(`${"─".repeat(60)}\n`);
      return;
    }

    if (!res.ok) {
      console.log(`  Latest    : GitHub API error ${res.status} — skipping`);
      console.log(`${"─".repeat(60)}\n`);
      return;
    }

    const data = await res.json();
    const latestTag = data.tag_name ?? null;

    if (!latestTag) {
      console.log(`  Latest    : (could not parse tag)`);
      console.log(`${"─".repeat(60)}\n`);
      return;
    }

    const latest = latestTag.replace(/^v/, "");
    const cmp = compareSemver(latest, currentVersion);

    console.log(`  Latest    : v${latest}`);

    if (cmp === 0) {
      console.log(`  Status    : ✅ Up to date`);
    } else if (cmp > 0) {
      console.log(`  Status    : ⚠️  UPDATE AVAILABLE  v${currentVersion} → v${latest}`);
      console.log(`  Release   : ${data.html_url}`);
      if (data.body) {
        const preview = data.body.split("\n").slice(0, 4).join("\n             ");
        console.log(`  Notes     : ${preview}`);
      }
    } else {
      // currentVersion is newer than latest release (dev / pre-release)
      console.log(`  Status    : 🚧 Dev build (v${currentVersion} > v${latest})`);
    }
  } catch (err) {
    process.stdout.write("\r" + " ".repeat(40) + "\r");
    if (err.name === "TimeoutError") {
      console.log(`  Latest    : (request timed out — skipping)`);
    } else {
      console.log(`  Latest    : (error: ${err.message} — skipping)`);
    }
  }

  console.log(`${"─".repeat(60)}\n`);
}
