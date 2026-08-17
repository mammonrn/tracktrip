import { openDb } from '../src/db/index.js';
import { runCleanup } from '../src/db/cleanup.js';
import { config } from '../src/config.js';

const confirm = process.argv.includes('--confirm');

const db = openDb();
const result = runCleanup(db, { retentionDays: config.historyRetentionDays, confirm });
db.close();

if (result.dryRun) {
  console.log(
    `[dry-run] Would delete ${result.deletedRows} position_history row(s) from ${result.tripCount} ended trip(s) older than ${config.historyRetentionDays} day(s).`
  );
  console.log('Re-run with --confirm to actually delete.');
} else {
  console.log(
    `Deleted ${result.deletedRows} position_history row(s) from ${result.tripCount} ended trip(s) older than ${config.historyRetentionDays} day(s).`
  );
}
