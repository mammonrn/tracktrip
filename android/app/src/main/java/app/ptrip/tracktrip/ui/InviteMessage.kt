package app.ptrip.tracktrip.ui

import android.content.Context
import android.content.Intent
import app.ptrip.tracktrip.R

/**
 * The invite a rider sends from the share sheet.
 *
 * One function, and one message, because the alternative is what this replaced:
 * the text was assembled inline where the sheet was opened, so nothing could
 * read it back and nothing could test it.
 *
 * ## Why there is no subject
 *
 * `ACTION_SEND` has two text extras and only email keeps them apart.
 * `EXTRA_SUBJECT` is a header to Gmail; to SMS/MMS, LINE, Messenger and most
 * other chat targets it is simply the first line of the message they compose.
 * So a subject that restates the body's opening — "Join my trip on PTrips"
 * above "Join my trip "X" on PTrips." — arrives as one message that says the
 * same thing twice. That is not two sends and not a duplicated call: it is one
 * message carrying two copies of the invitation, one from each extra.
 *
 * The body is written to stand alone, which leaves nothing for a subject to
 * add, so it is not set at all. Email is not the loser here: inviting somebody
 * by address is a separate action on the trip screen that goes through the
 * server, where the subject is the server's to write.
 *
 * Anything added to this message belongs in [R.string.invite_share_body], not
 * in a second extra.
 */
fun inviteMessage(context: Context, tripName: String, code: String): String =
    context.getString(R.string.invite_share_body, tripName, code, joinWebLinkFor(code))

/**
 * The chooser to start for one press of "share invite".
 *
 * Built here rather than at the call site so that the only text leaving the app
 * is [inviteMessage]'s, and the tests can see the finished intent.
 */
fun inviteShareIntent(context: Context, tripName: String, code: String): Intent =
    Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, inviteMessage(context, tripName, code))
        },
        context.getString(R.string.invite_share_chooser),
    )
