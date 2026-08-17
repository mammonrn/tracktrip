import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';

/**
 * Storing rider avatars on disk.
 *
 * The rule this module exists to enforce: nothing the client says about the
 * upload is trusted. Not the filename, not the content type. A browser or a
 * phone can put anything in either, and both are attacker-controlled on a
 * public endpoint.
 */

/** 5 MB. A phone camera JPEG is well under this; a 12 MP PNG is not. */
export const AVATAR_MAX_BYTES = 5 * 1024 * 1024;

/** What the client is allowed to *claim*. The bytes are checked separately. */
export const ALLOWED_AVATAR_MIME_TYPES = ['image/jpeg', 'image/png'];

/** Where the app publishes avatars. nginx serves this prefix from disk. */
export const AVATAR_PUBLIC_PREFIX = '/uploads/avatars/';

const JPEG_MAGIC = Buffer.from([0xff, 0xd8, 0xff]);
const PNG_MAGIC = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

/**
 * The image type the bytes actually are, or null if they aren't an image we
 * accept.
 *
 * This is the check that matters. `Content-Type: image/png` on a file whose
 * first bytes are `<?php` or `#!/bin/sh` is the whole attack, and the only
 * thing that catches it is reading the file.
 */
export function detectImageType(buffer) {
  if (!Buffer.isBuffer(buffer)) {
    return null;
  }
  if (buffer.subarray(0, JPEG_MAGIC.length).equals(JPEG_MAGIC)) {
    return 'jpeg';
  }
  if (buffer.subarray(0, PNG_MAGIC.length).equals(PNG_MAGIC)) {
    return 'png';
  }
  return null;
}

/**
 * A fresh random filename.
 *
 * The uploaded name is discarded rather than sanitised. Sanitising invites an
 * argument about which of `../`, `..\`, a NUL byte or a unicode lookalike got
 * missed; a UUID has no such argument to lose.
 */
export function avatarFilename(imageType) {
  return `${randomUUID()}.${imageType === 'png' ? 'png' : 'jpg'}`;
}

/** The directory avatars live in, created if it isn't there yet. */
export function ensureAvatarDir(uploadsDir) {
  const dir = path.join(uploadsDir, 'avatars');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

/**
 * Writes an avatar and returns the path to store in `users.photo_url`.
 *
 * Relative, not absolute: the API's own hostname is not this module's to know,
 * and a stored absolute URL would be wrong the day the domain changes. The app
 * resolves it against the base URL it already has.
 */
export function saveAvatar(uploadsDir, buffer, imageType) {
  const dir = ensureAvatarDir(uploadsDir);
  const filename = avatarFilename(imageType);
  fs.writeFileSync(path.join(dir, filename), buffer, { mode: 0o644 });
  return `${AVATAR_PUBLIC_PREFIX}${filename}`;
}

/**
 * Deletes a previously uploaded avatar, if that is what the stored value is.
 *
 * Silent about everything it declines to do. `photo_url` may hold a Google
 * URL from sign-in, or a path from a build that stored them differently, and
 * neither is ours to delete — nor is anything whose basename doesn't match
 * what we write, which is the check that keeps a crafted value in the database
 * from turning into a delete outside the avatars directory.
 */
export function removeAvatarFile(uploadsDir, photoUrl) {
  if (typeof photoUrl !== 'string' || !photoUrl.startsWith(AVATAR_PUBLIC_PREFIX)) {
    return false;
  }

  const filename = photoUrl.slice(AVATAR_PUBLIC_PREFIX.length);
  if (filename === '' || filename !== path.basename(filename)) {
    return false;
  }

  try {
    fs.unlinkSync(path.join(uploadsDir, 'avatars', filename));
    return true;
  } catch {
    // Already gone, or never written. Either way the row is about to point
    // somewhere else, and failing the upload over a stale file would be worse.
    return false;
  }
}
