package app.ptrip.tracktrip.location

/**
 * Whether to ask Android to stop putting this app to sleep.
 *
 * ## What the exemption is for
 *
 * A foreground service is what lets the app read location with the phone in a
 * pocket, and it is not enough on its own. Android's Doze and app-standby
 * buckets still throttle a backgrounded app: alarms are deferred, network is
 * batched, and a reporting loop that asks for a fix every forty-five seconds
 * quietly becomes one every several minutes. On a long ride that is exactly
 * the failure the rider notices — the pins stop moving while their phone
 * insists it is still sharing.
 *
 * Being on the exemption list stops the system doing that. It is the standard
 * Android mechanism, requested with the standard system dialog; what it does
 * not cover is the extra layer several manufacturers add on top of it, which
 * is a separate problem and deliberately not attempted here.
 *
 * ## Why the decision lives in a file with no Android in it
 *
 * `PowerManager` cannot be read on a laptop, so the *rule* is kept apart from
 * the reading of it. The rule is the part that can be got wrong twice: asking
 * a rider who has already granted it, or asking again after they said no.
 */
object BatteryExemption {

    /**
     * Whether to put the system dialog in front of the rider now.
     *
     * [isExempt] is what `PowerManager.isIgnoringBatteryOptimizations` says,
     * and [alreadyAsked] is this app's own record of having shown the dialog
     * before.
     *
     * Nothing is asked of a rider who is already exempt — the dialog in that
     * state offers to *remove* the exemption, which is the opposite of the
     * point. Nothing is asked twice either: the answer is a system setting the
     * rider can change whenever they like, and a prompt that reappears every
     * ride is how an app teaches people to dismiss it without reading.
     */
    fun shouldAsk(isExempt: Boolean, alreadyAsked: Boolean): Boolean =
        !isExempt && !alreadyAsked

    /**
     * Where to send a rider who pressed the settings row, best first.
     *
     * ## Why "Unrestricted" was a dead button
     *
     * The row always launched `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
     * and that is the right thing to launch *while the app is optimised*: one
     * system dialog, one yes or no, no hunting through a list.
     *
     * It is not a dialog when the app is already on the exemption list. The
     * system activity behind it checks first and finishes immediately when
     * there is nothing to grant — no window, no animation, nothing. That is
     * exactly the report: the row reads "Unrestricted", and pressing it does
     * nothing at all.
     *
     * A rider pressing a row that already says "Unrestricted" is not asking
     * for the exemption they have. They are asking to *look at* the setting,
     * or to take it back, and the place that does both is this app's own page
     * in Android's settings — [BatterySettingsTarget.APP_DETAILS], where
     * "Battery" is one tap in and the choice is theirs either way.
     *
     * ## Why it is a list rather than an answer
     *
     * Every one of these can be missing. Some builds strip the direct dialog;
     * a managed device can disable the optimisation list; an app-details page
     * is all but universal but is still an activity that has to be there. A
     * caller works down the list and stops at the first that opens, so a phone
     * without one of them lands on the next rather than on nothing — which is
     * the failure this whole function exists to stop happening again.
     */
    fun destinations(isExempt: Boolean): List<BatterySettingsTarget> = if (isExempt) {
        // No exemption to ask for. Straight to where it can be read and
        // changed, with the system's own list as the fallback.
        listOf(BatterySettingsTarget.APP_DETAILS, BatterySettingsTarget.OPTIMISATION_LIST)
    } else {
        // One tap to grant it, and somewhere to go by hand if that dialog is
        // not on this phone.
        listOf(
            BatterySettingsTarget.REQUEST_EXEMPTION,
            BatterySettingsTarget.APP_DETAILS,
            BatterySettingsTarget.OPTIMISATION_LIST,
        )
    }
}

/**
 * One of the three places Android will let an app point at for this setting.
 *
 * Named rather than passed around as `Intent`s so the choice between them can
 * be tested on a laptop, for the same reason [BatteryExemption.shouldAsk] is:
 * the decision is the part that can be got wrong, and building an `Intent`
 * needs an Android runtime while choosing one does not.
 */
enum class BatterySettingsTarget {
    /**
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — the one-tap yes/no.
     *
     * Only worth launching when the app is *not* already exempt. Asked while
     * it is, the system activity finishes without drawing anything.
     */
    REQUEST_EXEMPTION,

    /** `ACTION_APPLICATION_DETAILS_SETTINGS` — this app's own settings page. */
    APP_DETAILS,

    /** `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` — every app on the phone. */
    OPTIMISATION_LIST,
}
