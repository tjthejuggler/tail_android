package com.example.tail.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  THE ORRERY II — OFF-MAIN-THREAD FRAME PRODUCTION
 * ═══════════════════════════════════════════════════════════════════════
 *
 * The old spinner drew every frame in a Compose `Canvas` on the UI thread,
 * so any main-thread work happening during a load session (Room streaming,
 * DataStore reads, JSON parsing, GC) stole time from the Choreographer and
 * the animation visibly stuttered.
 *
 * This file moves frame production to a dedicated [HandlerThread]:
 *
 *  1. A single process-wide render thread at URGENT_DISPLAY priority hosts
 *     its own [Choreographer], so frames stay vsync-aligned like before.
 *  2. Each frame is painted with the UNCHANGED DrawScope renderers from
 *     HabitLoadingLayers/Monthly/Weekly/Daily via [CanvasDrawScope] into a
 *     GPU canvas obtained from [Surface.lockHardwareCanvas].
 *  3. The surface belongs to a [SurfaceView], which SurfaceFlinger composites
 *     DIRECTLY — unlike TextureView, whose buffer only becomes visible when
 *     the view redraws on the (stallable) UI thread. A stalled main thread
 *     therefore cannot affect the animation's display at all.
 *
 * Lifecycle safety: [SurfaceHolder.Callback.surfaceDestroyed] blocks until
 * the render thread has gone quiescent (single serial thread ⇒ no frame can
 * be in flight once the removal message ran), so the surface is never
 * touched after destroy — the use-after-free that crashed the earlier
 * TextureView attempt.
 *
 * The animation clock (periods, easing, RepeatMode semantics) mirrors the
 * previous Compose `infiniteRepeatable` specs exactly, so the visuals are
 * identical — just jank-proof.
 */

/** One render thread for the whole process; dies with the process. */
private val orreryRenderThread: HandlerThread by lazy {
    HandlerThread("OrreryRender", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
}

/** Serial queue owning every orrery frame. */
private val orreryRenderHandler: Handler by lazy { Handler(orreryRenderThread.looper) }

// Animation clock periods (ms) — mirror the previous Compose tween specs.
private const val PHASE_MS = 1400L
private const val PHASE2_MS = 2600L
private const val PHASE3_MS = 5200L
private const val PHASE4_MS = 900L
private const val BREATHE_MS = 1100L
private const val BREATHE2_MS = 2300L

/**
 * Sawtooth 0→1 over [periodMs], evaluated at t — the pure-Kotlin
 * equivalent of an `infiniteRepeatable(tween(period, LinearEasing), Restart)`
 * animation driven by frame time instead of the main-thread Choreographer.
 */
internal fun orreryPhase(tMs: Double, periodMs: Long): Float =
    ((tMs % periodMs) / periodMs).toFloat()

/**
 * Breathing 0→1→0 over 2×[halfMs] — the exact pure-Kotlin equivalent of
 * `infiniteRepeatable(tween(halfMs, FastOutSlowInEasing), Reverse)`:
 * forward leg is `ease(x)`, reverse leg is `1 - ease(x)` (Compose mirrors
 * the curve by inverting the OUTPUT, not the input — FastOutSlowIn is not
 * symmetric, so this distinction is visible and must be preserved).
 */
internal fun orreryBreath(tMs: Double, halfMs: Long): Float {
    val cycle = tMs % (2 * halfMs)
    return if (cycle < halfMs) {
        FastOutSlowInEasing.transform((cycle / halfMs).toFloat())
    } else {
        1f - FastOutSlowInEasing.transform(((cycle - halfMs) / halfMs).toFloat())
    }
}

/**
 * SurfaceView hosting the orrery, animated entirely on
 * [orreryRenderThread]. Instantiate via [HabitLoadingSpinner]; the main
 * thread never draws here, and SurfaceFlinger composites the surface
 * without any UI-thread involvement.
 */
internal class OrreryRenderView(context: Context) : SurfaceView(context) {

    /** Frozen tier triple for one loading session. Written on main, read on render thread. */
    @Volatile
    private var tiers = LoadingTiers(0, 0, 0)

    /** Screen density captured at composition time (px per dp). Main↔render thread. */
    @Volatile
    private var densityPxPerDp = 3f

    /** Surface dimensions, written on main thread, read on render thread. */
    @Volatile
    private var surfaceW = 0

    @Volatile
    private var surfaceH = 0

    /** Touched on the main thread AND read inside doFrame on the render thread. */
    @Volatile
    private var running = false

    /** Reusable across frames — one DrawScope engine, zero per-frame setup. */
    private val drawScope = CanvasDrawScope()

    init {
        // Composite ABOVE the app window so the translucent orrery floats
        // over whatever the screen shows behind it, and let SurfaceFlinger
        // — not the UI thread — put the frames on screen.
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                surfaceW = width
                surfaceH = height
                startRendering()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // The surface dies the moment this returns; the render
                // thread must be quiescent by then (see stopRenderingAndWait).
                stopRenderingAndWait()
            }
        })
    }

    /** Captures the frozen session parameters; idempotent and cheap. */
    fun configure(tiers: LoadingTiers, densityPxPerDp: Float) {
        this.tiers = tiers
        this.densityPxPerDp = densityPxPerDp
    }

    override fun onDetachedFromWindow() {
        // Quick, non-blocking stop; surfaceDestroyed does the safe
        // synchronized shutdown when the surface actually goes away.
        running = false
        orreryRenderHandler.post {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
        super.onDetachedFromWindow()
    }

    private fun startRendering() {
        if (running || surfaceW == 0 || surfaceH == 0) return
        running = true
        orreryRenderHandler.post {
            val choreographer = Choreographer.getInstance()
            choreographer.removeFrameCallback(frameCallback)
            choreographer.postFrameCallback(frameCallback)
        }
    }

    /**
     * Stops the loop AND waits until the render thread has processed the
     * removal. Because the render thread is a single serial thread, once
     * the removal message has run no frame is in flight and none can start
     * (doFrame checks [running] first) — the caller may safely let the
     * surface be destroyed.
     */
    private fun stopRenderingAndWait() {
        running = false
        val quiesced = CountDownLatch(1)
        orreryRenderHandler.post {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            quiesced.countDown()
        }
        try {
            quiesced.await(250, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            drawFrame(frameTimeNanos)
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Runs on [orreryRenderThread], vsync-aligned by its Choreographer. */
    private fun drawFrame(frameTimeNanos: Long) {
        val w = surfaceW
        val h = surfaceH
        val surface = holder.surface
        if (w <= 0 || h <= 0 || !surface.isValid) return
        val tMs = frameTimeNanos / 1_000_000.0

        val frameCanvas = try {
            // GPU-accelerated canvas (API 26+; minSdk is 26). Returns null
            // when the surface is no longer valid — skip the stale frame.
            surface.lockHardwareCanvas() ?: return
        } catch (_: Throwable) {
            return // surface went away between validity check and lock
        }
        try {
            @Suppress("DEPRECATION")
            frameCanvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            drawScope.draw(
                density = Density(densityPxPerDp),
                layoutDirection = LayoutDirection.Ltr,
                canvas = Canvas(frameCanvas),
                size = Size(w.toFloat(), h.toFloat())
            ) {
                drawOrrery(
                    LoadingPaintContext(
                        c = Offset(w / 2f, h / 2f),
                        radius = minOf(w, h) / 2f,
                        tiers = tiers,
                        monthColor = tierAccent(tiers.monthly),
                        weekColor = tierAccent(tiers.weekly),
                        dayColor = tierAccent(tiers.daily),
                        monthCombo = tierComboAccent(tiers.monthly),
                        weekCombo = tierComboAccent(tiers.weekly),
                        dayCombo = tierComboAccent(tiers.daily),
                        phase = orreryPhase(tMs, PHASE_MS),
                        phase2 = orreryPhase(tMs, PHASE2_MS),
                        phase3 = orreryPhase(tMs, PHASE3_MS),
                        phase4 = orreryPhase(tMs, PHASE4_MS),
                        breathe = orreryBreath(tMs, BREATHE_MS),
                        breathe2 = orreryBreath(tMs, BREATHE2_MS)
                    )
                )
            }
        } catch (_: Throwable) {
            // A single bad frame must never take the loop down; the next
            // vsync gets a fresh chance.
        } finally {
            try {
                surface.unlockCanvasAndPost(frameCanvas)
            } catch (_: Throwable) {
                // Surface died mid-frame; doFrame's validity check will
                // stop further drawing attempts.
            }
        }
    }
}

/**
 * The full orrery — global flourishes behind, the three personal layers,
 * resonance, then the flourishes in front. Draw order is identical to the
 * original main-thread Canvas body; the only thing that changed is WHO
 * runs it (render thread, not UI thread).
 */
internal fun DrawScope.drawOrrery(ctx: LoadingPaintContext) {
    val g = ctx.tiers.grandeur

    // ── Global flourishes (behind) ─────────────────────────────────
    if (g >= GrandeurThresholds.NEBULA) drawNebula(ctx)
    if (g >= GrandeurThresholds.STARFIELD) drawStarfield(ctx)
    if (g >= GrandeurThresholds.CORONA) drawCorona(ctx)
    if (g >= GrandeurThresholds.AURORA) drawAurora(ctx)
    if (g >= GrandeurThresholds.CONSTELLATION) drawConstellation(ctx)

    // ── The three personal layers ──────────────────────────────────
    drawWeeklyHalo(ctx)   // outer orbital system, in the weekly colour
    drawMonthlyCore(ctx)  // the central archetype, in the monthly colour
    drawDailySpark(ctx)   // the small central accent, in the daily colour

    // ── Rewards ────────────────────────────────────────────────────
    if (ctx.tiers.resonant) drawResonance(ctx)

    // ── Global flourishes (in front) ───────────────────────────────
    if (g >= GrandeurThresholds.SHOOTING_STARS) drawShootingStars(ctx)
    if (g >= GrandeurThresholds.SPECTRUM_CROWN) drawSpectrumCrown(ctx)
    if (g == GrandeurThresholds.TOTALITY || g >= GrandeurThresholds.TRANSCENDENCE) {
        drawTotality(ctx)
    }
    if (g >= GrandeurThresholds.POLAR_JETS) drawPolarJets(ctx)
    if (g >= GrandeurThresholds.HALO_OF_HALOS) drawHaloOfHalos(ctx)
    if (g >= GrandeurThresholds.TRANSCENDENCE) drawTranscendence(ctx)
}
