import { roundKm } from '../users/levels.js';
import { ROLE_USER } from './roles.js';

export function serializeUser(user) {
  return {
    id: user.id,
    email: user.email,
    display_name: user.display_name,
    photo_url: user.photo_url,
    // Optional profile details. Always present in the response, null until
    // the rider fills them in — a client can then render every field without
    // having to tell "not set" apart from "this build doesn't send it".
    first_name: user.first_name ?? null,
    last_name: user.last_name ?? null,
    username: user.username ?? null,
    phone: user.phone ?? null,
    birth_date: user.birth_date ?? null,
    total_km: roundKm(user.total_km),
    // The account's own role, so a client knows whether to offer the things
    // only a super user can do. Never a claim of trust on its own: every
    // route checks the database, and a client that lied about this to itself
    // would get a 403 from the first request it tried.
    role: user.role ?? ROLE_USER,
  };
}
