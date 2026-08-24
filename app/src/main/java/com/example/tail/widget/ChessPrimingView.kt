package com.example.tail.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * The Cognitive Priming board — renders one [ChessPrimingBank.PrimingPuzzle]
 * and accepts a from-square → to-square move selection.
 *
 * Two structural mechanics from the research framework:
 *  1. FAMILIAR PATTERNS ONLY — the board shows a mastered mate-in-one motif;
 *     recognition (not calculation) is the point.
 *  2. ENFORCED SLOW-DOWN — any move attempt inside the mandatory
 *     [ChessReadinessV2Engine.PRIMING_MIN_DELAY_MS] window is REJECTED with
 *     a "hold — blunder check" signal
 *     ([ChessReadinessV2Engine.primingMoveAccepted]); the caller also gets a
 *     per-tick countdown so the UI can show the remaining hold time.
 */
class ChessPrimingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Result of one complete from→to selection attempt. */
    sealed class MoveResult {
        /** Correct mating move, accepted after the latency window. */
        object Correct : MoveResult()

        /** Accepted after the window, but the wrong move. */
        data class Wrong(val fromIdx: Int, val toIdx: Int) : MoveResult()

        /** Rejected — attempted inside the mandatory 3-second window. */
        object TooSoon : MoveResult()
    }

    /** Fired when the user completes a from→to selection (or is rejected). */
    var onMoveAttempt: ((MoveResult) -> Unit)? = null

    /** Fired ~10×/s while the latency window is still open (seconds left). */
    var onHoldCountdown: ((secondsRemaining: Int) -> Unit)? = null

    private var puzzle: ChessPrimingBank.PrimingPuzzle? = null
    private var shownAtMs = 0L
    private var selectedFrom = -1

    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEEEED2.toInt() }
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF769656.toInt() }
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8066CCFF.toInt() }
    private val rejectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66EF4444.toInt() }
    private val piecePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 34f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val coordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF999999.toInt()
        textSize = 10f
        textAlign = Paint.Align.LEFT
    }

    private var flashRejectUntil = 0L
    private val cellRect = RectF()

    private val countdownRunnable = object : Runnable {
        override fun run() {
            val p = puzzle ?: return
            val remain = ChessReadinessV2Engine.primingSecondsRemaining(shownAtMs, SystemClock.elapsedRealtime())
            onHoldCountdown?.invoke(remain)
            if (remain > 0) postDelayed(this, 100)
        }
    }

    /** Loads a puzzle and starts its latency window. */
    fun showPuzzle(p: ChessPrimingBank.PrimingPuzzle) {
        puzzle = p
        selectedFrom = -1
        shownAtMs = SystemClock.elapsedRealtime()
        removeCallbacks(countdownRunnable)
        post(countdownRunnable)
        onHoldCountdown?.invoke(
            ChessReadinessV2Engine.primingSecondsRemaining(shownAtMs, SystemClock.elapsedRealtime())
        )
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(countdownRunnable)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = puzzle ?: return
        val board = p.board
        val size = minOf(width, height).toFloat()
        val originX = (width - size) / 2f
        val originY = (height - size) / 2f
        val cell = size / 8f

        // Scale piece glyphs to the cell size.
        piecePaint.textSize = cell * 0.78f
        outlinePaint.textSize = cell * 0.78f

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val idx = r * 8 + c
                val left = originX + c * cell
                val top = originY + r * cell
                cellRect.set(left, top, left + cell, top + cell)
                canvas.drawRect(cellRect, if ((r + c) % 2 == 0) lightPaint else darkPaint)

                if (idx == selectedFrom) {
                    canvas.drawRect(cellRect, selectPaint)
                }
                if (SystemClock.elapsedRealtime() < flashRejectUntil) {
                    canvas.drawRect(cellRect, rejectPaint)
                }

                val ch = board.getOrNull(idx)
                if (ch != null && ch != '.') {
                    val cx = left + cell / 2f
                    val cy = top + cell * 0.78f
                    // White pieces get a dark outline so they read on light squares.
                    outlinePaint.color = if (ch.isUpperCase()) Color.BLACK else 0xFF333333.toInt()
                    if (ch.isUpperCase()) canvas.drawText(ch.toString(), cx, cy, outlinePaint)
                    piecePaint.color = if (ch.isUpperCase()) Color.WHITE else Color.BLACK
                    canvas.drawText(ch.toString(), cx, cy, piecePaint)
                }
            }
        }
        // File/rank coordinates (a…h, 1…8) along the bottom/left edges.
        coordPaint.textSize = cell * 0.18f
        for (c in 0 until 8) {
            val label = ('a' + c).toString()
            canvas.drawText(label, originX + c * cell + 2f, originY + size - 2f, coordPaint)
        }
        for (r in 0 until 8) {
            val label = (8 - r).toString()
            canvas.drawText(label, originX + 1f, originY + r * cell + coordPaint.textSize + 2f, coordPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val p = puzzle ?: return false
        if (event.action != MotionEvent.ACTION_DOWN) return false

        val size = minOf(width, height).toFloat()
        val originX = (width - size) / 2f
        val originY = (height - size) / 2f
        val cell = size / 8f
        val col = ((event.x - originX) / cell).toInt()
        val row = ((event.y - originY) / cell).toInt()
        if (col !in 0..7 || row !in 0..7) return true
        val idx = row * 8 + col

        // Only white pieces may be picked up.
        val ch = p.board.getOrNull(idx)
        if (selectedFrom < 0) {
            if (ch != null && ch.isUpperCase() && ch != '.') {
                selectedFrom = idx
                invalidate()
            }
            return true
        }

        // A from→to selection: enforce the mandatory latency window.
        val now = SystemClock.elapsedRealtime()
        if (!ChessReadinessV2Engine.primingMoveAccepted(shownAtMs, now)) {
            flashRejectUntil = now + 350
            performClick()
            invalidate()
            onMoveAttempt?.invoke(MoveResult.TooSoon)
            return true
        }
        val from = selectedFrom
        selectedFrom = -1
        invalidate()
        val result = if (from == p.fromIdx && idx == p.toIdx) MoveResult.Correct
        else MoveResult.Wrong(from, idx)
        onMoveAttempt?.invoke(result)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
