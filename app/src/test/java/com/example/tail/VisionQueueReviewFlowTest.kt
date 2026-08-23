package com.example.tail

import com.example.tail.data.meal.VisionQueueItem
import com.example.tail.data.meal.VisionQueueRepository
import com.example.tail.data.meal.VisionQueueStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for the Quick Capture fallback flow: items the AI can't act on
 * move to NEEDS_REVIEW (Quick Capture History), survive cleanup, and can be
 * re-queued with a user-assigned habit or deleted with their image.
 */
class VisionQueueReviewFlowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var repo: VisionQueueRepository

    @Before
    fun setUp() {
        repo = VisionQueueRepository(tmp.newFolder("files"))
    }

    /** Enqueues an item and forces its timestamp; returns the stored item. */
    private fun enqueue(ts: Long = System.currentTimeMillis(), habit: String? = "Eat"): VisionQueueItem {
        val created = repo.enqueue("meal_images/${ts}.jpg", habit)
        return repo.updateItem(created.copy(timestamp = ts)) ?: created
    }

    @Test
    fun `markNeedsReview moves item out of pending with note`() {
        val item = enqueue()
        repo.markNeedsReview(item.id, "Not recognised as food")

        val stored = repo.loadAll().first { it.id == item.id }
        assertEquals(VisionQueueStatus.NEEDS_REVIEW, stored.status)
        assertEquals("Not recognised as food", stored.reviewNote)
        // No longer picked up by the worker…
        assertTrue(repo.pendingItems().none { it.id == item.id })
        // …but listed in the review history.
        assertEquals(listOf(item.id), repo.reviewItems().map { it.id })
        assertEquals(1, repo.reviewItemCount())
    }

    @Test
    fun `retryWithHabit requeues with assigned habit and fresh retry budget`() {
        val item = enqueue()
        // Exhaust the retry budget so it lands in review
        repeat(3) { repo.markFailedOrRetry(item.id, "boom") }
        repo.markNeedsReview(item.id, "failed permanently")
        assertTrue(repo.reviewItems().any { it.id == item.id })

        assertTrue(repo.retryWithHabit(item.id, "Eat"))

        val requeued = repo.loadAll().first { it.id == item.id }
        assertEquals(VisionQueueStatus.PENDING, requeued.status)
        assertEquals("Eat", requeued.habitId)
        assertEquals(0, requeued.retryCount)
        assertNull(requeued.errorLog)
        assertNull(requeued.reviewNote)
        assertTrue(repo.pendingItems().any { it.id == item.id })
        assertEquals(0, repo.reviewItemCount())
    }

    @Test
    fun `retryWithHabit only applies to review items`() {
        val item = enqueue()
        assertFalse(repo.retryWithHabit(item.id, "Eat"))
        // Item untouched
        assertEquals(VisionQueueStatus.PENDING, repo.loadAll().first { it.id == item.id }.status)
    }

    @Test
    fun `cleanupOldItems never removes needs review items`() {
        val old = System.currentTimeMillis() - 48 * 60 * 60 * 1000L
        val review = enqueue(ts = old)
        repo.markNeedsReview(review.id, "Not recognised as food")
        val completed = enqueue(ts = old + 1)
        repo.markCompleted(completed.id, "log1")

        repo.cleanupOldItems()

        val ids = repo.loadAll().map { it.id }
        assertTrue("review item must survive cleanup", review.id in ids)
        assertFalse("completed item should be cleaned", completed.id in ids)
    }

    @Test
    fun `deleteReviewItem removes item and image file`() {
        val item = enqueue()
        repo.markNeedsReview(item.id, "Not recognised as food")
        val image = File(tmp.root, "files/${item.imagePath}").apply {
            parentFile.mkdirs()
            writeText("fake-jpeg")
        }
        assertTrue(image.exists())

        assertTrue(repo.deleteReviewItem(item.id))
        assertFalse(image.exists())
        assertTrue(repo.loadAll().none { it.id == item.id })
        assertFalse(repo.deleteReviewItem(item.id)) // already gone
    }

    @Test
    fun `requeueStaleProcessing recovers orphaned processing items`() {
        val item = enqueue()
        repo.markProcessing(item.id)
        assertEquals(VisionQueueStatus.PROCESSING, repo.loadAll().first { it.id == item.id }.status)

        val recovered = repo.requeueStaleProcessing()

        assertEquals(1, recovered)
        assertEquals(VisionQueueStatus.PENDING, repo.loadAll().first { it.id == item.id }.status)
        assertEquals(0, repo.requeueStaleProcessing())
    }

    @Test
    fun `reviewItems sorted newest first`() {
        val older = enqueue(ts = 1000L)
        val newer = enqueue(ts = 2000L)
        repo.markNeedsReview(older.id, "a")
        repo.markNeedsReview(newer.id, "b")

        assertEquals(listOf(newer.id, older.id), repo.reviewItems().map { it.id })
    }

    @Test
    fun `legacy queue json without reviewNote still loads`() {
        // Simulates a pre-upgrade queue file: no reviewNote field anywhere.
        val json = """
            [
              {
                "id": "legacy",
                "timestamp": 123,
                "imagePath": "meal_images/x.jpg",
                "status": "PENDING",
                "habitId": "Eat",
                "retryCount": 0
              }
            ]
        """.trimIndent()
        File(tmp.root, "files/vision_queue.json").apply {
            parentFile.mkdirs()
            writeText(json)
        }

        val pending = repo.pendingItems()
        assertEquals(1, pending.size)
        assertEquals("legacy", pending[0].id)
        assertNull(pending[0].reviewNote)
        assertNull(pending[0].attachToMealLogId)
    }
}
