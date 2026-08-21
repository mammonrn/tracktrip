'use strict';
// Resolve the graft CLI, installing it on demand.
//
// Why this file exists: graft's own shims (graft-hooks.cjs, graft-statusline.cjs,
// written by `graft init`) locate an *already installed* graft and no-op when they
// can't find one. That is the right behaviour on a developer laptop, where
// `npm install -g @nanonets/graft` is a one-time step. It is the wrong behaviour
// here, because Claude Code sessions for this repo run on a throwaway container:
// the repo is cloned fresh, nothing installed by a previous session survives, and
// so every session would start with graft silently absent — hooks no-op, the MCP
// server fails to spawn, and the skill points at a graft/ directory that isn't
// there. This module closes that gap by installing graft when it is missing.
//
// Deliberately NOT solved by adding @nanonets/graft to package.json: the VPS runs
// `npm ci` from that manifest and .github/workflows/backend-tests.yml watches
// package.json / package-lock.json. A dev-only indexing tool has no business in
// the file that decides what gets installed on the production box.
const fs = require('fs');
const path = require('path');
const { spawnSync, execFileSync } = require('child_process');

// Pinned to patch releases of the minor that was wired in. graft is pre-1.0, so
// a minor bump may change the hook/skill contract that `graft init` wrote into
// .claude/ — bump this line and re-run `graft init` together, not separately.
const SPEC = '@nanonets/graft@^0.10.1';
const PKG = '@nanonets/graft';

// Derived from this file's own location (<repo>/.claude/helpers/) rather than
// process.cwd(), because MCP servers inherit whatever directory Claude Code was
// launched from — which is not reliably the repo root.
const projectDir = process.env.CLAUDE_PROJECT_DIR || path.resolve(__dirname, '..', '..');
// Fallback install target when a global install isn't permitted. Gitignored;
// under .claude/ rather than the repo root so it never lands near deploy files.
const localPrefix = path.join(projectDir, '.claude', '.graft-runtime');

function log(msg) {
  // stderr, never stdout: this module is also loaded by the MCP wrapper, where
  // stdout is the JSON-RPC transport and one stray line corrupts the session.
  process.stderr.write(`[graft-ensure] ${msg}\n`);
}

// Absolute path to graft's CLI entrypoint as resolved from `base`, or null.
function cliFrom(base) {
  try {
    const manifestPath = require.resolve(`${PKG}/package.json`, { paths: [base] });
    const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
    const bin = typeof manifest.bin === 'string'
      ? manifest.bin
      : manifest.bin && (manifest.bin.graft || Object.values(manifest.bin)[0]);
    if (!bin) return null;
    const entry = path.join(path.dirname(manifestPath), bin);
    return fs.existsSync(entry) ? entry : null;
  } catch {
    return null;
  }
}

// npm's global node_modules, asked rather than guessed so this works the same
// under nvm/volta/Homebrew as it does on the plain node in the container image.
function globalBase() {
  try {
    const root = execFileSync('npm', ['root', '-g'], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
      shell: process.platform === 'win32',
    }).trim();
    // require.resolve searches `<base>/node_modules`, so hand it the parent.
    return root ? path.dirname(root) : null;
  } catch {
    return null;
  }
}

function findCli() {
  const bases = [localPrefix, globalBase(), projectDir].filter(Boolean);
  for (const base of bases) {
    const cli = cliFrom(base);
    if (cli) return cli;
  }
  return null;
}

// mkdir is atomic, so it doubles as a mutex. The SessionStart hook and the MCP
// wrapper both call ensure() and Claude Code starts them concurrently; without
// this they would race into two simultaneous `npm install -g` runs on the same
// prefix. The loser waits for the winner rather than installing again.
function withInstallLock(fn) {
  const lock = path.join(require('os').tmpdir(), 'graft-ensure.lock');
  const deadlineMs = Date.now() + 120000;
  for (;;) {
    try {
      fs.mkdirSync(lock);
      break;
    } catch (e) {
      if (e.code !== 'EEXIST') return fn(); // can't lock — just do the work
      // A lock left behind by a killed process would otherwise wedge every
      // future session, so treat an old one as abandoned.
      try {
        if (Date.now() - fs.statSync(lock).mtimeMs > 120000) fs.rmdirSync(lock);
      } catch { /* raced with the owner releasing it */ }
      if (Date.now() > deadlineMs) return fn();
      // Re-check: the holder may have finished the install while we waited.
      const cli = findCli();
      if (cli) return cli;
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 250);
    }
  }
  try {
    return fn();
  } finally {
    try { fs.rmdirSync(lock); } catch { /* already gone */ }
  }
}

function install() {
  const npmFlags = ['--no-audit', '--no-fund', '--loglevel', 'error'];
  log(`installing ${SPEC}…`);
  const global = spawnSync('npm', ['install', '-g', ...npmFlags, SPEC], {
    stdio: ['ignore', 'ignore', 'inherit'],
    shell: process.platform === 'win32',
  });
  if (global.status === 0) {
    const cli = findCli();
    if (cli) return cli;
  }
  // No write access to the global prefix (a locked-down or rootless image).
  // Fall back to a private prefix inside the repo, which is always writable.
  log('global install unavailable — installing into .claude/.graft-runtime');
  fs.mkdirSync(localPrefix, { recursive: true });
  spawnSync('npm', ['install', '--prefix', localPrefix, '--no-save', ...npmFlags, SPEC], {
    stdio: ['ignore', 'ignore', 'inherit'],
    shell: process.platform === 'win32',
  });
  return findCli();
}

/** Path to graft's CLI, installing it first if this machine doesn't have it.
 *  Returns null when graft can't be obtained (offline, npm missing); every
 *  caller treats that as "skip graft", never as a hard failure — a session
 *  without the index must still be a working session. */
function ensure() {
  return findCli() || withInstallLock(() => findCli() || install());
}

module.exports = { ensure, projectDir, SPEC };
