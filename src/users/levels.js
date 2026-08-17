/**
 * Rider levels, by lifetime kilometres. Ascending, and the first entry must
 * start at 0 so every rider has a level from their very first ride.
 *
 * These are balance numbers, expected to be retuned once there is real
 * mileage data to look at — that is the only reason this is a plain table
 * rather than a formula.
 */
export const LEVELS = [
  { name: 'Novice', min_km: 0 },
  { name: 'Rookie Rider', min_km: 500 },
  { name: 'Wanderer', min_km: 1500 },
  { name: 'Voyager', min_km: 3500 },
  { name: 'Trailblazer', min_km: 6000 },
  { name: 'Road Warrior', min_km: 9000 },
  { name: 'Road King', min_km: 12000 },
  { name: 'Legendary', min_km: 20000 },
];

// Distances are reported to the nearest 10 m, which is also the shortest
// segment that counts towards a total (see JITTER.MIN_DISTANCE_KM).
const KM_DECIMALS = 2;

/**
 * Rounds a distance for display. Totals are *stored* at full precision so
 * that thousands of small additions don't drift; only what goes out over the
 * API is rounded, and every response rounds the same way.
 */
export function roundKm(km) {
  return Number.isFinite(km) ? Number(km.toFixed(KM_DECIMALS)) : 0;
}

const round = roundKm;

/**
 * Where a rider stands: the level they've reached, the one after it, and how
 * much further they have to ride to get there.
 *
 * `next_level` and `km_to_next` are null at the top of the table — there is
 * nothing left to work towards, which the profile screen renders differently
 * from "0 km to go".
 */
export function progressFor(totalKm) {
  const km = Number.isFinite(totalKm) && totalKm > 0 ? totalKm : 0;

  let index = 0;
  for (let i = 0; i < LEVELS.length; i += 1) {
    if (km >= LEVELS[i].min_km) {
      index = i;
    }
  }

  const level = LEVELS[index];
  const nextLevel = LEVELS[index + 1] ?? null;

  return {
    total_km: round(km),
    level: { ...level },
    next_level: nextLevel ? { ...nextLevel } : null,
    // Rounded after subtracting, so it never disagrees with the difference
    // between the two rounded numbers shown either side of it.
    km_to_next: nextLevel ? round(nextLevel.min_km - km) : null,
  };
}
