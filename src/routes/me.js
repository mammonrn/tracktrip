import { Router } from 'express';
import multer from 'multer';
import { requireAuth } from '../auth/middleware.js';
import { serializeUser } from '../auth/serializeUser.js';
import { progressFor } from '../users/levels.js';
import { validateProfilePatch, usernameTaken } from '../users/profile.js';
import {
  ALLOWED_AVATAR_MIME_TYPES,
  AVATAR_MAX_BYTES,
  detectImageType,
  removeAvatarFile,
  saveAvatar,
} from '../users/avatar.js';

/**
 * Uploads are held in memory rather than streamed to disk.
 *
 * Nothing is written until the bytes have been checked, and writing first
 * would mean a rejected upload had already touched the filesystem. At 5 MB a
 * request, with one file per request, the memory is bounded and small.
 */
function avatarUpload() {
  return multer({
    storage: multer.memoryStorage(),
    limits: { fileSize: AVATAR_MAX_BYTES, files: 1 },
    fileFilter: (req, file, cb) => {
      // A first pass on what the client claims. Cheap, and it rejects the
      // honest mistakes before reading a 5 MB body — but it is not the check
      // that matters, since the header is the client's to write.
      if (!ALLOWED_AVATAR_MIME_TYPES.includes(file.mimetype)) {
        cb(new UnsupportedAvatarType());
        return;
      }
      cb(null, true);
    },
  }).single('avatar');
}

class UnsupportedAvatarType extends Error {
  constructor() {
    super('avatar must be a JPEG or PNG image');
    this.name = 'UnsupportedAvatarType';
  }
}

export function createMeRouter({ db, config }) {
  const router = Router();
  const upload = avatarUpload();
  router.use(requireAuth(db, config));

  router.get('/me', (req, res) => {
    res.json(serializeUser(req.user));
  });

  // The challenge widget on the profile screen. Kept apart from GET /me
  // because it is derived from total_km rather than stored, and the level
  // table it reads is expected to be retuned.
  router.get('/me/level', (req, res) => {
    res.json(progressFor(req.user.total_km));
  });

  /**
   * A partial update: only the fields present in the body are touched, and
   * sending an optional one as null clears it.
   *
   * An empty body is a no-op rather than an error. The profile screen sends
   * what changed, and "nothing changed" is a save the rider is allowed to
   * make.
   */
  router.patch('/me', (req, res) => {
    const { error, value: updates } = validateProfilePatch(req.body);
    if (error) {
      return res.status(400).json({ error });
    }

    if (Object.prototype.hasOwnProperty.call(updates, 'username')) {
      // Checked up front so the rider gets "that username is taken" instead
      // of a constraint violation. The unique index is still the authority —
      // this only decides which error they see.
      if (usernameTaken(db, updates.username, req.user.id)) {
        return res.status(409).json({ error: 'that username is already taken' });
      }
    }

    const fields = Object.keys(updates);
    if (fields.length > 0) {
      const assignments = fields.map((field) => `${field} = ?`).join(', ');
      db.prepare(`UPDATE users SET ${assignments}, updated_at = ? WHERE id = ?`).run(
        ...fields.map((field) => updates[field]),
        new Date().toISOString(),
        req.user.id
      );
    }

    const updated = db.prepare('SELECT * FROM users WHERE id = ?').get(req.user.id);
    res.json(serializeUser(updated));
  });

  /**
   * Replaces the rider's avatar with an uploaded image.
   *
   * Multipart rather than a JSON data URL: a 5 MB base64 body is 6.7 MB on the
   * wire and has to be held as a string before it can be decoded, and
   * express.json() would have to be opened up to allow it.
   */
  router.post('/me/avatar', (req, res) => {
    upload(req, res, (uploadError) => {
      if (uploadError instanceof UnsupportedAvatarType) {
        return res.status(415).json({ error: uploadError.message });
      }
      if (uploadError?.code === 'LIMIT_FILE_SIZE') {
        return res
          .status(413)
          .json({ error: `avatar must be at most ${AVATAR_MAX_BYTES / (1024 * 1024)} MB` });
      }
      if (uploadError) {
        return res.status(400).json({ error: 'could not read the uploaded file' });
      }
      if (!req.file) {
        return res.status(400).json({ error: 'an avatar file is required' });
      }

      // The claimed content type got the request this far; the bytes decide
      // whether it goes any further.
      const imageType = detectImageType(req.file.buffer);
      if (!imageType) {
        return res.status(415).json({ error: 'avatar must be a JPEG or PNG image' });
      }

      const previous = req.user.photo_url;
      const photoUrl = saveAvatar(config.uploadsDir, req.file.buffer, imageType);

      db.prepare('UPDATE users SET photo_url = ?, updated_at = ? WHERE id = ?').run(
        photoUrl,
        new Date().toISOString(),
        req.user.id
      );

      // Only after the row points at the new file: a delete that ran first
      // would leave the rider with no avatar at all if the write failed.
      removeAvatarFile(config.uploadsDir, previous);

      const updated = db.prepare('SELECT * FROM users WHERE id = ?').get(req.user.id);
      res.json(serializeUser(updated));
    });
  });

  return router;
}

export default createMeRouter;
