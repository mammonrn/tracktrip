/**
 * Reports riders who are on more than one active trip.
 *
 * Migration 0013 enforces one active trip per rider from the moment it is
 * applied, but deliberately does not evict anybody who was already on two —
 * see the migration for why a trigger rather than a unique index, and why
 * picking a trip to close is not a decision for a migration that runs
 * unattended at boot.
 *
 * So this is the other half: run it against a real database to find out
 * whether anybody is in that state before or after deploying. It only reads.
 *
 *   node scripts/check-one-active-trip.js
 *   DB_PATH=/root/tracktrip/data/trip-tracker.db node scripts/check-one-active-trip.js
 *
 * Exits 0 when nobody holds two, 1 when somebody does — so it can gate a
 * deploy step if that is ever wanted.
 */
import { openDb } from '../src/db/index.js';
import { config } from '../src/config.js';

function offenders(db) {
  return db
    .prepare(
      `SELECT users.id            AS user_id,
              users.email         AS email,
              users.display_name  AS display_name,
              COUNT(*)            AS active_trips,
              GROUP_CONCAT(trips.id || ': ' || COALESCE(trips.name, '(unnamed)'), ' | ') AS trips
         FROM trip_members
         JOIN trips ON trips.id = trip_members.trip_id
         JOIN users ON users.id = trip_members.user_id
        WHERE trips.status = 'active'
        GROUP BY users.id
       HAVING COUNT(*) > 1
        ORDER BY COUNT(*) DESC, users.id`
    )
    .all();
}

const db = openDb();
const rows = offenders(db);

if (rows.length === 0) {
  console.log(`No rider is on more than one active trip. (${config.dbPath})`);
  db.close();
  process.exit(0);
}

console.log(`${rows.length} rider(s) on more than one active trip in ${config.dbPath}:\n`);
for (const row of rows) {
  const who = row.display_name || row.email || `user ${row.user_id}`;
  console.log(`  ${who} (id ${row.user_id}) — ${row.active_trips} active`);
  for (const trip of String(row.trips).split(' | ')) {
    console.log(`      ${trip}`);
  }
}
console.log(
  '\nThese keep every trip they have; the rule only refuses a further one.' +
    '\nEnding the trips they are no longer riding is a decision for whoever owns the data:' +
    "\n  POST /trips/:id/end as the trip's owner, which also clears its sharing sessions."
);

db.close();
process.exit(1);
