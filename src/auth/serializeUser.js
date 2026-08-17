import { roundKm } from '../users/levels.js';

export function serializeUser(user) {
  return {
    id: user.id,
    email: user.email,
    display_name: user.display_name,
    photo_url: user.photo_url,
    total_km: roundKm(user.total_km),
  };
}
