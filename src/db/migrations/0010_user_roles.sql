-- An account-level role, so somebody can be given the whole system.
--
-- ## One column, not a permissions system
--
-- There are two kinds of account and one privilege between them: an ordinary
-- rider, who sees the trips they belong to, and a super user, who sees and
-- manages all of them. A roles/permissions/role_permissions triple would be
-- three tables and four joins to express one boolean, and every read of it
-- would be slower and harder to reason about than the thing it models.
--
-- A column extends cheaply in the direction this is likely to go. Adding
-- 'moderator' is one CHECK constraint and one branch in the middleware.
-- Turning a column into a join table later is a mechanical migration anyone
-- can write; going the other way, once the joins are load-bearing, never gets
-- done.
--
-- ## This is NOT trip_members.role
--
-- `trip_members.role` is 'owner' or 'member' and says what somebody is *on one
-- trip*. This says what they are *on this installation*. They are deliberately
-- separate: a super user is not the owner of everybody's trips, and promoting
-- one must not rewrite anybody's membership. The middleware keeps the same
-- separation — a super user passes the ownership check without a trip_members
-- row ever being invented for them.
--
-- ## Who is one
--
-- The column is the authority for *authorisation*; the SUPERUSER_EMAILS config
-- is the authority for *who*. `syncSuperuserRoles` reconciles the two at every
-- boot and at every sign-in, so the answer to "who can see everything?" is one
-- environment variable rather than a row somebody has to remember setting. See
-- src/auth/roles.js.
--
-- DEFAULT 'user' and NOT NULL together mean every existing account, and every
-- account made from now on, starts ordinary. Nobody is promoted by this
-- migration — that is the config's job, on the next boot.

ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'user'
  CHECK (role IN ('user', 'superuser'));

-- Listing every super user is something an admin screen would do on every
-- load, and it is a full scan of the users table without this. Small now,
-- cheap forever.
CREATE INDEX idx_users_role ON users (role);
