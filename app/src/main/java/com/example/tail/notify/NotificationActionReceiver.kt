package com.example.tail.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.tail.data.NotificationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "NotifActionReceiver"

/**
 * Handles the Yes/No actions on the habit-ask system notifications.
 *
 * Answering here applies the effect via [HabitAsks.applyAnswer], removes the
 * record from the [NotificationStore] and cancels the notification — because
 * the store is the single source of truth, the in-app notification center and
 * the pending flash update/disappear automatically (answer-anywhere,
 * dismiss-everywhere).
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ANSWER = "com.example.tail.NOTIF_ANSWER"
        const val EXTRA_ASK_ID = "ask_id"
        const val EXTRA_ANSWER = "answer"

        /** Builds the broadcast intent for a Yes ([answer]=true) / No answer. */
        fun answerIntent(context: Context, askId: String, answer: Boolean): Intent {
            return Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_ANSWER
                putExtra(EXTRA_ASK_ID, askId)
                putExtra(EXTRA_ANSWER, answer)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ANSWER) return
        val askId = intent.getStringExtra(EXTRA_ASK_ID) ?: return
        val answer = intent.getBooleanExtra(EXTRA_ANSWER, false)

        val pendingResult = goAsync()
        val appContext = context.applicationContext

        scope.launch {
            try {
                val store = NotificationStore(appContext)
                val ask = store.get(askId)
                HabitNotifier.cancelAsk(appContext, askId)
                if (ask != null) {
                    val resolved = HabitAsks.applyAnswer(appContext, ask, answer)
                    if (resolved) {
                        store.remove(askId)
                        Log.i(TAG, "Ask '$askId' answered ${if (answer) "YES" else "NO"} from system notification")
                    } else {
                        // The effect (text log / increment) failed even after
                        // retries — keep the ask and re-post the notification
                        // so the user can answer it again instead of the
                        // answer being silently lost.
                        HabitNotifier.postAsk(appContext, ask)
                        Log.w(TAG, "Ask '$askId' answer FAILED — ask kept for retry")
                    }
                } else {
                    Log.i(TAG, "Ask '$askId' already answered elsewhere — nothing to do")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process notification answer: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
