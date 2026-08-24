package com.example.tail.widget

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Cognitive Priming — mastered-pattern bank (v2)
 * ════════════════════════════════════════════════════════════════════════
 *
 * The priming module presents FAMILIAR, previously-mastered tactical
 * patterns (Template Theory: basal-ganglia chunking, near-zero prefrontal
 * metabolic cost) instead of novel calculation. Each entry is a classic
 * mate-in-one motif every improving player has drilled hundreds of times;
 * the UI additionally enforces a 3-second minimum latency per puzzle
 * (blunder-check conditioning — see
 * [ChessReadinessV2Engine.primingMoveAccepted]).
 *
 * Board encoding: 64 chars, index 0 = a8 … 7 = h8, 56 = a1 … 63 = h1.
 * Uppercase = White, lowercase = black, '.' = empty. White always mates.
 */
object ChessPrimingBank {

    data class PrimingPuzzle(
        val title: String,
        val motif: String,
        val board: String,
        /** 0-63 index of the piece that moves. */
        val fromIdx: Int,
        /** 0-63 index of the destination square. */
        val toIdx: Int,
        /** One-line why-it-works explanation shown after solving. */
        val explanation: String
    )

    /** Classic back-rank mate: Rd1–d8#. */
    private val BACK_RANK = PrimingPuzzle(
        title = "Back-Rank Mate",
        motif = "The undefended 8th rank",
        board =
            "........" +
            ".....ppp" +
            "........" +
            "........" +
            "........" +
            "........" +
            "........" +
            "...R.K..",
        fromIdx = 3,   // d1
        toIdx = 59,    // d8
        explanation = "The rook takes the undefended back rank; the king's own " +
            "pawns seal every escape."
    )

    /** Scholar's mate finish: Qh5xf7#. */
    private val SCHOLARS = PrimingPuzzle(
        title = "Scholar's Mate Finish",
        motif = "Queen + bishop battery on f7",
        board =
            "r.bqkb.r" +
            "pppp.ppp" +
            "..n..n.." +
            "....p..Q" +
            "..B.P..." +
            "........" +
            "PPPP.PPP" +
            "RNB.K.NR",
        fromIdx = 39,  // h5
        toIdx = 53,    // f7
        explanation = "f7 is the weakest square in the opening — the bishop " +
            "supports the queen, the king has no flight square."
    )

    /** Philidor's smothered mate finish: Ng5–f7#. */
    private val SMOTHERED = PrimingPuzzle(
        title = "Smothered Mate",
        motif = "The king buried by its own army",
        board =
            "......rk" +
            ".......p" +
            "........" +
            "......N." +
            "........" +
            "........" +
            "........" +
            "......K.",
        fromIdx = 46,  // g5
        toIdx = 53,    // f7
        explanation = "The knight checks from f7 where nothing can touch it; " +
            "the rook and pawn smother their own king."
    )

    /** Queen + knight mating net: Qg6–g7#. */
    private val QUEEN_KNIGHT = PrimingPuzzle(
        title = "Queen & Knight Mate",
        motif = "Supported queen contact check",
        board =
            ".....r.k" +
            "........" +
            "......Q." +
            ".......N" +
            "........" +
            "........" +
            "........" +
            "......K.",
        fromIdx = 46,  // g6
        toIdx = 54,    // g7
        explanation = "The knight on h5 guards the contact-checking queen — " +
            "the king can neither take nor run."
    )

    /** Rook ladder endgame mate: Ra1–a8#. */
    private val ROOK_LADDER = PrimingPuzzle(
        title = "Rook Ladder Mate",
        motif = "King opposition + rook cut-off",
        board =
            ".k......" +
            "........" +
            ".K......" +
            "........" +
            "........" +
            "........" +
            "........" +
            "R.......",
        fromIdx = 56,  // a1
        toIdx = 0,     // a8
        explanation = "The kings stand opposed; the rook delivers the ladder " +
            "check along the 8th rank."
    )

    /** Damiano-style queen mate: Qh5xh7# (supported by the g6 pawn). */
    private val DAMIANO = PrimingPuzzle(
        title = "Pawn-Supported Queen Mate",
        motif = "h7 contact check, g6 pawn guard",
        board =
            ".....rk." +
            ".....ppp" +
            "......P." +
            ".......Q" +
            "........" +
            "........" +
            "........" +
            "......K.",
        fromIdx = 39,  // h5
        toIdx = 55,    // h7
        explanation = "The g6 pawn guards the queen's contact check on h7 — " +
            "the classic Damiano pattern."
    )

    /** Arabian mate: Ra7–h7# (knight on f6 covers g8 and h7). */
    private val ARABIAN = PrimingPuzzle(
        title = "Arabian Mate",
        motif = "Rook + knight corner net",
        board =
            ".......k" +
            "R......." +
            ".....N.." +
            "........" +
            "........" +
            "........" +
            "........" +
            "......K.",
        fromIdx = 48,  // a7
        toIdx = 55,    // h7
        explanation = "The knight covers g8 and protects the rook — the oldest " +
            "recorded mating net in chess."
    )

    /** The full bank, stable order. */
    val ALL = listOf(
        BACK_RANK, SCHOLARS, SMOTHERED, QUEEN_KNIGHT,
        ROOK_LADDER, DAMIANO, ARABIAN
    )

    /**
     * Picks the [count] puzzles for today's priming run: a deterministic
     * rotation seeded by the epoch day, so the same day always presents the
     * same set (familiarity!) while successive days cycle through the bank.
     */
    fun selectForDay(epochDay: Long, count: Int = ChessReadinessV2Engine.PRIMING_PUZZLE_COUNT): List<PrimingPuzzle> {
        if (count >= ALL.size) return ALL
        val start = (epochDay % ALL.size).toInt()
        return (0 until count).map { i -> ALL[(start + i) % ALL.size] }
    }

    /** Algebraic square name ("a1"…"h8") for a 0-63 board index. */
    fun squareName(idx: Int): String {
        val file = 'a' + (idx % 8)
        val rank = 8 - (idx / 8)
        return "$file$rank"
    }
}
