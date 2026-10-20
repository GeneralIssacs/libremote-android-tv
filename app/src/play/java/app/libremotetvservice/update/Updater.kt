package app.libremotetvservice.update

import android.app.Activity
import android.content.Context
import app.libremotetvservice.data.Tv

/**
 * Google Play keeps this build up to date, and its policy forbids any other way, so there is no updater here: not
 * for Libremote, nor for Libremote Receiver on the TV.
 */
object Updater {
    fun offered(context: Context) = false
    fun autoCheck(context: Context) = false
    fun setAutoCheck(context: Context, on: Boolean) = Unit
    fun lastCheck(context: Context) = 0L
    fun checkOnStart(activity: Activity, tv: Tv?) = Unit
    fun checkNow(activity: Activity, done: () -> Unit) = Unit
    fun receiverUpdate(context: Context, tv: Tv): String? = null
    fun receiverNote(context: Context, tv: Tv): String? = null
    fun dismissReceiverNote(context: Context, tv: Tv) = Unit
    fun updateReceiver(activity: Activity, tv: Tv) = Unit
}
