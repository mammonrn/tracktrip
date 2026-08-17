function cutoffIso(retentionDays, now = new Date()) {
  return new Date(now.getTime() - retentionDays * 24 * 60 * 60 * 1000).toISOString();
}

export function findExpiredTrips(db, retentionDays, now = new Date()) {
  return db
    .prepare(
      `SELECT id, ended_at FROM trips
       WHERE status = 'ended' AND ended_at IS NOT NULL AND ended_at < ?`
    )
    .all(cutoffIso(retentionDays, now));
}

export function countExpiredHistory(db, retentionDays, now = new Date()) {
  const row = db
    .prepare(
      `SELECT COUNT(*) AS count FROM position_history ph
       JOIN trips t ON t.id = ph.trip_id
       WHERE t.status = 'ended' AND t.ended_at IS NOT NULL AND t.ended_at < ?`
    )
    .get(cutoffIso(retentionDays, now));
  return row.count;
}

export function deleteExpiredHistory(db, retentionDays, now = new Date()) {
  const result = db
    .prepare(
      `DELETE FROM position_history
       WHERE trip_id IN (
         SELECT id FROM trips
         WHERE status = 'ended' AND ended_at IS NOT NULL AND ended_at < ?
       )`
    )
    .run(cutoffIso(retentionDays, now));
  return result.changes;
}

export function runCleanup(db, { retentionDays, confirm = false, now = new Date() } = {}) {
  const expiredTrips = findExpiredTrips(db, retentionDays, now);
  const expiredRowCount = countExpiredHistory(db, retentionDays, now);

  if (!confirm) {
    return { dryRun: true, tripCount: expiredTrips.length, deletedRows: expiredRowCount };
  }

  const deletedRows = deleteExpiredHistory(db, retentionDays, now);
  return { dryRun: false, tripCount: expiredTrips.length, deletedRows };
}
