import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { openDb } from './index.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
export const MIGRATIONS_DIR = path.join(__dirname, 'migrations');

function ensureMigrationsTable(db) {
  db.exec(`
    CREATE TABLE IF NOT EXISTS schema_migrations (
      filename    TEXT PRIMARY KEY,
      applied_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
    )
  `);
}

function pendingMigrations(db, migrationsDir) {
  const applied = new Set(
    db.prepare('SELECT filename FROM schema_migrations').all().map((row) => row.filename)
  );
  return fs
    .readdirSync(migrationsDir)
    .filter((file) => file.endsWith('.sql'))
    .sort()
    .filter((file) => !applied.has(file));
}

export function runMigrations(db, migrationsDir = MIGRATIONS_DIR) {
  ensureMigrationsTable(db);
  const pending = pendingMigrations(db, migrationsDir);
  const applyMigration = db.transaction((filename) => {
    const sql = fs.readFileSync(path.join(migrationsDir, filename), 'utf8');
    db.exec(sql);
    db.prepare('INSERT INTO schema_migrations (filename) VALUES (?)').run(filename);
  });

  for (const filename of pending) {
    applyMigration(filename);
  }

  return pending;
}

function isMain() {
  return process.argv[1] === fileURLToPath(import.meta.url);
}

if (isMain()) {
  const db = openDb();
  const applied = runMigrations(db);
  if (applied.length === 0) {
    console.log('No pending migrations. Database is up to date.');
  } else {
    console.log(`Applied ${applied.length} migration(s):`);
    for (const filename of applied) {
      console.log(`  - ${filename}`);
    }
  }
  db.close();
}
