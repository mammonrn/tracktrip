import { syncSuperuserRoles } from './roles.js';

/**
 * Upserts a user by google_sub. On first sign-in, display_name/photo_url are
 * seeded from the Google profile. On every later sign-in those two fields are
 * left untouched, since the user may have edited them via PATCH /me.
 *
 * [superuserEmails] is the configured super-user list, reconciled here as well
 * as at boot. Without it, an address added to the config would only take
 * effect for accounts that already existed when the server started — and the
 * first super user's account may not exist at all until they sign in for the
 * first time, which is exactly the case this has to get right.
 */
export function upsertGoogleUser(
  db,
  { googleSub, email, name, picture },
  now = new Date(),
  superuserEmails = []
) {
  const nowIso = now.toISOString();
  const existing = db.prepare('SELECT * FROM users WHERE google_sub = ?').get(googleSub);

  if (existing) {
    db.prepare('UPDATE users SET email = ?, updated_at = ? WHERE id = ?').run(
      email ?? existing.email,
      nowIso,
      existing.id
    );
    syncSuperuserRoles(db, superuserEmails);
    return db.prepare('SELECT * FROM users WHERE id = ?').get(existing.id);
  }

  const result = db
    .prepare(
      `INSERT INTO users (google_sub, email, display_name, photo_url, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?)`
    )
    .run(googleSub, email ?? null, name ?? null, picture ?? null, nowIso, nowIso);

  syncSuperuserRoles(db, superuserEmails);
  return db.prepare('SELECT * FROM users WHERE id = ?').get(result.lastInsertRowid);
}
