package com.example.tail.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Persists which collapsible sections are expanded, so screens reopen the way
 * the user left them on the previous app launch.
 *
 * `remember` / `rememberSaveable` only survive configuration changes at best —
 * this tiny store writes through to SharedPreferences under
 * `"<scope>·<section key>"` (e.g. `"chess·Readiness Over Time"` or
 * `"settings·Integrations"`).
 *
 * Only explicit user toggles are persisted; the caller's [default] is returned
 * untouched until the user actually interacts with a section.
 */
object SectionExpansionStore {

    private const val PREFS_NAME = "section_expansion"
    private const val SEP = '·'

    @Volatile
    private var prefs: SharedPreferences? = null
    private val mem = HashMap<String, Boolean>()

    private fun prefs(ctx: Context): SharedPreferences =
        prefs ?: ctx.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { prefs = it }

    fun isExpanded(ctx: Context, scope: String, key: String, default: Boolean): Boolean {
        val k = scope + SEP + key
        mem[k]?.let { return it }
        val v = prefs(ctx).getBoolean(k, default)
        mem[k] = v
        return v
    }

    fun setExpanded(ctx: Context, scope: String, key: String, value: Boolean) {
        val k = scope + SEP + key
        mem[k] = value
        prefs(ctx).edit().putBoolean(k, value).apply()
    }
}

/**
 * Drop-in replacement for `remember(key) { mutableStateOf(default) }` in
 * collapsible section headers. The returned state auto-persists every change,
 * so the section reopens in its most recent expanded/collapsed state across
 * app restarts.
 *
 * Usage:
 * ```
 * var expanded by rememberSectionExpansion("chess", title, startExpanded)
 * ```
 */
@Composable
fun rememberSectionExpansion(
    scope: String,
    key: String,
    default: Boolean = false
): MutableState<Boolean> {
    val context = LocalContext.current
    val state = remember(scope, key) {
        mutableStateOf(SectionExpansionStore.isExpanded(context, scope, key, default))
    }

    // Write through whenever the value diverges from what is stored (i.e. the
    // user toggled it). The initial composition is a read-only no-op.
    LaunchedEffect(scope, key, state.value) {
        if (SectionExpansionStore.isExpanded(context, scope, key, default) != state.value) {
            SectionExpansionStore.setExpanded(context, scope, key, state.value)
        }
    }

    return state
}
