/**
 * Shapes a trips row for API responses. `extra` carries fields that aren't on
 * the row itself but that the caller has already resolved — typically the
 * requesting user's `role` in the trip.
 */
export function serializeTrip(trip, extra = {}) {
  return {
    id: trip.id,
    name: trip.name,
    owner_id: trip.owner_id,
    status: trip.status,
    created_at: trip.created_at,
    ended_at: trip.ended_at,
    ...extra,
  };
}

/**
 * Shapes a trip_invites row for API responses. `extra` is used the same way
 * as in serializeTrip — e.g. the trip's name when listing an invitee's
 * pending invites.
 */
export function serializeInvite(invite, extra = {}) {
  return {
    id: invite.id,
    trip_id: invite.trip_id,
    email: invite.email,
    invited_by: invite.invited_by,
    status: invite.status,
    created_at: invite.created_at,
    accepted_at: invite.accepted_at,
    accepted_by: invite.accepted_by,
    ...extra,
  };
}
