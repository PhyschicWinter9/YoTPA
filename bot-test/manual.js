/**
 * manual.js — Interactive, human-controlled bot for live-testing YoTPA.
 *
 * Spawns one bot and gives you a terminal REPL to drive it while watching
 * the plugin's chat responses print in real time. Useful for poking at
 * scenarios the automated suite (index.js) doesn't cover, or just eyeballing
 * behavior on a live server.
 *
 * Usage:
 *   bun run manual.js [username]
 *   bun run manual.js TestBot_Manual
 *
 * REPL syntax:
 *   /tpa Alice          -> sent verbatim as chat (works for any command)
 *   hello there          -> sent verbatim as chat too (plain messages)
 *   !move forward 1000   -> hold forward for 1000ms then release
 *   !move back 500
 *   !move left 500
 *   !move right 500
 *   !jump                -> single jump
 *   !sprint on|off        -> toggle sprint control state
 *   !look <yaw> <pitch>  -> radians; e.g. !look 0 0
 *   !lookat <player>     -> face a nearby player/bot by name
 *   !pos                 -> print current position/world
 *   !players              -> list nearby players the bot can see
 *   !stop                 -> clear all movement control states
 *   !help                 -> show this list again
 *   !quit / !exit          -> disconnect and end the process
 */

import readline from "node:readline";
import { createBot } from "./bot-factory.js";
import { BOT_NAMES } from "./config.js";

const username = process.argv[2] || "TestBot_Manual";

const HELP = `
Commands:
  /<command> args        send as chat verbatim (e.g. /tpa Alice, /tpaccept)
  <plain text>            send as chat verbatim
  !move <dir> <ms>        dir = forward|back|left|right, hold then release
  !jump                   single jump
  !sprint on|off          toggle sprint control state
  !look <yaw> <pitch>     radians (e.g. !look 0 0)
  !lookat <player>        face a nearby player by name
  !pos                    print current position/world
  !players                list nearby players
  !stop                   clear all movement control states
  !help                   show this list
  !quit / !exit           disconnect and exit
`;

function printHelp() {
  console.log(HELP);
}

async function main() {
  console.log(`[…] connecting as ${username} …`);
  if (!BOT_NAMES.includes(username) && username !== "TestBot_Manual") {
    console.log(`[~] note: "${username}" isn't in config.js BOT_NAMES — connecting anyway.`);
  }

  const bot = await createBot(username);
  console.log(`[+] ${username} ready. Type !help for commands, !quit to exit.`);
  printHelp();

  const rl = readline.createInterface({ input: process.stdin, output: process.stdout, prompt: `${username}> ` });
  rl.prompt();

  const validDirs = new Set(["forward", "back", "left", "right"]);

  rl.on("line", async (raw) => {
    const line = raw.trim();
    if (!line) return rl.prompt();

    try {
      if (line.startsWith("!")) {
        const [cmd, ...args] = line.slice(1).split(/\s+/);

        switch (cmd) {
          case "help":
            printHelp();
            break;

          case "quit":
          case "exit":
            console.log("[…] disconnecting …");
            bot.end();
            rl.close();
            process.exit(0);
            return;

          case "move": {
            const [dir, msStr] = args;
            const ms = parseInt(msStr, 10) || 1000;
            if (!validDirs.has(dir)) {
              console.log(`[!] unknown direction "${dir}". Use forward|back|left|right.`);
              break;
            }
            bot.setControlState(dir, true);
            setTimeout(() => bot.setControlState(dir, false), ms);
            console.log(`[…] holding ${dir} for ${ms}ms`);
            break;
          }

          case "jump":
            bot.setControlState("jump", true);
            setTimeout(() => bot.setControlState("jump", false), 250);
            break;

          case "sprint": {
            const on = args[0] === "on";
            bot.setControlState("sprint", on);
            console.log(`[…] sprint ${on ? "on" : "off"}`);
            break;
          }

          case "look": {
            const yaw = parseFloat(args[0]);
            const pitch = parseFloat(args[1]);
            if (Number.isNaN(yaw) || Number.isNaN(pitch)) {
              console.log("[!] usage: !look <yaw> <pitch> (radians)");
              break;
            }
            await bot.look(yaw, pitch, true);
            break;
          }

          case "lookat": {
            const name = args[0];
            const target = bot.players[name]?.entity;
            if (!target) {
              console.log(`[!] no visible player named "${name}"`);
              break;
            }
            await bot.lookAt(target.position.offset(0, target.height ?? 1.6, 0));
            break;
          }

          case "pos": {
            const p = bot.entity.position;
            console.log(`[pos] ${p.x.toFixed(2)}, ${p.y.toFixed(2)}, ${p.z.toFixed(2)} in ${bot.game?.dimension ?? "unknown"}`);
            break;
          }

          case "players": {
            const names = Object.keys(bot.players).filter((n) => n !== username);
            console.log(names.length ? `[players] ${names.join(", ")}` : "[players] none visible");
            break;
          }

          case "stop":
            for (const dir of validDirs) bot.setControlState(dir, false);
            bot.setControlState("jump", false);
            bot.setControlState("sprint", false);
            console.log("[…] all movement stopped");
            break;

          default:
            console.log(`[!] unknown command "!${cmd}". Type !help.`);
        }
      } else {
        bot.chat(line);
      }
    } catch (err) {
      console.error(`[!] error: ${err.message}`);
    }

    rl.prompt();
  });

  rl.on("close", () => {
    bot.end();
    process.exit(0);
  });

  bot.on("end", () => {
    console.log("[-] bot disconnected, exiting.");
    process.exit(0);
  });
}

main().catch((err) => {
  console.error(`[!] fatal: ${err.message}`);
  process.exit(1);
});
