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
