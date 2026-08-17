import { roundKm } from '../users/levels.js';

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
  };
}
