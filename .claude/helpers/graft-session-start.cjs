#!/usr/bin/env node
'use strict';
// SessionStart: make sure this machine actually has graft and a built graph,
// then hand off to graft's own session-start hook.
//
// Sessions for this repo run on a fresh container, so both halves of graft are
// missing every time: the npm package (installed globally, not in the repo) and
// graft/ itself (a generated cache, gitignored — see the note in .gitignore).
// Rebuilding is cheap enough that regenerating beats committing: a full parse of
// all ~250 indexed files takes about 4 seconds, needs no API key and costs
// nothing, whereas a committed graph would be a 14 MB snapshot that is wrong the
// moment anyone lands a commit without regenerating it — and a graph pointing at
// stale file:line spans is worse than no graph, because the agent trusts it.
//
// Ordering matters and is why this is one script rather than two hook entries:
// graft's own session-start reads graft/INDEX.md and skips when it is absent, so
// it has to run after the build, not alongside it.
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const { ensure, projectDir } = require('./graft-ensure.cjs');

// stdin carries the hook payload; graft's hook parses it for the project dir,
// so read it here and pass it through rather than letting the child find empty
// stdin. Not a TTY in practice, but a missing payload must not be fatal.
let payload = '';
try { payload = fs.readFileSync(0, 'utf8'); } catch { /* no stdin */ }

const cli = ensure();
if (!cli) {
  // Offline, or npm unavailable. Say so on stderr and exit clean: a session
  // without the index is slower, not broken.
  process.stderr.write('[graft] not available on this machine — continuing without it\n');
  process.exit(0);
}

// .graph/wiring.json is what every query reads, so its absence — not the
// directory's — is the test for "this container has no graph yet". When it is
// present the graph is reused as-is: graft refreshes changed files on each
// query anyway, so an unconditional rebuild here would only add startup delay.
if (!fs.existsSync(path.join(projectDir, 'graft', '.graph', 'wiring.json'))) {
  const built = spawnSync(process.execPath, [cli, 'build', projectDir], {
    // `2` for the child's stdout, not 'inherit': graft build prints its summary
    // there, and this hook's stdout is the SessionStart channel — plain text in
    // front of the JSON graft's own hook emits would corrupt it. Everything the
    // build says is diagnostics, so send both streams to stderr.
    stdio: ['ignore', 2, 'inherit'],
    env: { ...process.env, DO_NOT_TRACK: process.env.DO_NOT_TRACK ?? '1' },
  });
  if (built.status !== 0) {
    process.stderr.write('[graft] build failed — continuing without the graph\n');
    process.exit(0);
  }
}

// Hand off to the shim `graft init` wrote, so the orientation banner Claude sees
// is graft's own and stays correct across graft upgrades.
const shim = path.join(__dirname, 'graft-hooks.cjs');
if (fs.existsSync(shim)) {
  const r = spawnSync(process.execPath, [shim, 'session-start'], {
    input: payload,
    stdio: ['pipe', 'inherit', 'inherit'],
  });
  process.exit(r.status ?? 0);
}
