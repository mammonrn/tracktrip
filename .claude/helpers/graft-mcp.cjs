#!/usr/bin/env node
'use strict';
// Launcher for the graft MCP server, referenced from .mcp.json.
//
// .mcp.json would normally just say `command: "graft"`, which assumes the binary
// is already on PATH. On a fresh session container it isn't yet — and MCP servers
// are spawned at startup, concurrently with the SessionStart hook that installs
// it, so "the hook will have run by then" is a race, not a guarantee. Resolving
// through ensure() removes the race: whichever of the two gets there first does
// the install, the other waits on the same lock.
const { spawnSync } = require('child_process');
const { ensure, projectDir } = require('./graft-ensure.cjs');

const cli = ensure();
if (!cli) {
  // Exiting non-zero shows the server as failed in /mcp, which is the honest
  // signal: the CLI surface described in .claude/skills/graft/SKILL.md still
  // works, so Claude can fall back to `graft ask` over Bash.
  process.stderr.write('[graft] MCP server unavailable — graft could not be installed\n');
  process.exit(1);
}

// Pass the repo path explicitly: MCP servers inherit whatever cwd Claude Code
// was launched from, which is not necessarily the project root.
const r = spawnSync(process.execPath, [cli, 'mcp', projectDir], { stdio: 'inherit' });
process.exit(r.status ?? 0);
