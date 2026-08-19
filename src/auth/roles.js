/**
 * Account-level roles.
 *
 * Two of them, and one privilege between them: an ordinary rider sees the
 * trips they belong to, and a super user sees and manages all of them. See
 * migration 0010 for why this is a column on `users` rather than a
 * permissions system, and for why it is not the same thing as
 * `trip_members.role`.
 */
export const ROLE_USER = 'user';
export const ROLE_SUPERUSER = 'superuser';

/** Whether a users row carries the super-user role. */
export function isSuperuser(user) {
  return user?.role === ROLE_SUPERUSER;
}

/**
 * Emails, compared the way addresses have to be compared.
 *
 * Lowercased, because Google returns whatever capitalisation the account was
 * created with and `Poom@…` and `poom@…` are one person. Trimmed, because a
 * comma-separated environment variable is written by a human.
 */
function normalize(email) {
  return typeof email === 'string' ? email.trim().toLowerCase() : '';
}

/**
 * Brings `users.role` in line with the configured list of super-user emails.
 *
 * **The config is the authority for who; the column is the authority for
 * what.** Every account whose email is listed is promoted, and every account
 * holding the role whose email is *not* listed is demoted. That second half is
 * the important one: it means revoking somebody is deleting an address from
 * one environment variable and restarting, with no database surgery and no
 * chance of a forgotten row keeping a privilege alive.
 *
 * The cost of that choice is that there can be no "promote this person"
 * button that outlives a restart. That is the right trade today — there is no
 * such button, and one environment variable is a far easier thing to audit
 * than a table — but it is the thing to revisit first if an admin screen ever
 * wants to hand the role out. At that point this becomes seed-only: promote
 * the listed accounts if nobody holds the role yet, and leave the column alone
 * afterwards.
 *
 * Runs at boot and again at each sign-in, so an address added to the config
 * takes effect on the next deploy, and an account that had never signed in
 * when the deploy happened is promoted the moment it does.
 *
 * Returns what changed, so a boot can say so in its log rather than promoting
 * people silently.
 */
export function syncSuperuserRoles(db, emails = []) {
  const wanted = new Set(emails.map(normalize).filter(Boolean));

  const promoted = [];
  const demoted = [];

  const rows = db.prepare('SELECT id, email, role FROM users').all();
  const setRole = db.prepare('UPDATE users SET role = ? WHERE id = ?');

  const apply = db.transaction(() => {
    for (const row of rows) {
      // An account with no email address can never match a list of email
      // addresses. It also cannot be demoted by one, or a deployment that
      // lost its config would strip a role nothing could restore.
      const email = normalize(row.email);
      const shouldBeSuperuser = email !== '' && wanted.has(email);

      if (shouldBeSuperuser && row.role !== ROLE_SUPERUSER) {
        setRole.run(ROLE_SUPERUSER, row.id);
        promoted.push(row.email);
      } else if (!shouldBeSuperuser && row.role === ROLE_SUPERUSER && email !== '') {
        setRole.run(ROLE_USER, row.id);
        demoted.push(row.email);
      }
    }
  });
  apply();

  return { promoted, demoted };
}

export default { ROLE_USER, ROLE_SUPERUSER, isSuperuser, syncSuperuserRoles };
