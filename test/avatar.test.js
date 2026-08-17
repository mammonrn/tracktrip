import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import Database from 'better-sqlite3';
import supertest from 'supertest';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { signAccessToken } from '../src/auth/jwt.js';
import { AVATAR_MAX_BYTES, AVATAR_PUBLIC_PREFIX, detectImageType } from '../src/users/avatar.js';

const JWT_SECRET = 'test-secret';

/**
 * Byte-accurate headers followed by filler.
 *
 * Not real images — the endpoint's job is to decide what a file *is* from its
 * first bytes, and a valid image would test the same code path as this does
 * while making the failure cases harder to write.
 */
const pngBytes = (size = 64) =>
  Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    Buffer.alloc(Math.max(0, size - 8), 0x21),
  ]);

const jpegBytes = (size = 64) =>
  Buffer.concat([Buffer.from([0xff, 0xd8, 0xff]), Buffer.alloc(Math.max(0, size - 3), 0x21)]);

function setup(t) {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  runMigrations(db, MIGRATIONS_DIR);

  const riderId = Number(
    db
      .prepare('INSERT INTO users (google_sub, email, display_name) VALUES (?, ?, ?)')
      .run('sub-rider', 'rider@gmail.com', 'Rider').lastInsertRowid
  );

  const uploadsDir = fs.mkdtempSync(path.join(os.tmpdir(), 'tracktrip-uploads-'));
  t.after(() => fs.rmSync(uploadsDir, { recursive: true, force: true }));

  const app = createApp({
    db,
    config: { jwtSecret: JWT_SECRET, googleClientIds: ['test-client-id'], uploadsDir },
    verifyGoogleIdToken: async () => {
      throw new Error('unused in these tests');
    },
  });

  return { db, app, uploadsDir, riderId, riderToken: signAccessToken(riderId, JWT_SECRET) };
}

const postAvatar = (app, token) =>
  supertest(app).post('/me/avatar').set('Authorization', `Bearer ${token}`);

const storedPath = (uploadsDir, photoUrl) =>
  path.join(uploadsDir, 'avatars', photoUrl.slice(AVATAR_PUBLIC_PREFIX.length));

// ─── The happy path ─────────────────────────────────────────────────────────

test('POST /me/avatar stores the image and points the user at it', async (t) => {
  const { app, uploadsDir, riderToken } = setup(t);

  const res = await postAvatar(app, riderToken).attach('avatar', pngBytes(), {
    filename: 'me.png',
    contentType: 'image/png',
  });

  assert.equal(res.status, 200);
  assert.ok(res.body.photo_url.startsWith(AVATAR_PUBLIC_PREFIX), res.body.photo_url);
  assert.ok(res.body.photo_url.endsWith('.png'));
  assert.ok(fs.existsSync(storedPath(uploadsDir, res.body.photo_url)));
});

test('a JPEG is stored with a .jpg extension whatever it was called', async (t) => {
  const { app, riderToken } = setup(t);

  const res = await postAvatar(app, riderToken).attach('avatar', jpegBytes(), {
    filename: 'holiday.PNG',
    contentType: 'image/jpeg',
  });

  assert.equal(res.status, 200);
  assert.ok(res.body.photo_url.endsWith('.jpg'), res.body.photo_url);
});

test('the uploaded filename is discarded, not sanitised', async (t) => {
  const { app, uploadsDir, riderToken } = setup(t);

  const res = await postAvatar(app, riderToken).attach('avatar', pngBytes(), {
    filename: '../../../../etc/cron.d/pwned.png',
    contentType: 'image/png',
  });

  assert.equal(res.status, 200);

  // A UUID with an extension, and nothing that could climb out of the
  // directory it was written to.
  const stored = res.body.photo_url.slice(AVATAR_PUBLIC_PREFIX.length);
  assert.match(stored, /^[0-9a-f-]{36}\.png$/);
  assert.deepEqual(fs.readdirSync(path.join(uploadsDir, 'avatars')), [stored]);
});

test('uploading again replaces the file rather than accumulating', async (t) => {
  const { app, uploadsDir, riderToken } = setup(t);

  const first = await postAvatar(app, riderToken).attach('avatar', pngBytes(), {
    filename: 'a.png',
    contentType: 'image/png',
  });
  const second = await postAvatar(app, riderToken).attach('avatar', jpegBytes(), {
    filename: 'b.jpg',
    contentType: 'image/jpeg',
  });

  assert.equal(second.status, 200);
  assert.notEqual(first.body.photo_url, second.body.photo_url);
  assert.equal(fs.existsSync(storedPath(uploadsDir, first.body.photo_url)), false);
  assert.ok(fs.existsSync(storedPath(uploadsDir, second.body.photo_url)));
});

test('a Google avatar from sign-in is left alone when it is replaced', async (t) => {
  const { app, db, riderId, riderToken } = setup(t);
  db.prepare('UPDATE users SET photo_url = ? WHERE id = ?').run(
    'https://lh3.googleusercontent.com/a/photo.jpg',
    riderId
  );

  const res = await postAvatar(app, riderToken).attach('avatar', pngBytes(), {
    filename: 'me.png',
    contentType: 'image/png',
  });

  // Nothing to assert on disk — the point is that a URL we did not write does
  // not get handed to unlink, and the upload still succeeds.
  assert.equal(res.status, 200);
  assert.ok(res.body.photo_url.startsWith(AVATAR_PUBLIC_PREFIX));
});

// ─── What must be refused ───────────────────────────────────────────────────

test('a file that claims to be a PNG but is not is refused', async (t) => {
  const { app, uploadsDir, riderToken } = setup(t);

  const res = await postAvatar(app, riderToken).attach(
    'avatar',
    Buffer.from('#!/bin/sh\nrm -rf /\n'),
    { filename: 'me.png', contentType: 'image/png' }
  );

  assert.equal(res.status, 415);
  // And nothing reached the disk.
  assert.equal(fs.existsSync(path.join(uploadsDir, 'avatars')), false);
});

test('a content type we do not accept is refused', async (t) => {
  const { app, riderToken } = setup(t);

  const res = await postAvatar(app, riderToken).attach('avatar', pngBytes(), {
    filename: 'me.gif',
    contentType: 'image/gif',
  });

  assert.equal(res.status, 415);
});

test('an image over the size limit is refused', async (t) => {
  const { app, riderToken } = setup(t);

  const res = await postAvatar(app, riderToken).attach('avatar', pngBytes(AVATAR_MAX_BYTES + 1024), {
    filename: 'huge.png',
    contentType: 'image/png',
  });

  assert.equal(res.status, 413);
});

test('a request with no file at all is a 400', async (t) => {
  const { app, riderToken } = setup(t);

  const res = await postAvatar(app, riderToken).field('unrelated', 'value');

  assert.equal(res.status, 400);
});

test('POST /me/avatar needs a signed-in rider', async (t) => {
  const { app } = setup(t);

  const res = await supertest(app)
    .post('/me/avatar')
    .attach('avatar', pngBytes(), { filename: 'me.png', contentType: 'image/png' });

  assert.equal(res.status, 401);
});

// ─── The sniffing itself ────────────────────────────────────────────────────

test('detectImageType reads the bytes and nothing else', () => {
  assert.equal(detectImageType(pngBytes()), 'png');
  assert.equal(detectImageType(jpegBytes()), 'jpeg');
  assert.equal(detectImageType(Buffer.from('GIF89a')), null);
  assert.equal(detectImageType(Buffer.from('<?php system($_GET[0]); ?>')), null);
  assert.equal(detectImageType(Buffer.alloc(0)), null);
  assert.equal(detectImageType('not a buffer'), null);

  // A truncated header is not a match — subarray would happily return the two
  // bytes that are there.
  assert.equal(detectImageType(Buffer.from([0xff, 0xd8])), null);
});
