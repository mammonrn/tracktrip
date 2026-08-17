package app.ptrip.tracktrip.data

/**
 * What to call a rider, everywhere.
 *
 * A username is the name someone chose for themselves, so it wins over the
 * display name Google supplied — that one is often a legal name, and a rider
 * who set a handle did so to be known by it.
 *
 * One function rather than the same `?:` chain written out at each call site:
 * the rule is a product decision, and the moment it lives in six places the
 * six start disagreeing about blank strings.
 */
fun riderLabel(username: String?, displayName: String?, fallback: String): String =
    username?.takeIf { it.isNotBlank() }
        ?: displayName?.takeIf { it.isNotBlank() }
        ?: fallback
