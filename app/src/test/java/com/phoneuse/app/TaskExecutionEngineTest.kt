package com.phoneuse.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

class TaskExecutionEngineTest {
    @Test
    fun queueEnforcesSingleActiveExecutorAndFifoOrder() {
        val q = TaskQueueManager()
        val first = q.enqueueOrAcquire(1L, "First task")
        assertTrue(first is TaskQueueManager.EnqueueResult.StartedImmediately)
        assertEquals("First task", q.activeTask.value?.prompt)

        val second = q.enqueueOrAcquire(1L, "Second task")
        val third = q.enqueueOrAcquire(2L, "Third task")
        assertTrue(second is TaskQueueManager.EnqueueResult.Queued)
        assertTrue(third is TaskQueueManager.EnqueueResult.Queued)
        assertEquals(2, q.queuedTasks.value.size)

        // Releasing active task pops FIFO ("Second task")
        val next = q.releaseAndPopNext()
        assertEquals("Second task", next?.prompt)
        assertEquals("Second task", q.activeTask.value?.prompt)
        assertEquals(listOf("Third task"), q.queuedTasks.value.map { it.prompt })

        // Cancel remaining queued item
        val thirdId = q.queuedTasks.value.single().id
        assertTrue(q.cancelQueued(thirdId))
        assertTrue(q.queuedTasks.value.isEmpty())
    }

    @Test
    fun pauseAndResumeToggleOnlyWhenTaskIsActive() {
        val q = TaskQueueManager()
        assertFalse(q.togglePaused())
        q.enqueueOrAcquire(1L, "Active")
        assertTrue(q.togglePaused())
        assertTrue(q.paused.value)
        assertFalse(q.togglePaused())
        assertFalse(q.paused.value)
        q.releaseActiveOnly()
        assertNull(q.activeTask.value)
    }

    @Test
    fun checkpointStoreScrubsSecretsAndMarksInterruptedOnRestart() {
        val store = TaskCheckpointStore(
            backend = InMemoryTaskCheckpointBackend(),
            knownSecrets = { listOf("nvapi-secret-998877") },
        )
        store.save(
            TaskCheckpoint(
                taskId = "t1",
                conversationId = 10L,
                prompt = "Use Bearer nvapi-secret-998877 to check settings",
                status = TaskRunStatus.RUNNING,
                stepCount = 3,
                lastError = "password=hunter2 failed",
            ),
        )
        val saved = store.get("t1")
        assertNotNull(saved)
        assertFalse(saved!!.prompt.contains("nvapi-secret-998877"))
        assertFalse(saved.lastError!!.contains("hunter2"))

        val changed = store.markActiveInterrupted(nowMs = 999_999L, reason = "App stopped")
        assertEquals(1, changed)
        assertEquals(TaskRunStatus.INTERRUPTED, store.get("t1")?.status)
    }

    @Test
    fun taskExecutionLogRedactsSensitiveTokensAndRespectsCapacity() {
        val log = TaskExecutionLog(capacity = 3)
        log.append("t1", "Bearer nvapi-xyz1234567890")
        log.append("t1", "otp=654321")
        log.append("t1", "Step 3 completed")
        log.append("t1", "Step 4 completed")
        val entries = log.entries.value
        assertEquals(3, entries.size)
        assertFalse(entries.any { it.message.contains("nvapi-xyz") || it.message.contains("654321") })
        assertEquals("Step 4 completed", entries.last().message)
    }

    @Test
    fun sqliteCheckpointSchemaCreatesAndUpsertsCleanly() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { st ->
                SqliteTaskCheckpointBackend.CREATE_SQL.forEach { st.execute(it) }
                st.execute(
                    "INSERT INTO task_checkpoints(task_id, conversation_id, prompt, status, step_count, created_at, updated_at) " +
                        "VALUES('row1', 1, 'Open calendar', 'RUNNING', 2, 100, 200)",
                )
            }
            conn.createStatement().use { st ->
                st.executeQuery("SELECT status, step_count FROM task_checkpoints WHERE task_id='row1'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("RUNNING", rs.getString(1))
                    assertEquals(2, rs.getInt(2))
                }
            }
        }
    }
}
