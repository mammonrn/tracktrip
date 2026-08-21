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

// Does `name` resolve as a dependency of something in `fromDir`? Walks the
// node_modules chain by hand rather than using require.resolve, which reports a
// package with a restrictive "exports" map as missing even when it is installed.
function depInstalled(fromDir, name) {
  for (let dir = fromDir;;) {
    if (fs.existsSync(path.join(dir, 'node_modules', name))) return true;
    const parent = path.dirname(dir);
    if (parent === dir) return false;
    dir = parent;
  }
}

// Absolute path to graft's CLI entrypoint as resolved from `base`, or null.
//
// "The entrypoint exists" is not the same as "the install finished". npm unpacks
// a tree incrementally, so a concurrent reader can see package.json and
// dist/cli.js while the nested dependencies are still being written — and a CLI
// launched in that window dies on ERR_MODULE_NOT_FOUND partway through an
// import. Checking that every declared dependency is on disk closes that window,
// and costs a handful of existsSync calls.
function cliFrom(base) {
  try {
    const manifestPath = require.resolve(`${PKG}/package.json`, { paths: [base] });
    const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
    const bin = typeof manifest.bin === 'string'
      ? manifest.bin
      : manifest.bin && (manifest.bin.graft || Object.values(manifest.bin)[0]);
    if (!bin) return null;
    const pkgDir = path.dirname(manifestPath);
    const entry = path.join(pkgDir, bin);
    if (!fs.existsSync(entry)) return null;
    const deps = Object.keys(manifest.dependencies ?? {});
    if (!deps.every((d) => depInstalled(pkgDir, d))) return null; // still unpacking
    return entry;
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

const LOCK = path.join(require('os').tmpdir(), 'graft-ensure.lock');
const LOCK_WAIT_MS = 240000;  // how long a waiter blocks before giving up
const LOCK_STALE_MS = 600000; // when a lock is assumed orphaned by a killed process

function sleep(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

// mkdir is atomic, so it doubles as a mutex. The SessionStart hook and the MCP
// wrapper both call ensure() and Claude Code starts them concurrently; without
// this they would race into two simultaneous `npm install -g` runs on the same
// prefix. The loser waits for the winner rather than installing again.
//
// A waiter waits for the lock to be *released* and does not look at the files
// while it waits. Polling findCli() here instead looks like an optimisation and
// is a bug: it returns the moment npm has written enough of the tree to look
// finished, which is how the MCP server came up pointing at a graft whose
// dependencies were still being unpacked. The only safe moment to trust the
// filesystem is once nobody holds the lock.
function withInstallLock(fn) {
  const deadline = Date.now() + LOCK_WAIT_MS;
  for (;;) {
    try {
      fs.mkdirSync(LOCK);
      break;
    } catch (e) {
      if (e.code !== 'EEXIST') return fn(); // can't lock — just do the work
      // A lock left behind by a killed process would otherwise wedge every
      // future session, so treat an old one as abandoned.
      try {
        if (Date.now() - fs.statSync(LOCK).mtimeMs > LOCK_STALE_MS) fs.rmdirSync(LOCK);
      } catch { /* raced with the owner releasing it */ }
      if (Date.now() > deadline) {
        log('timed out waiting for another install to finish — proceeding anyway');
        return fn();
      }
      sleep(250);
    }
  }
  try {
    return fn();
  } finally {
    try { fs.rmdirSync(LOCK); } catch { /* already gone */ }
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
  // Skip the fast path while an install is in flight: findCli() screens out a
  // half-written tree, but going through the lock means waiting for the install
  // that is already running rather than racing it.
  if (!fs.existsSync(LOCK)) {
    const cli = findCli();
    if (cli) return cli;
  }
  return withInstallLock(() => findCli() || install());
}

module.exports = { ensure, projectDir, SPEC };
