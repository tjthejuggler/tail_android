package com.example.tail.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

/**
 * Kenga/Octo -> Tail refresh ping. Sent by sibling apps right after their
 * figure data changes (logged set / session). Manifest-declared with the
 * signature-level TAIL_INTEGRATION permission, so only same-keystore apps
 * can deliver it. The pull itself is hash-gated inside
 * [SceneFigureClient.fetchIfStale], so a redundant ping costs one cheap
 * provider query.
 */
class SceneFigureRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SceneFigureClient.ACTION_SCENE_FIGURES_CHANGED) return
        val pending = goAsync()
        CoroutineScopeIO.launch {
            try {
                CombinedSceneWidgetProvider.refreshAll(context.applicationContext)
            } catch (_: Exception) {
                // best-effort — a missed ping self-heals on the next refresh
            } finally {
                pending.finish()
            }
        }
    }

    private object CoroutineScopeIO :
        kotlinx.coroutines.CoroutineScope {
        override val coroutineContext =
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    }
}
