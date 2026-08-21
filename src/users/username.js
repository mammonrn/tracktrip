/**
 * What a username may contain, and what counts as the same one.
 *
 * Split out of profile.js because it stopped being a regex. Supporting Thai
 * turned one line into two questions that have to agree with each other and
 * with a unique index, and the second of them — "are these two names the same
 * name?" — is the whole reason the feature is risky.
 *
 * ## The rule
 *
 * A username is **Latin letters, Thai letters and marks, digits, underscores
 * and single dots**, 3 to 20 characters, not starting with a dot or a
 * combining mark and not ending with a dot.
 *
 * ## Why not \p{Script=Latin}
 *
 * Because it lets impersonation back in, and the old ASCII-only pattern was
 * blocking it by accident. Measured, not assumed:
 *
 * ```
 * /^\p{Script=Latin}$/u.test('Ｐ')  // U+FF30 FULLWIDTH LATIN CAPITAL P -> true
 * /^\p{Script=Latin}$/u.test('ı')  // U+0131 DOTLESS I                 -> true
 * ```
 *
 * `Ｐｏｏｍ` and `ı` are exactly the homoglyphs a username has to keep apart
 * from `Poom` and `i`, and the script property waves both through — it is a
 * statement about which *writing system* a character belongs to, not about
 * whether two characters look alike. Latin here therefore means ASCII
 * `a-zA-Z`, which is what every username in the database already is and what
 * no other script can be confused with.
 *
 * ## Why not \p{Script=Thai} either
 *
 * Same reason in the other direction — it is broader than "Thai letters":
 *
 * ```
 * /^\p{Script=Thai}$/u.test('๏')  // U+0E4F FONGMAN, category Po  -> true
 * /^\p{Script=Thai}$/u.test('๚')  // U+0E5A ANGKHANKHU, Po        -> true
 * ```
 *
 * Thai punctuation in a username is not something anybody asked for, so the
 * two ranges below are spelled out: [THAI_BASE] is the consonants, the spacing
 * vowels and the leading vowels, and [THAI_MARK] is the marks that hang above
 * and below them. Between them they are every Thai letter and mark and nothing
 * else — no `฿`, no `๏`, no `๚`, and no Thai digits.
 *
 * Thai digits are left out deliberately rather than by omission. `0-9` are
 * already allowed, and `poom๑` beside `poom1` is two accounts a Thai reader
 * says the same way out loud, which is the impersonation this file exists to
 * prevent.
 */

/** ก through ฯ, the spacing vowels ะ า ำ, and the leading vowels เ แ โ ใ ไ ๅ ๆ. */
const THAI_BASE = '\u0E01-\u0E30\u0E32\u0E33\u0E40-\u0E46';

/** The marks that hang off a base: ั, the upper and lower vowels, the tones. */
const THAI_MARK = '\u0E31\u0E34-\u0E3A\u0E47-\u0E4E';

/**
 * The first character has to be something a mark can hang off.
 *
 * A username opening with a combining mark renders as a dotted circle — the
 * font drawing "this mark has nothing to attach to" — so the start of the
 * string is the one place the marks are refused.
 */
const START = `[a-zA-Z0-9_${THAI_BASE}]`;

/**
 * Everywhere else, including the last character.
 *
 * Marks are allowed at the end on purpose, unlike dots: `ก็` is a Thai word
 * and it ends in one. What the trailing position refuses is a dot, which this
 * class simply does not contain.
 */
const BODY = `[a-zA-Z0-9_${THAI_BASE}${THAI_MARK}]`;

/**
 * The shape, with the dot rule the ASCII version already had: no leading dot,
 * no trailing dot, and never two in a row — those are the shapes that read as
 * one name and sort as another.
 */
export const USERNAME_PATTERN = new RegExp(`^${START}(?:${BODY}|\\.(?![.]))*${BODY}$`, 'u');

export const USERNAME_MIN_LENGTH = 3;
export const USERNAME_MAX_LENGTH = 20;

const SARA_AM = '\u0E33';
const NIKHAHIT = '\u0E4D';
const SARA_AA = '\u0E32';

/** A mark, or the spacing vowel that an expanded [SARA_AM] leaves behind. */
const IS_MARK = new RegExp(`[${THAI_MARK}]`, 'u');
const belongsToCluster = (ch) => IS_MARK.test(ch) || ch === SARA_AA;

/**
 * The form a username is stored in.
 *
 * Trimmed and NFC, and nothing else. The rider's own spelling survives —
 * including their capitalisation, which is what the display name is drawn
 * from — for the same reason migration 0007 indexed `lower(username)` rather
 * than storing the lowercased value. Deciding whether two names collide is
 * [usernameKey]'s job, and it is a different question from what to draw.
 */
export function normalizeUsername(value) {
  return value.trim().normalize('NFC');
}

/**
 * What two usernames are compared by. Equal keys mean the same rider.
 *
 * ## Why lowercase is not enough any more
 *
 * Thai has no case, so the old `lower(username)` did nothing for it — and it
 * does not have to, because Thai's collision problem is a different one:
 * **two sequences of code points that draw the identical glyphs.** Unicode
 * normalisation does not fix them, which is the part worth writing down,
 * because "we call NFC" reads like it would:
 *
 *  - `สำ` is `0E2A 0E33`. `สํา` is `0E2A 0E4D 0E32`. They are the same three
 *    marks of ink and NFC leaves them as two different strings — SARA AM has
 *    no canonical decomposition in Unicode, so there is nothing for NFC to do.
 *  - `กี่` and `ก่ี` are the same glyphs with the vowel and the tone stored in
 *    the other order. NFC will not reorder them either: canonical ordering
 *    only sorts marks by combining class, and SARA II's is 0, so it never
 *    moves.
 *
 * Either one is a way to register a name that renders exactly like somebody
 * else's, which is the thing a unique username is for.
 *
 * ## What this does instead
 *
 * 1. NFC, for everything Unicode *does* handle.
 * 2. Expand every `SARA AM` into `NIKHAHIT` + `SARA AA`, so the two spellings
 *    above become the same sequence of pieces.
 * 3. Within each cluster — a base character and the marks hanging off it —
 *    sort the marks and put the spacing vowel last. Two marks that sit in
 *    different places (one above, one below) draw the same either way round,
 *    so the order they were typed in must not make two names.
 * 4. Collapse `NIKHAHIT` + `SARA AA` back to `SARA AM`, so the key reads as
 *    Thai rather than as debris.
 * 5. Lowercase, which is the ASCII half and still the whole job for `Poom`.
 *
 * Names that only *look* similar are deliberately left alone: `กอง` and
 * `ก้อง` are different words and stay different keys.
 */
export function usernameKey(value) {
  if (value === null || value === undefined) {
    return null;
  }

  const expanded = normalizeUsername(value).split(SARA_AM).join(NIKHAHIT + SARA_AA);

  let out = '';
  let i = 0;
  while (i < expanded.length) {
    // A cluster char with no base in front of it is left exactly where it is.
    // It cannot pass validation anyway, and a normaliser is not the place to
    // start deleting somebody's input.
    if (belongsToCluster(expanded[i])) {
      out += expanded[i];
      i += 1;
      continue;
    }

    out += expanded[i];
    i += 1;

    let end = i;
    while (end < expanded.length && belongsToCluster(expanded[end])) {
      end += 1;
    }
    const cluster = [...expanded.slice(i, end)];
    const marks = cluster.filter((ch) => ch !== SARA_AA).sort();
    const spacing = cluster.filter((ch) => ch === SARA_AA);
    out += marks.join('') + spacing.join('');
    i = end;
  }

  return out.split(NIKHAHIT + SARA_AA).join(SARA_AM).normalize('NFC').toLowerCase();
}
