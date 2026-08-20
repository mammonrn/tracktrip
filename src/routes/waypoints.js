import { Router } from 'express';
import { requireAuth } from '../auth/middleware.js';
import { requireTripMembership } from '../auth/tripMembership.js';
import { requireActiveTrip } from '../auth/tripStatus.js';

const NAME_MIN_LENGTH = 1;
const NAME_MAX_LENGTH = 60;
const WAYPOINT_TYPES = ['planned', 'live'];

function serializeWaypoint(row) {
  return {
    id: row.id,
    trip_id: row.trip_id,
    name: row.name,
    lat: row.lat,
    lng: row.lng,
    type: row.type,
    order_index: row.order_index,
    added_by: row.added_by,
    created_at: row.created_at,
  };
}

/**
 * Validates a POST body. Returns { error } with a message, or { value } with
 * the cleaned fields ready to insert.
 */
export function validateWaypointInput(body) {
  const payload = body || {};
  const { name, lat, lng, type } = payload;

  if (typeof name !== 'string') {
    return { error: 'name is required' };
  }
  const trimmedName = name.trim();
  if (trimmedName.length < NAME_MIN_LENGTH || trimmedName.length > NAME_MAX_LENGTH) {
    return {
      error: `name must be between ${NAME_MIN_LENGTH} and ${NAME_MAX_LENGTH} characters`,
    };
  }

  // Reject NaN/Infinity/strings — Number.isFinite is false for all of them.
  if (!Number.isFinite(lat) || lat < -90 || lat > 90) {
    return { error: 'lat must be a number between -90 and 90' };
  }
  if (!Number.isFinite(lng) || lng < -180 || lng > 180) {
    return { error: 'lng must be a number between -180 and 180' };
  }

  if (!WAYPOINT_TYPES.includes(type)) {
    return { error: "type must be either 'planned' or 'live'" };
  }

  const hasOrderIndex = Object.prototype.hasOwnProperty.call(payload, 'order_index');

  if (type === 'planned') {
    if (!hasOrderIndex) {
      return { error: 'order_index is required when type is planned' };
    }
    if (!Number.isInteger(payload.order_index) || payload.order_index < 0) {
      return { error: 'order_index must be a non-negative integer' };
    }
    return {
      value: { name: trimmedName, lat, lng, type, order_index: payload.order_index },
    };
  }

  // type === 'live': ordering is meaningless, so the field must be absent
  // entirely rather than silently ignored.
  if (hasOrderIndex) {
    return { error: 'order_index must not be sent when type is live' };
  }
  return { value: { name: trimmedName, lat, lng, type, order_index: null } };
}

/**
 * Validates a PATCH body: the fields of a waypoint that may be changed after
 * it exists.
 *
 * ## Why this is not the same validator as [validateWaypointInput]
 *
 * A create says what a waypoint *is*; a patch says what about it changed, and
 * the difference is what an absent field means. Absent on a create is an
 * error; absent on a patch is "leave it". So every field here is optional and
 * a body with none of them is refused outright — a PATCH that changes nothing
 * is a request somebody meant to fill in.
 *
 * `lat`/`lng` and `type` are deliberately **not** patchable. Moving a saved
 * waypoint somewhere else is not an edit, it is a different stop; and a
 * planned waypoint that turned into a live one would silently leave the route
 * it was ordered inside. Both are a delete and a create, which the client
 * already does.
 */
export function validateWaypointPatch(body) {
  const payload = body || {};
  const value = {};

  const hasName = Object.prototype.hasOwnProperty.call(payload, 'name');
  const hasOrder = Object.prototype.hasOwnProperty.call(payload, 'order_index');

  if (!hasName && !hasOrder) {
    return { error: 'name or order_index is required' };
  }

  if (hasName) {
    if (typeof payload.name !== 'string') {
      return { error: 'name is required' };
    }
    const trimmedName = payload.name.trim();
    if (trimmedName.length < NAME_MIN_LENGTH || trimmedName.length > NAME_MAX_LENGTH) {
      return {
        error: `name must be between ${NAME_MIN_LENGTH} and ${NAME_MAX_LENGTH} characters`,
      };
    }
    value.name = trimmedName;
  }

  if (hasOrder) {
    if (!Number.isInteger(payload.order_index) || payload.order_index < 0) {
      return { error: 'order_index must be a non-negative integer' };
    }
    value.order_index = payload.order_index;
  }

  return { value };
}

export function createWaypointsRouter({ db, config }) {
  const router = Router();
  router.use('/trips/:id/waypoints', requireAuth(db, config), requireTripMembership(db));

  // Reads stay open on an ended trip; writes don't.
  router.post('/trips/:id/waypoints', requireActiveTrip(), (req, res) => {
    const { error, value } = validateWaypointInput(req.body);
    if (error) {
      return res.status(400).json({ error });
    }

    const result = db
      .prepare(
        `INSERT INTO trip_waypoints (trip_id, name, lat, lng, type, order_index, added_by)
         VALUES (?, ?, ?, ?, ?, ?, ?)`
      )
      .run(
        req.trip.id,
        value.name,
        value.lat,
        value.lng,
        value.type,
        value.order_index,
        req.user.id
      );

    const created = db
      .prepare('SELECT * FROM trip_waypoints WHERE id = ?')
      .get(result.lastInsertRowid);
    res.status(201).json(serializeWaypoint(created));
  });

  router.get('/trips/:id/waypoints', (req, res) => {
    const planned = db
      .prepare(
        `SELECT * FROM trip_waypoints
         WHERE trip_id = ? AND type = 'planned'
         ORDER BY order_index ASC, id ASC`
      )
      .all(req.trip.id);

    const live = db
      .prepare(
        `SELECT * FROM trip_waypoints
         WHERE trip_id = ? AND type = 'live'
         ORDER BY created_at ASC, id ASC`
      )
      .all(req.trip.id);

    res.json({
      planned: planned.map(serializeWaypoint),
      live: live.map(serializeWaypoint),
    });
  });

  /**
   * Changes a waypoint that already exists — in practice, its position in the
   * planned route.
   *
   * ## Why this exists
   *
   * Until now a route could only be appended to. The app held the stops a
   * rider was arranging in local state and wrote them with one POST each, so
   * re-ordering was something that happened on the phone and nowhere else:
   * leaving the trip and coming back showed a list with no stops in it at all,
   * because there was nothing to load them *into*. Ordering has to be
   * writable for the list to be the real route rather than a sketch of it.
   *
   * ## Who may
   *
   * The trip's owner only, and this is stricter than the delete below on
   * purpose. Re-ordering one stop renumbers the ones around it — a member
   * dragging their own stop up two places has to rewrite two stops that are
   * not theirs, and an author-only rule would let the first of those writes
   * succeed and the second come back 403, leaving the route half-renumbered
   * with no way for the rider to tell. The route between the ends is the
   * owner's, the same way `PATCH /trips/:id` is; adding a stop and removing
   * your own are what a member gets.
   *
   * `order_index` is not made unique. A client rewriting a whole route sends
   * one PATCH per moved stop, and any intermediate state would collide with
   * itself under a unique index — the ordering is a sort key, and ties fall
   * back to `id` exactly as the GET says.
   */
  router.patch('/trips/:id/waypoints/:wpId', requireActiveTrip(), (req, res) => {
    const waypointId = Number(req.params.wpId);
    if (!Number.isInteger(waypointId) || waypointId <= 0) {
      return res.status(400).json({ error: 'invalid waypoint id' });
    }

    const { error, value } = validateWaypointPatch(req.body);
    if (error) {
      return res.status(400).json({ error });
    }

    const waypoint = db
      .prepare('SELECT * FROM trip_waypoints WHERE id = ? AND trip_id = ?')
      .get(waypointId, req.trip.id);
    if (!waypoint) {
      return res.status(404).json({ error: 'waypoint not found' });
    }

    if (req.trip.owner_id !== req.user.id) {
      return res
        .status(403)
        .json({ error: 'only the trip owner can change a waypoint' });
    }

    // Ordering means nothing on a live waypoint — those are chronological, and
    // the column is NULL for every one of them. Accepting it would write a
    // number nothing reads and imply an order that does not exist.
    if (value.order_index !== undefined && waypoint.type !== 'planned') {
      return res
        .status(400)
        .json({ error: 'order_index can only be set on a planned waypoint' });
    }

    const fields = [];
    const args = [];
    if (value.name !== undefined) {
      fields.push('name = ?');
      args.push(value.name);
    }
    if (value.order_index !== undefined) {
      fields.push('order_index = ?');
      args.push(value.order_index);
    }
    args.push(waypointId);

    db.prepare(`UPDATE trip_waypoints SET ${fields.join(', ')} WHERE id = ?`).run(...args);

    const updated = db.prepare('SELECT * FROM trip_waypoints WHERE id = ?').get(waypointId);
    res.json(serializeWaypoint(updated));
  });

  router.delete('/trips/:id/waypoints/:wpId', requireActiveTrip(), (req, res) => {
    const waypointId = Number(req.params.wpId);
    if (!Number.isInteger(waypointId) || waypointId <= 0) {
      return res.status(400).json({ error: 'invalid waypoint id' });
    }

    const waypoint = db
      .prepare('SELECT * FROM trip_waypoints WHERE id = ? AND trip_id = ?')
      .get(waypointId, req.trip.id);
    if (!waypoint) {
      return res.status(404).json({ error: 'waypoint not found' });
    }

    const isTripOwner = req.trip.owner_id === req.user.id;
    const isAuthor = waypoint.added_by === req.user.id;
    if (!isTripOwner && !isAuthor) {
      return res
        .status(403)
        .json({ error: 'only the trip owner or the member who added it can delete a waypoint' });
    }

    db.prepare('DELETE FROM trip_waypoints WHERE id = ?').run(waypointId);
    res.status(204).end();
  });

  return router;
}

export default createWaypointsRouter;
