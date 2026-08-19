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
}
