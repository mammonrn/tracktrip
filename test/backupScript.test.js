import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import Database from 'better-sqlite3';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SCRIPT = path.join(REPO_ROOT, 'scripts', 'backup-to-drive.sh');

/**
 * scripts/backup-to-drive.sh, with rclone replaced by a stub that records what
 * it was asked to do.
 *
 * ## Why a shell script has a test at all
 *
 * Because the thing most likely to be wrong about it is invisible until the
 * night somebody needs a restore. Every other failure here is loud — a missing
 * tool, an unreadable database — but "uploaded, to the wrong place" and
 * "uploaded, then deleted the only copy" both look exactly like success in the
 * log and in the exit code.
 *
 * The destination assertion below is not hypothetical. The remote is
 * configured with `root_folder_id` pinned to the backup folder, so `gdrive:`
 * on its own already *is* the destination; an earlier version appended
 * `tracktrip-backups/` and would have filed every archive in a folder one
 * level inside it. Nothing in this repository can see that rclone config, so
 * what is pinned here is the shape of the argument the script passes.
 *
 * ## What is faked, and what is not
 *
 * Only rclone. The database is real and migrated, `.backup` and
 * `integrity_check` are the real sqlite3, the code snapshot is a real
 * `git archive`, and the zip is a real zip that the test unpacks again.
 */

/** Everything the script shells out to. Missing any of them skips, never fails. */
const TOOLS = ['bash', 'sqlite3', 'zip', 'git'];
const missingTool = TOOLS.find(
  (tool) => spawnSync('sh', ['-c', `command -v ${tool}`]).status !== 0
);

/**
 * A throwaway repo with a real database, an avatar, and a stub rclone.
 *
 * `callsFile` is where the stub writes its own argv, one run per line, which
 * is what the destination assertions read.
 */
function stage(t, { rcloneExit = 0 } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tracktrip-backup-test-'));
  // Removed however the test ends, including a failing assertion — a test
  // about not leaving files behind should not leave files behind.
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));

  // A repo with one commit, so `git archive HEAD` has something to archive.
  fs.writeFileSync(path.join(dir, 'README.md'), 'test fixture\n');
  const git = (...args) => execFileSync('git', ['-C', dir, ...args], { stdio: 'pipe' });
  git('init', '-q');
  git('config', 'user.email', 'test@example.com');
  git('config', 'user.name', 'Test');
  git('add', '.');
  git('commit', '-qm', 'fixture');

  // A real database, in WAL mode and deliberately not checkpointed: the row
  // below is still in the -wal file, which is the thing `cp` would lose and
  // `.backup` must not.
  fs.mkdirSync(path.join(dir, 'data'));
  const dbPath = path.join(dir, 'data', 'trip-tracker.db');
  const db = new Database(dbPath);
  db.pragma('journal_mode = WAL');
  runMigrations(db, MIGRATIONS_DIR);
  db.prepare('INSERT INTO users (google_sub, email, display_name) VALUES (?, ?, ?)').run(
    'sub-fixture',
    'rider@example.com',
    'Rider'
  );

  const uploads = path.join(dir, 'uploads', 'avatars');
  fs.mkdirSync(uploads, { recursive: true });
  fs.writeFileSync(path.join(uploads, 'a1.jpg'), 'not really a jpeg');

  const callsFile = path.join(dir, 'rclone-calls.txt');
  const binDir = path.join(dir, 'bin');
  fs.mkdirSync(binDir);
  fs.writeFileSync(
    path.join(binDir, 'rclone'),
    `#!/usr/bin/env bash\nprintf '%s\\n' "$*" >>${JSON.stringify(callsFile)}\nexit ${rcloneExit}\n`
  );
  fs.chmodSync(path.join(binDir, 'rclone'), 0o755);

  return { dir, dbPath, uploads: path.join(dir, 'uploads'), callsFile, binDir };
}

function run(fixture, env = {}) {
  const logFile = path.join(fixture.dir, 'backup.log');
  const result = spawnSync('bash', [SCRIPT], {
    env: {
      ...process.env,
      PATH: `${fixture.binDir}:${process.env.PATH}`,
      REPO_DIR: fixture.dir,
      DB_PATH: fixture.dbPath,
      UPLOADS_DIR: fixture.uploads,
      LOG_FILE: logFile,
      ...env,
    },
    encoding: 'utf8',
  });
  const log = fs.existsSync(logFile) ? fs.readFileSync(logFile, 'utf8') : '';
  const calls = fs.existsSync(fixture.callsFile)
    ? fs.readFileSync(fixture.callsFile, 'utf8').trim().split('\n').filter(Boolean)
    : [];
  return { ...result, log, calls };
}

/**
 * The archives a run left behind in /tmp, so a test can assert either way.
 *
 * Matched on the script's own naming — `tracktrip-backup-<8 digits>-<6>` — so
 * the fixture directories this file creates, which are named
 * `tracktrip-backup-test-*`, are never mistaken for one.
 */
const LEFTOVER = /^tracktrip-backup-\d{8}-\d{6}(\.zip)?$/;
const leftovers = () => fs.readdirSync(os.tmpdir()).filter((name) => LEFTOVER.test(name));

function cleanLeftovers() {
  for (const name of leftovers()) {
    fs.rmSync(path.join(os.tmpdir(), name), { recursive: true, force: true });
  }
}

test('the archive goes to the remote exactly as configured, with no path glued on', (t) => {
  if (missingTool) return t.skip(`${missingTool} is not installed`);

  cleanLeftovers();
  const fixture = stage(t);
  const { status, calls, log } = run(fixture);

  assert.equal(status, 0, `script failed:\n${log}`);
  assert.equal(calls.length, 1, 'rclone should be called exactly once');

  // `copy <archive> gdrive:` — and specifically NOT `gdrive:something/`. The
  // remote's root_folder_id already points at the backup folder.
  const [verb, archive, destination, ...rest] = calls[0].split(' ');
  assert.equal(verb, 'copy');
  assert.equal(destination, 'gdrive:');
  assert.deepEqual(rest, [], 'rclone should be given two arguments and no more');
  assert.match(path.basename(archive), /^tracktrip-backup-\d{8}-\d{6}\.zip$/);

  assert.match(log, /upload {5}ok {2}-> gdrive: \(as tracktrip-backup-/);
});

test('an overridden remote is passed through untouched', (t) => {
  if (missingTool) return t.skip(`${missingTool} is not installed`);

  // The default is bare `gdrive:` because of how that one remote is pinned,
  // not because the script assumes every destination is a bare remote.
  cleanLeftovers();
  const fixture = stage(t);
  const { status, calls } = run(fixture, { RCLONE_REMOTE: 'other:some/folder' });

  assert.equal(status, 0);
  assert.equal(calls[0].split(' ')[2], 'other:some/folder');
});

test('a successful upload cleans /tmp up behind it', (t) => {
  if (missingTool) return t.skip(`${missingTool} is not installed`);

  cleanLeftovers();
  const fixture = stage(t);
  const { status, log } = run(fixture);

  assert.equal(status, 0);
  assert.match(log, /cleanup {4}ok/);
  assert.deepEqual(leftovers(), [], 'nothing should be left in /tmp after a good run');
});

test('a failed upload keeps the only copy there is', (t) => {
  if (missingTool) return t.skip(`${missingTool} is not installed`);

  cleanLeftovers();
  const fixture = stage(t, { rcloneExit: 7 });
  const { status, log } = run(fixture);

  assert.equal(status, 7, "the script should exit with rclone's own status");
  assert.match(log, /ERROR: rclone exited 7/);
  assert.match(log, /KEPT/);

  // The point of the whole test file. Deleting here would turn "the backup did
  // not reach Drive" into "the backup does not exist", silently, every night.
  const kept = leftovers();
  assert.ok(
    kept.some((name) => name.endsWith('.zip')),
    `the archive should still be in /tmp, found: ${JSON.stringify(kept)}`
  );
  cleanLeftovers();
});

test('the database in the archive opens, with the rows that were still in the -wal', (t) => {
  if (missingTool) return t.skip(`${missingTool} is not installed`);

  cleanLeftovers();
  // rclone "fails" so the archive stays put and the test can open it.
  const fixture = stage(t, { rcloneExit: 1 });
  run(fixture);

  const zip = leftovers().find((name) => name.endsWith('.zip'));
  assert.ok(zip, 'expected an archive to inspect');

  const out = path.join(fixture.dir, 'restored');
  fs.mkdirSync(out);
  execFileSync('unzip', ['-q', path.join(os.tmpdir(), zip), '-d', out], { stdio: 'pipe' });
  const inner = path.join(out, fs.readdirSync(out)[0]);

  const restored = new Database(path.join(inner, 'trip-tracker.db'), { readonly: true });
  assert.equal(restored.pragma('integrity_check', { simple: true }), 'ok');
  // The row was written and never checkpointed, so a `cp` of the main file
  // would have come back with zero here.
  assert.equal(restored.prepare('SELECT COUNT(*) AS n FROM users').get().n, 1);
  restored.close();

  assert.ok(fs.existsSync(path.join(inner, 'code.tar')));
  assert.ok(fs.existsSync(path.join(inner, 'code-commit.txt')));
  assert.ok(fs.existsSync(path.join(inner, 'uploads', 'avatars', 'a1.jpg')));

  cleanLeftovers();
});
