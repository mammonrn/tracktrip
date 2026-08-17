-- Optional profile details riders can fill in themselves.
--
-- Everything here is nullable and stays nullable: a rider signs in with Google
-- and is immediately useful to the app, so none of this can be required. The
-- columns exist so the profile screen has somewhere to write.

ALTER TABLE users ADD COLUMN phone      TEXT;
ALTER TABLE users ADD COLUMN username   TEXT;
ALTER TABLE users ADD COLUMN birth_date TEXT;
ALTER TABLE users ADD COLUMN first_name TEXT;
ALTER TABLE users ADD COLUMN last_name  TEXT;

-- Usernames are unique, case-insensitively: "Poom" and "poom" being two
-- different riders would be a way to impersonate someone, not a feature.
--
-- Indexed on lower(username) rather than the column, so the stored value keeps
-- whatever capitalisation the rider chose. SQLite ignores NULLs in a unique
-- index, so every rider without a username still coexists.
CREATE UNIQUE INDEX idx_users_username_lower ON users (lower(username));
