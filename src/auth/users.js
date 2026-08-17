/**
 * Upserts a user by google_sub. On first sign-in, display_name/photo_url are
 * seeded from the Google profile. On every later sign-in those two fields are
 * left untouched, since the user may have edited them via PATCH /me.
 */
export function upsertGoogleUser(db, { googleSub, email, name, picture }, now = new Date()) {
  const nowIso = now.toISOString();
  const existing = db.prepare('SELECT * FROM users WHERE google_sub = ?').get(googleSub);

  if (existing) {
    db.prepare('UPDATE users SET email = ?, updated_at = ? WHERE id = ?').run(
      email ?? existing.email,
      nowIso,
      existing.id
    );
    return db.prepare('SELECT * FROM users WHERE id = ?').get(existing.id);
  }

  const result = db
    .prepare(
      `INSERT INTO users (google_sub, email, display_name, photo_url, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?)`
    )
    .run(googleSub, email ?? null, name ?? null, picture ?? null, nowIso, nowIso);

  return db.prepare('SELECT * FROM users WHERE id = ?').get(result.lastInsertRowid);
}
