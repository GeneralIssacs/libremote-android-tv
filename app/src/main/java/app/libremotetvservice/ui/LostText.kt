package app.libremotetvservice.ui

import android.content.res.Resources
import app.libremotetvservice.R
import app.libremotetvservice.net.LostBatch
import app.libremotetvservice.net.LostBatch.Part

/**
 * What arrived of a batch whose connection broke, as sentences for the send screen and the notification:
 * "1 of 3 files arrived (a.jpg). b.mp4 was cut off. c.jpg wasn't sent." Empty when there is nothing to say.
 */
fun Resources.lostSummary(lost: LostBatch): String = lost.parts.joinToString(" ") { part ->
    when (part) {
        is Part.Arrived -> {
            val which = if (part.count == 1) part.first else getString(R.string.send_lost_range, part.first, part.last)
            getQuantityString(R.plurals.send_lost_arrived, part.total, part.count, part.total, which)
        }
        is Part.NoneArrived -> getQuantityString(R.plurals.send_lost_none_arrived, part.total, part.total)
        is Part.CutOff -> getString(R.string.send_lost_cut_off, part.name)
        is Part.Rest -> when {
            part.count == 1 && part.started -> getString(R.string.send_lost_not_arrived, part.first)
            part.count == 1 -> getString(R.string.send_lost_not_sent, part.first)
            part.started -> getQuantityString(R.plurals.send_lost_rest_not_arrived, part.count, part.count)
            else -> getQuantityString(R.plurals.send_lost_rest_not_sent, part.count, part.count)
        }
        is Part.Unconfirmed -> getString(R.string.send_lost_unconfirmed, part.name, part.number, part.total)
    }
}

/** The send screen's text for a lost connection: what arrived, then what Try again sends, and [detail] (how it broke). */
fun Resources.lostText(lost: LostBatch?, detail: String): String {
    val summary = lost?.let(::lostSummary).orEmpty()
    if (lost == null || summary.isEmpty()) return getString(R.string.send_lost_text, detail)
    val retry = when {
        lost.arrived == null -> getQuantityString(R.plurals.send_lost_retry_all, lost.remaining, lost.remaining, detail)
        lost.skip > 0 -> getQuantityString(R.plurals.send_lost_retry, lost.remaining, lost.remaining, detail)
        // Nothing arrived: Try again sends the batch as it was.
        else -> getString(R.string.send_lost_text, detail)
    }
    return "$summary $retry"
}
