package com.example.tail.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Unit tests for the pure Chess Readiness stats computation:
 * readiness-context resolution at a point in time, game → record mapping,
 * and the full aggregation (time-of-day buckets, per-day averages,
 * authorized vs unauthorized games, green sessions, compliance).
 */
class ChessReadinessStatsCalculatorTest {

    private val zone = ZoneId.of("UTC")
    private val green = com.example.tail.widget.ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name
    private val yellow = com.example.tail.widget.ChessReadinessEngine.ReadinessState.YELLOW_LIGHT.name
    private val red = com.example.tail.widget.ChessReadinessEngine.ReadinessState.RED_LIGHT.name

    private fun ms(date: String, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.of(hour, minute), zone)
            .toInstant().toEpochMilli()

    private fun test(
        at: Long, ccrs: Int, state: String,
        startedAt: Long = at - 10 * 60 * 1000L
    ): ReadinessTestRecord = ReadinessTestRecord(
        timestamp = at, ccrs = ccrs, state = state,
        sSleep = 0, sClarity = 0, pPuzzle = 0, pRush = 0,
        sleepScore = 80, sleepFromGarmin = true,
        stress = 3, focus = 3, energy = 3,
        puzzleTimesSec = listOf(30, 40), rushScore = 20, rushStrikes = 0,
        rushAllTimeHigh = 20, sessionStartedAt = startedAt
    )

    private fun game(
        endTimeSec: Long, white: String = "me", black: String = "opp",
        whiteResult: String = "win", blackResult: String = "checkmated",
        timeControl: String = "180+2",
        rated: Boolean = true, rules: String = "chess",
        whiteRating: Int = 0, blackRating: Int = 0
    ): ChessComGame = ChessComGame(
        timeClass = "blitz", timeControl = timeControl, endTime = endTimeSec,
        whiteUsername = white, blackUsername = black,
        whiteResult = whiteResult, blackResult = blackResult,
        rated = rated, rules = rules,
        whiteRating = whiteRating, blackRating = blackRating
    )

    // ── readinessContextAt ───────────────────────────────────────────────

    @Test
    fun `no test before the moment yields no context`() {
        val (ctx, authorized) = readinessContextAt(emptyList(), ms("2026-08-10", 12))
        assertNull(ctx)
        assertFalse(authorized)
    }

    @Test
    fun `green inside the validity window authorizes play`() {
        val t = test(ms("2026-08-10", 9), 90, green)
        val (ctx, authorized) = readinessContextAt(listOf(t), ms("2026-08-10", 9) + 59 * 60 * 1000L)
        assertEquals(90, ctx?.ccrs)
        assertTrue(authorized)
    }

    @Test
    fun `green after the window expires does not authorize`() {
        val t = test(ms("2026-08-10", 9), 90, green)
        val (_, authorized) = readinessContextAt(listOf(t), ms("2026-08-10", 9) + 61 * 60 * 1000L)
        assertFalse(authorized)
    }

    @Test
    fun `yellow never authorizes rated play`() {
        val t = test(ms("2026-08-10", 9), 75, yellow)
        val (_, authorized) = readinessContextAt(listOf(t), ms("2026-08-10", 9, 30))
        assertFalse(authorized)
    }

    @Test
    fun `a later red test supersedes an earlier green`() {
        val g = test(ms("2026-08-10", 9), 90, green)
        val r = test(ms("2026-08-10", 11), 30, red)
        val (ctx, authorized) = readinessContextAt(listOf(g, r), ms("2026-08-10", 11, 15))
        assertEquals(30, ctx?.ccrs)
        assertFalse(authorized)
    }

    // ── gameToRecord ─────────────────────────────────────────────────────

    @Test
    fun `maps a won game as white with context`() {
        val t = test(ms("2026-08-10", 9), 90, green)
        val endSec = ms("2026-08-10", 9, 20) / 1000
        val rec = gameToRecord(game(endSec), "me", listOf(t))!!
        assertEquals("BLITZ", rec.type)
        assertEquals("opp", rec.opponent)
        assertTrue(rec.won)
        assertTrue(rec.authorized)
        assertEquals(90, rec.ccrsAtPlay)
        assertEquals(3.0, rec.minutes, 0.001)
    }

    @Test
    fun `maps a lost game as black`() {
        val t = test(ms("2026-08-10", 9), 30, red)
        val endSec = ms("2026-08-10", 9, 30) / 1000
        val rec = gameToRecord(
            game(endSec, white = "opp", black = "me",
                whiteResult = "win", blackResult = "checkmated"),
            "me", listOf(t)
        )!!
        assertFalse(rec.won)
        assertFalse(rec.authorized)
        assertEquals(red, rec.stateAtPlay)
    }

    @Test
    fun `skips games the user did not play and daily games`() {
        assertNull(gameToRecord(game(ms("2026-08-10", 10) / 1000, white = "a", black = "b"), "me", emptyList()))
        assertNull(gameToRecord(game(ms("2026-08-10", 10) / 1000, timeControl = "1/86400"), "me", emptyList()))
    }

    @Test
    fun `dedupe key is stable and case-insensitive on opponent`() {
        assertEquals(
            gameDedupeKey(1000, "Opp", "180+2"),
            gameDedupeKey(1000, "opp ", "180+2")
        )
        assertFalse(gameDedupeKey(1000, "a", "60") == gameDedupeKey(1001, "a", "60"))
    }

    // ── computeReadinessStats ────────────────────────────────────────────

    @Test
    fun `aggregates tests games sessions and buckets`() {
        val t0 = test(ms("2026-08-10", 9), 90, green, startedAt = ms("2026-08-10", 8, 50))
        val t1 = test(ms("2026-08-11", 22), 30, red)
        val g1 = gameToRecord(game(ms("2026-08-10", 9, 20) / 1000), "me", listOf(t0, t1))!!   // authorized win
        val g2 = gameToRecord(
            game(ms("2026-08-10", 9, 40) / 1000, whiteResult = "resigned", blackResult = "win"),
            "me", listOf(t0, t1)
        )!!                                                                                        // authorized loss
        val g3 = gameToRecord(game(ms("2026-08-10", 11) / 1000), "me", listOf(t0, t1))!!         // window expired → unauthorized win
        val g4 = gameToRecord(game(ms("2026-08-11", 22, 30) / 1000), "me", listOf(t0, t1))!!     // red state → unauthorized win
        val g5 = gameToRecord(
            game(ms("2026-08-01", 12) / 1000, whiteResult = "checkmated", blackResult = "win"),
            "me", listOf(t0, t1)
        )!!                                                                                        // before any test → no-test loss

        val stats = computeReadinessStats(
            listOf(t0, t1), listOf(g1, g2, g3, g4, g5),
            listOf(ReadinessBlockedRecord(ms("2026-08-11", 7), "cooldown")),
            zone
        )

        // Tests
        assertEquals(2, stats.totalTests)
        assertEquals(60.0, stats.avgCcrs, 0.001)
        assertEquals(1, stats.greenCount)
        assertEquals(1, stats.redCount)
        assertEquals(0, stats.yellowCount)
        assertEquals(90, stats.bestCcrs)
        assertEquals(30, stats.worstCcrs)
        assertEquals(10.0, stats.avgTestDurationMin, 0.001) // 10 min session each

        // Per-day averages
        assertEquals(
            listOf("2026-08-10" to 90, "2026-08-11" to 30),
            stats.dailyAvgCcrs
        )

        // Games vs readiness
        assertEquals(5, stats.totalGames)
        assertEquals(2, stats.gamesAuthorized)
        assertEquals(2, stats.gamesUnauthorized)
        assertEquals(1, stats.gamesNoTest)
        assertEquals(1, stats.winsAuthorized)
        assertEquals(2, stats.winsUnauthorized)
        assertEquals(50.0, stats.winRateAuthorized, 0.001)
        assertEquals(100.0, stats.winRateUnauthorized, 0.001)
        assertEquals(50.0, stats.complianceRate, 0.001)

        // Green sessions: one window (t0) covered both authorized games
        assertEquals(1, stats.greenSessions)
        assertEquals(2.0, stats.avgGamesPerGreenSession, 0.001)
        assertEquals(2, stats.maxGamesInOneGreenSession)

        // Time-of-day buckets (UTC): tests at 09:00 → 08–12, 22:00 → 20–24
        assertEquals(6, stats.timeBuckets.size) // always six buckets
        val morning = stats.timeBuckets.first { it.label == "08–12" }
        assertEquals(1, morning.testCount)
        assertEquals(90.0, morning.avgCcrs, 0.001)
        assertEquals(3, morning.gamesPlayed) // g1, g2, g3 all ended 09–11
        assertEquals(2, morning.gamesWon)
        val night = stats.timeBuckets.first { it.label == "20–24" }
        assertEquals(1, night.testCount)
        assertEquals(30.0, night.avgCcrs, 0.001)
        assertEquals(1, night.gamesPlayed) // g4
        assertEquals("08–12", stats.bestBucketLabel)
        assertEquals("20–24", stats.worstBucketLabel)

        // Blocked attempts
        assertEquals(1, stats.blockedAttempts)
    }

    @Test
    fun `empty log yields zeroed stats and full compliance`() {
        val stats = computeReadinessStats(emptyList(), emptyList(), emptyList(), zone)
        assertEquals(0, stats.totalTests)
        assertEquals(0, stats.totalGames)
        assertEquals(100.0, stats.complianceRate, 0.001)
        assertNull(stats.bestBucketLabel)
        assertEquals(6, stats.timeBuckets.size)
    }

    @Test
    fun `games before the first test count as no-test not violations`() {
        val t0 = test(ms("2026-08-10", 9), 90, green)
        val g = gameToRecord(game(ms("2026-08-05", 10) / 1000), "me", listOf(t0))!!
        val stats = computeReadinessStats(listOf(t0), listOf(g), emptyList(), zone)
        assertEquals(1, stats.gamesNoTest)
        assertEquals(0, stats.gamesUnauthorized)
        assertEquals(100.0, stats.complianceRate, 0.001)
    }

    // ── computeComplianceSeries ──────────────────────────────────────────

    @Test
    fun `compliance series categorizes post-adoption games and excludes pre-system ones`() {
        val t0 = test(ms("2026-08-10", 9), 90, green)
        val t1 = test(ms("2026-08-11", 22), 30, red)
        val gPre = gameToRecord(game(ms("2026-08-05", 10) / 1000), "me", listOf(t0, t1))!!     // before the system existed
        val gAuth = gameToRecord(game(ms("2026-08-10", 9, 20) / 1000), "me", listOf(t0, t1))!! // green, inside window
        val gDenied = gameToRecord(game(ms("2026-08-11", 22, 30) / 1000), "me", listOf(t0, t1))!! // fresh red test said no
        val gNoTest = gameToRecord(game(ms("2026-08-13", 14) / 1000), "me", listOf(t0, t1))!!  // only stale tests

        val series = computeComplianceSeries(
            listOf(gPre, gAuth, gDenied, gNoTest),
            listOf(t0, t1),
            t0.timestamp,
            zone
        )

        assertEquals(3, series.size) // the pre-system game is excluded
        val d10 = series[0]
        assertEquals(LocalDate.parse("2026-08-10"), d10.date)
        assertEquals(1, d10.authorized)
        assertEquals(0, d10.violationDenied)
        assertEquals(0, d10.violationNoTest)
        assertEquals(100.0, d10.compliantPct, 0.001)
        val d11 = series[1]
        assertEquals(LocalDate.parse("2026-08-11"), d11.date)
        assertEquals(0, d11.authorized)
        assertEquals(1, d11.violationDenied)
        assertEquals(0, d11.violationNoTest)
        val d13 = series[2]
        assertEquals(LocalDate.parse("2026-08-13"), d13.date)
        assertEquals(0, d13.authorized)
        assertEquals(0, d13.violationDenied)
        assertEquals(1, d13.violationNoTest)
    }

    @Test
    fun `expired green window counts as no fresh test not denied`() {
        val t0 = test(ms("2026-08-10", 9), 90, green)
        val gLate = gameToRecord(game(ms("2026-08-10", 11) / 1000), "me", listOf(t0))!! // 2 h after the green test
        val series = computeComplianceSeries(listOf(gLate), listOf(t0), t0.timestamp, zone)
        assertEquals(1, series.size)
        assertEquals(0, series[0].authorized)
        assertEquals(0, series[0].violationDenied)
        assertEquals(1, series[0].violationNoTest)
    }

    @Test
    fun `zero system start yields empty compliance series`() {
        assertTrue(computeComplianceSeries(emptyList(), emptyList(), 0L, zone).isEmpty())
    }

    // ── computeRatingStats ───────────────────────────────────────────────

    @Test
    fun `rating deltas split by compliance per pool with chess960 separate`() {
        val t0 = test(ms("2026-08-10", 9), 90, green) // system adoption
        // Standard blitz pool: pre-system baseline, then authorized, then violation
        val g0 = gameToRecord(game(ms("2026-08-08", 20) / 1000, whiteRating = 1500), "me", listOf(t0))!!
        val g1 = gameToRecord(game(ms("2026-08-10", 9, 20) / 1000, whiteRating = 1512), "me", listOf(t0))!!
        val g2 = gameToRecord(
            game(ms("2026-08-12", 14) / 1000, whiteRating = 1505,
                whiteResult = "checkmated", blackResult = "win"),
            "me", listOf(t0)
        )!!
        // Chess960 blitz pool: separate rating — first game is only a baseline
        val r1 = gameToRecord(game(ms("2026-08-10", 9, 30) / 1000, whiteRating = 2000, rules = "chess960"), "me", listOf(t0))!!
        val r2 = gameToRecord(
            game(ms("2026-08-11", 10) / 1000, whiteRating = 1985, rules = "chess960",
                whiteResult = "checkmated", blackResult = "win"),
            "me", listOf(t0)
        )!!

        val pools = computeRatingStats(listOf(g0, g1, g2, r1, r2), listOf(t0), t0.timestamp)

        assertEquals(2, pools.size)
        val std = pools.first { it.key == "chess|BLITZ" }
        assertEquals("Standard · Blitz", std.label)
        assertEquals(3, std.ratedGames)
        assertEquals(1505, std.currentRating)
        assertEquals(1, std.authorized.games)
        assertEquals(12, std.authorized.totalDelta)
        assertEquals(1, std.authorized.wins)
        assertEquals(1, std.violations.games)
        assertEquals(-7, std.violations.totalDelta)
        assertEquals(0, std.violations.wins)

        val fischer = pools.first { it.key == "chess960" }
        assertEquals("Chess960", fischer.label)
        assertEquals(2, fischer.ratedGames)
        assertEquals(1985, fischer.currentRating)
        assertEquals(0, fischer.authorized.games) // first pool game = baseline only
        assertEquals(1, fischer.violations.games)
        assertEquals(-15, fischer.violations.totalDelta)
    }

    @Test
    fun `chess960 games across speeds share one pool with a continuous chain`() {
        val t0 = test(ms("2026-08-10", 9), 90, green) // system adoption
        // chess.com gives Chess960 a SINGLE rating — a blitz and a rapid
        // game feed the same pool and the delta chain crosses speeds.
        val blitz1 = gameToRecord(game(ms("2026-08-10", 9, 20) / 1000, whiteRating = 2000, rules = "chess960"), "me", listOf(t0))!!
        val rapid1 = gameToRecord(game(ms("2026-08-10", 9, 30) / 1000, whiteRating = 1985, rules = "chess960", timeControl = "600"), "me", listOf(t0))!!
        val blitz2 = gameToRecord(game(ms("2026-08-10", 9, 40) / 1000, whiteRating = 1992, rules = "chess960"), "me", listOf(t0))!!

        val pools = computeRatingStats(listOf(blitz1, rapid1, blitz2), listOf(t0), t0.timestamp)
        assertEquals(1, pools.size)
        val p = pools[0]
        assertEquals("chess960", p.key)
        assertEquals("Chess960", p.label)
        assertEquals(3, p.ratedGames)
        assertEquals(1992, p.currentRating)
        // blitz1 is the baseline; rapid1 (−15) and blitz2 (+7) chain across speeds
        assertEquals(2, p.authorized.games)
        assertEquals(-8, p.authorized.totalDelta)
        assertEquals(0, p.violations.games)

        val history = computeRatingHistory(listOf(blitz2, rapid1, blitz1)) // unsorted input
        assertEquals(1, history.size)
        assertEquals(listOf(2000, 1985, 1992), history[0].points.map { it.rating })
    }

    @Test
    fun `unrated games are skipped from the rating chain`() {
        val t0 = test(ms("2026-08-10", 9), 90, green)
        val g1 = gameToRecord(game(ms("2026-08-10", 9, 20) / 1000, whiteRating = 1500), "me", listOf(t0))!!
        val gUnrated = gameToRecord(game(ms("2026-08-10", 9, 40) / 1000, rated = false), "me", listOf(t0))!!
        val g2 = gameToRecord(
            game(ms("2026-08-10", 9, 50) / 1000, whiteRating = 1500,
                whiteResult = "agreed", blackResult = "agreed"),
            "me", listOf(t0)
        )!!

        val pools = computeRatingStats(listOf(g1, gUnrated, g2), listOf(t0), t0.timestamp)

        assertEquals(1, pools.size)
        assertEquals(2, pools[0].ratedGames) // the unrated game is excluded
        assertNull(gUnrated.ratingAfter)
        assertEquals(1, pools[0].authorized.games)
        assertEquals(0, pools[0].authorized.totalDelta) // 1500 → 1500 across the unrated game
        assertEquals(0, pools[0].violations.games)
    }

    @Test
    fun `rating and variant map from the user's side of the game`() {
        val t0 = test(ms("2026-08-10", 9), 90, green)
        val asBlack = gameToRecord(
            game(ms("2026-08-10", 9, 20) / 1000, white = "opp", black = "me",
                whiteResult = "win", blackResult = "checkmated",
                rules = "chess960", whiteRating = 2100, blackRating = 1890),
            "me", listOf(t0)
        )!!
        assertEquals(1890, asBlack.ratingAfter)
        assertEquals("chess960", asBlack.variant)
        assertTrue(asBlack.rated)
    }

    @Test
    fun `no rated games yields no rating pools`() {
        assertTrue(computeRatingStats(emptyList(), emptyList(), 0L).isEmpty())
    }

    // ── computeRatingHistory ─────────────────────────────────────────────

    @Test
    fun `rating history spans entire history per pool sorted chronologically`() {
        val t0 = test(ms("2026-08-10", 9), 90, green)
        val old1 = gameToRecord(game(ms("2026-06-01", 10) / 1000, whiteRating = 1400), "me", listOf(t0))!!
        val old2 = gameToRecord(game(ms("2026-07-01", 10) / 1000, whiteRating = 1450), "me", listOf(t0))!!
        val new1 = gameToRecord(game(ms("2026-08-10", 9, 20) / 1000, whiteRating = 1512), "me", listOf(t0))!!
        val fisch = gameToRecord(game(ms("2026-08-10", 9, 30) / 1000, whiteRating = 2000, rules = "chess960"), "me", listOf(t0))!!

        val history = computeRatingHistory(listOf(new1, old2, fisch, old1)) // unsorted input

        assertEquals(2, history.size)
        val std = history.first { it.key == "chess|BLITZ" }
        assertEquals(3, std.points.size)
        assertEquals(listOf(1400, 1450, 1512), std.points.map { it.rating })
        assertEquals(1400, std.startRating)
        assertEquals(1512, std.endRating)
        assertEquals(1512, std.peakRating)
        assertEquals(1400, std.lowRating)
        // Pre-system games ARE included — the series spans the entire history
        assertTrue(std.points.first().endTimeMs < t0.timestamp)
        val fischer = history.first { it.key == "chess960" }
        assertEquals(1, fischer.points.size)
    }

    @Test
    fun `rating history excludes unrated games`() {
        val t0 = test(ms("2026-08-10", 9), 90, green)
        val g1 = gameToRecord(game(ms("2026-08-10", 9, 20) / 1000, whiteRating = 1500), "me", listOf(t0))!!
        val gUnrated = gameToRecord(game(ms("2026-08-10", 9, 40) / 1000, rated = false), "me", listOf(t0))!!
        val g2 = gameToRecord(game(ms("2026-08-10", 9, 50) / 1000, whiteRating = 1510), "me", listOf(t0))!!

        val history = computeRatingHistory(listOf(g1, gUnrated, g2))

        assertEquals(1, history.size)
        assertEquals(listOf(1500, 1510), history[0].points.map { it.rating })
    }

    // ── GameFilter / hourly / bucket analytics ────────────────────────────

    /** Scenario shared by the hourly/filter/effectiveness tests below. */
    private fun hourlyScenario(): Pair<List<ReadinessTestRecord>, List<ReadinessGameRecord>> {
        val greenTest = test(ms("2026-08-10", 9), 90, green)   // Monday
        val redTest = test(ms("2026-08-11", 20), 30, red)      // Tuesday
        val tests = listOf(greenTest, redTest)
        val preTest = gameToRecord(game(ms("2026-08-09", 10) / 1000), "me", tests)!!          // Sunday, before any test, won
        val approved1 = gameToRecord(game(ms("2026-08-10", 9, 20) / 1000), "me", tests)!!     // Monday, in GREEN window, won
        val approved2 = gameToRecord(
            game(ms("2026-08-10", 9, 30) / 1000, whiteResult = "checkmated", blackResult = "win"),
            "me", tests
        )!!                                                                                   // Monday, in GREEN window, lost
        val denied = gameToRecord(game(ms("2026-08-11", 20, 15) / 1000), "me", tests)!!       // Tuesday, fresh RED denies, won
        val expired = gameToRecord(
            game(ms("2026-08-11", 23, 0) / 1000, whiteResult = "checkmated", blackResult = "win"),
            "me", tests
        )!!                                                                                   // Tuesday, window expired, lost
        return tests to listOf(preTest, approved1, approved2, denied, expired)
    }

    @Test
    fun `game filters partition all games`() {
        val (_, games) = hourlyScenario()
        assertEquals(5, games.count { GameFilter.ALL.matches(it) })
        // Post-test = approved + unapproved; pre-test games excluded
        val postTest = games.filter { GameFilter.POST_TEST.matches(it) }
        assertEquals(4, postTest.size)
        assertEquals(
            postTest.size,
            games.count { GameFilter.APPROVED.matches(it) } +
                games.count { GameFilter.UNAPPROVED.matches(it) }
        )
        assertEquals(2, games.count { GameFilter.APPROVED.matches(it) })
        assertEquals(2, games.count { GameFilter.UNAPPROVED.matches(it) })
    }

    @Test
    fun `hourly readiness has 24 slots with per-hour aggregates`() {
        val (tests, _) = hourlyScenario()
        val hourly = computeHourlyReadiness(tests, zone)
        assertEquals(24, hourly.size)
        assertEquals((0..23).toList(), hourly.map { it.hour })
        val h9 = hourly[9]
        assertEquals(1, h9.testCount)
        assertEquals(90.0, h9.avgCcrs, 0.001)
        assertEquals(1, h9.greenCount)
        val h20 = hourly[20]
        assertEquals(1, h20.testCount)
        assertEquals(30.0, h20.avgCcrs, 0.001)
        assertEquals(0, h20.greenCount)
        assertEquals(0, hourly[15].testCount)
    }

    @Test
    fun `hourly win rates respect the game filter`() {
        val (_, games) = hourlyScenario()
        val all = computeHourlyWinRates(games, GameFilter.ALL, zone)
        assertEquals(24, all.size)
        assertEquals(2, all[9].games)
        assertEquals(1, all[9].wins)
        assertEquals(1, all[10].games) // the pre-test game
        assertEquals(50.0, all[9].winRate, 0.001)

        val approved = computeHourlyWinRates(games, GameFilter.APPROVED, zone)
        assertEquals(2, approved[9].games)
        assertEquals(0, approved[10].games) // pre-test game excluded
        assertEquals(0, approved[20].games) // denied game excluded

        val unapproved = computeHourlyWinRates(games, GameFilter.UNAPPROVED, zone)
        assertEquals(1, unapproved[20].games) // denied
        assertEquals(1, unapproved[23].games) // expired
        assertEquals(100.0, unapproved[20].winRate, 0.001)

        val postTest = computeHourlyWinRates(games, GameFilter.POST_TEST, zone)
        assertEquals(4, postTest.sumOf { it.games })
        assertEquals(2, postTest.sumOf { it.wins })
    }

    @Test
    fun `bucket win rates merge four-hour groups in bucket order`() {
        val (_, games) = hourlyScenario()
        val buckets = computeBucketWinRates(games, GameFilter.ALL, zone)
        assertEquals(6, buckets.size)
        assertEquals(listOf(0, 4, 8, 12, 16, 20), buckets.map { it.hour })
        val morning = buckets.first { it.hour == 8 } // hours 8–11: two @9 + one @10
        assertEquals(3, morning.games)
        assertEquals(2, morning.wins) // approved win + pre-test win (approved2 lost)
        val evening = buckets.first { it.hour == 20 } // hours 20–23: denied @20 + expired @23
        assertEquals(2, evening.games)
        assertEquals(1, evening.wins)
        val approvedBuckets = computeBucketWinRates(games, GameFilter.APPROVED, zone)
        assertEquals(2, approvedBuckets.first { it.hour == 8 }.games)
        assertEquals(0, approvedBuckets.first { it.hour == 20 }.games)
    }

    // ── System effectiveness categories ───────────────────────────────────

    @Test
    fun `game categories split pre-test approved denied and expired`() {
        val (tests, games) = hourlyScenario()
        val agg = computeGameCategoryAggregates(games, tests).associateBy { it.category }

        val pre = agg.getValue(GameCategory.PRE_TEST)
        assertEquals(1, pre.games)
        assertEquals(1, pre.wins) // default game() helper is a win
        assertNull(pre.avgCcrsAtPlay)

        val approved = agg.getValue(GameCategory.APPROVED)
        assertEquals(2, approved.games)
        assertEquals(1, approved.wins)
        assertEquals(50.0, approved.winRate, 0.001)
        assertEquals(90.0, approved.avgCcrsAtPlay!!, 0.001)

        val denied = agg.getValue(GameCategory.DENIED)
        assertEquals(1, denied.games)
        assertEquals(1, denied.wins)
        assertEquals(30.0, denied.avgCcrsAtPlay!!, 0.001)

        val expired = agg.getValue(GameCategory.EXPIRED)
        assertEquals(1, expired.games)
        assertEquals(0, expired.wins)
        assertEquals(30.0, expired.avgCcrsAtPlay!!, 0.001)

        // Categories partition all games
        assertEquals(5, agg.values.sumOf { it.games })
    }

    // ── CCRS bands ────────────────────────────────────────────────────────

    @Test
    fun `ccrs bands bucket post-test games by score at play`() {
        val (_, games) = hourlyScenario()
        val bands = computeWinRateByCcrsBand(games)
        assertEquals(listOf("0–39", "40–59", "60–69", "70+"), bands.map { it.label })
        val low = bands.first { it.label == "0–39" }
        assertEquals(2, low.games) // denied + expired, both CCRS 30
        assertEquals(1, low.wins)
        val high = bands.first { it.label == "70+" }
        assertEquals(2, high.games) // both approved, CCRS 90
        assertEquals(1, high.wins)
        // The pre-test game has no CCRS context and is skipped everywhere
        assertEquals(4, bands.sumOf { it.games })
        assertEquals(0, bands.first { it.label == "40–59" }.games)
    }

    // ── Day of week ───────────────────────────────────────────────────────

    @Test
    fun `day of week aggregates tests and games monday first`() {
        val (tests, games) = hourlyScenario()
        val stats = computeDayOfWeekStats(tests, games, zone)
        assertEquals(7, stats.size)
        assertEquals(java.time.DayOfWeek.MONDAY, stats.first().day)
        val monday = stats.first()
        assertEquals(1, monday.testCount)
        assertEquals(90.0, monday.avgCcrs, 0.001)
        assertEquals(2, monday.games)
        assertEquals(1, monday.wins)
        val tuesday = stats[1]
        assertEquals(30.0, tuesday.avgCcrs, 0.001)
        assertEquals(2, tuesday.games)
        assertEquals(1, tuesday.wins)
        val sunday = stats.last()
        assertEquals(0, sunday.testCount)
        assertEquals(1, sunday.games) // the pre-test game (a win)
        assertEquals(1, sunday.wins)
    }

    // ── Speed (bullet/blitz/rapid) filtering ───────────────────────────────

    @Test
    fun `game speeds match the classified time control`() {
        val (_, games) = hourlyScenario()
        // The scenario helper uses the default 180+2 control → all blitz
        assertEquals(5, games.count { GameSpeed.ALL.matches(it) })
        assertEquals(5, games.count { GameSpeed.BLITZ.matches(it) })
        assertEquals(0, games.count { GameSpeed.BULLET.matches(it) })
        assertEquals(0, games.count { GameSpeed.RAPID.matches(it) })
    }

    @Test
    fun `hourly win rates respect the speed filter`() {
        val (tests, games) = hourlyScenario()
        val bullet = gameToRecord(
            game(ms("2026-08-10", 9, 25) / 1000, timeControl = "60+0"),
            "me", tests
        )!!                                                                                  // Monday, GREEN window, won
        val rapid = gameToRecord(
            game(ms("2026-08-10", 9, 35) / 1000, timeControl = "600+0", whiteResult = "checkmated", blackResult = "win"),
            "me", tests
        )!!                                                                                  // Monday, GREEN window, lost
        val mixed = games + bullet + rapid

        val all = computeHourlyWinRates(mixed, GameFilter.ALL, zone, GameSpeed.ALL)
        assertEquals(4, all[9].games) // 2 blitz + bullet + rapid
        assertEquals(2, all[9].wins)

        val blitz = computeHourlyWinRates(mixed, GameFilter.ALL, zone, GameSpeed.BLITZ)
        assertEquals(2, blitz[9].games)
        assertEquals(1, blitz[9].wins)

        val bulletOnly = computeHourlyWinRates(mixed, GameFilter.ALL, zone, GameSpeed.BULLET)
        assertEquals(1, bulletOnly[9].games)
        assertEquals(1, bulletOnly[9].wins)
        assertEquals(100.0, bulletOnly[9].winRate, 0.001)

        val rapidOnly = computeHourlyWinRates(mixed, GameFilter.ALL, zone, GameSpeed.RAPID)
        assertEquals(1, rapidOnly[9].games)
        assertEquals(0, rapidOnly[9].wins)

        // Speed combines with the game-subset filter: approved + bullet is
        // exactly the one bullet game played inside the GREEN window.
        val approvedBullet = computeHourlyWinRates(mixed, GameFilter.APPROVED, zone, GameSpeed.BULLET)
        assertEquals(1, approvedBullet[9].games)
        assertEquals(1, approvedBullet[9].wins)
        assertEquals(0, approvedBullet[10].games) // pre-test game is blitz anyway
    }

    // ── Puzzle telemetry over time ───────────────────────────────────────

    @Test
    fun `puzzle time series skips tests without telemetry and sorts chronologically`() {
        val legacy = test(ms("2026-08-10", 9), 80, green)
            .copy(puzzleTimesSec = emptyList()) // legacy import — no telemetry
        val later = test(ms("2026-08-11", 9), 75, yellow)
            .copy(puzzleTimesSec = listOf(50, 70))
        val earlier = test(ms("2026-08-10", 8), 90, green)
            .copy(puzzleTimesSec = listOf(20, 30, 40))

        val series = computePuzzleTimeSeries(listOf(later, legacy, earlier))

        assertEquals(2, series.size)
        assertEquals(earlier.timestamp, series[0].timestampMs)
        assertEquals(later.timestamp, series[1].timestampMs)
        assertEquals(listOf(20, 30, 40), series[0].timesSec)
        assertEquals(30.0, series[0].avgSec, 0.001)
        assertEquals(20, series[0].bestSec)
        assertEquals(40, series[0].worstSec)
        assertEquals(green, series[0].state)
        assertEquals(90, series[0].ccrs)
    }

    @Test
    fun `rush score series skips zero-score tests and flags new highs`() {
        val aborted = test(ms("2026-08-10", 9), 80, green)
            .copy(rushScore = 0, rushAllTimeHigh = 18) // aborted run — skipped
        val first = test(ms("2026-08-10", 8), 90, green)
            .copy(rushScore = 18, rushStrikes = 0, rushAllTimeHigh = 18)
        val record = test(ms("2026-08-11", 8), 85, green)
            .copy(rushScore = 24, rushStrikes = 1, rushAllTimeHigh = 18)
        val slump = test(ms("2026-08-12", 8), 55, red)
            .copy(rushScore = 12, rushStrikes = 3, rushAllTimeHigh = 24)

        val series = computeRushScoreSeries(listOf(slump, record, aborted, first))

        assertEquals(3, series.size)
        assertEquals(first.timestamp, series[0].timestampMs)
        assertEquals(record.timestamp, series[1].timestampMs)
        assertEquals(slump.timestamp, series[2].timestampMs)
        assertTrue(series[0].isNewHigh)   // 18 ≥ baseline 18
        assertTrue(series[1].isNewHigh)   // 24 ≥ baseline 18 → raised the record
        assertFalse(series[2].isNewHigh)  // 12 < baseline 24
        assertEquals(3, series[2].strikes)
        assertEquals(red, series[2].state)
    }
}
