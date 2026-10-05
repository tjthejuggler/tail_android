package com.example.tail

import com.example.tail.data.movie.BridgeMovie
import com.example.tail.data.movie.MovieSession
import com.example.tail.notify.HabitAsks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Tests for [HabitAsks.movieSuggestionEntryTime] — the watch-time pre-fill
 * used when a movie is added MANUALLY from the habit-tap suggestion dialog.
 * The dialog wheel must start at the movie's last session start (when the
 * movie was watched on the dialog's date) and stay null otherwise, so the
 * dialog default (current time / noon) is never mistaken for a watch time.
 */
class MovieSuggestionEntryTimeTest {

    private val today = LocalDate.now()

    private fun movie(
        title: String = "Dune: Part Two",
        date: String = today.toString(),
        lastWatched: String = "$date 21:30:00",
        sessions: List<MovieSession> = emptyList()
    ) = BridgeMovie(
        title = title,
        season = null,
        episode = null,
        date = date,
        lastWatched = lastWatched,
        sessions = sessions,
        totalWatchMin = 166
    )

    @Test
    fun `today's movie pre-fills the last session start time`() {
        // Session started at 21:07:51 today (unix for the system zone).
        val startLdt = today.atTime(21, 7, 51)
        val startUnix = startLdt.atZone(ZoneId.systemDefault()).toEpochSecond()
        val m = movie(
            sessions = listOf(
                MovieSession(
                    start = "$today 21:07:51",
                    end = "$today 22:45:00",
                    startUnix = startUnix,
                    endUnix = startUnix + 5800,
                    durationMin = 96
                )
            )
        )
        assertEquals(LocalTime.of(21, 7, 51), HabitAsks.movieSuggestionEntryTime(m, today))
    }

    @Test
    fun `latest session wins when sessions are unordered`() {
        val earlyLdt = today.atTime(10, 0, 0)
        val lateLdt = today.atTime(23, 15, 0)
        val early = earlyLdt.atZone(ZoneId.systemDefault()).toEpochSecond()
        val late = lateLdt.atZone(ZoneId.systemDefault()).toEpochSecond()
        val m = movie(
            sessions = listOf(
                MovieSession("a", "b", late, late + 60, 1),   // newest first
                MovieSession("c", "d", early, early + 60, 1)
            )
        )
        assertEquals(LocalTime.of(23, 15), HabitAsks.movieSuggestionEntryTime(m, today))
    }

    @Test
    fun `no sessions falls back to lastWatched time`() {
        val m = movie(lastWatched = "$today 21:30:00", sessions = emptyList())
        assertEquals(LocalTime.of(21, 30), HabitAsks.movieSuggestionEntryTime(m, today))
    }

    @Test
    fun `movie watched on a different day yields null so default time is kept`() {
        val yesterday = today.minusDays(1)
        val startUnix = yesterday.atTime(22, 0, 0)
            .atZone(ZoneId.systemDefault()).toEpochSecond()
        val m = movie(
            date = yesterday.toString(),
            lastWatched = "$yesterday 22:00:00",
            sessions = listOf(MovieSession("s", "e", startUnix, startUnix + 60, 1))
        )
        // Dialog is for TODAY; yesterday's watch time must NOT pre-fill it.
        assertNull(HabitAsks.movieSuggestionEntryTime(m, today))
    }

    @Test
    fun `movie with no usable time data yields null`() {
        val m = movie(lastWatched = "", sessions = emptyList())
        assertNull(HabitAsks.movieSuggestionEntryTime(m, today))
    }

    @Test
    fun `garbage lastWatched with unix-less sessions yields null`() {
        val m = movie(
            lastWatched = "not-a-datetime",
            sessions = listOf(MovieSession("s", "e", 0, null, null)) // startUnix=0
        )
        assertNull(HabitAsks.movieSuggestionEntryTime(m, today))
    }
}
