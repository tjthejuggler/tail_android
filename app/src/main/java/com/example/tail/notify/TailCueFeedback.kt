package com.example.tail.notify

import android.content.Context
import android.util.Log
import com.example.tail.data.HabitNotification
import com.example.tail.data.NotificationStore
import com.example.tail.data.SettingsRepository
import com.example.tail.data.bridgeConnectionFrom
import com.example.tail.data.tailcue.TailCue
import com.example.tail.data.tailcue.TailCueService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Sends cue feedback (👍 / 👎 + optional note explaining why) from the
 * notification center back to TailCue via the bridge, then dismisses the cue
 * everywhere. TailCue stores likes/dislikes and notes so future warnings and
 * research directions can be improved — feedback that never leaves the phone
 * would be lost, so the note is the whole point.
 */
object TailCueFeedback {

    private const val TAG = "TailCueFeedback"

    /**
     * @return true when feedback was delivered (or rating is 0 = plain
     *         dismiss, which needs no bridge call).
     */
    suspend fun sendAndDismiss(
        context: Context,
        ask: HabitNotification,
        rating: Int,
        comment: String?
    ): Boolean = withContext(Dispatchers.IO) {
        val cueId = TailCue.cueIdFromAskId(ask.id)
        if (cueId == null) return@withContext false

        var delivered = rating == 0
        if (rating != 0) {
            try {
                val settings = SettingsRepository(context).settingsFlow.first()
                if (settings.bridgeEnabled) {
                    bridgeConnectionFrom(settings.garminProxyUrl, settings.garminAppToken)
                        ?.let { (url, token) ->
                            delivered = TailCueService()
                                .sendFeedback(url, token, cueId, rating, comment)
                        }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cue feedback failed: ${e.message}")
            }
        }

        // Dismiss everywhere regardless — feedback failing to send must not
        // trap the cue in the center; the webapp history allows rating too.
        NotificationStore(context).remove(ask.id)
        HabitNotifier.cancelAsk(context, ask.id)
        Log.i(TAG, "Cue '$cueId' rated $rating note='${comment?.take(60)}' delivered=$delivered")
        delivered
    }
}
