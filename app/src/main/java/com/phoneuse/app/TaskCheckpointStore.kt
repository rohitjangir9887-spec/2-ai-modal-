package com.phoneuse.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.phoneuse.security.SecretRedactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

enum class TaskRunStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    WAITING_CONFIRM,
    INTERRUPTED,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == INTERRUPTED
}

data class TaskCheckpoint(
    val taskId: String,
    val conversationId: Long,
    val prompt: String,
    val status: TaskRunStatus,
    val stepCount: Int = 0,
    val activeRole: String = "",
    val activeModel: String = "",
    val lastPackage: String = "",
    val lastError: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs,
)

interface TaskCheckpointBackend {
    fun upsert(checkpoint: TaskCheckpoint)
    fun get(taskId: String): TaskCheckpoint?
    fun listRecent(limit: Int = 40): List<TaskCheckpoint>
    fun markActiveInterrupted(nowMs: Long, reason: String): Int
    fun clear()
}

class InMemoryTaskCheckpointBackend : TaskCheckpointBackend {
    private val map = ConcurrentHashMap<String, TaskCheckpoint>()

    override fun upsert(checkpoint: TaskCheckpoint) {
        map[checkpoint.taskId] = checkpoint
    }

    override fun get(taskId: String): TaskCheckpoint? = map[taskId]

    override fun listRecent(limit: Int): List<TaskCheckpoint> =
        map.values.sortedByDescending { it.updatedAtMs }.take(limit)

    override fun markActiveInterrupted(nowMs: Long, reason: String): Int {
        var count = 0
        for ((id, cp) in map) {
            if (!cp.status.isTerminal) {
                map[id] = cp.copy(status = TaskRunStatus.INTERRUPTED, lastError = reason, updatedAtMs = nowMs)
                count++
            }
        }
        return count
    }

    override fun clear() {
        map.clear()
    }
}

class SqliteTaskCheckpointBackend(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, VERSION), TaskCheckpointBackend {
    override fun onCreate(db: SQLiteDatabase) {
        CREATE_SQL.forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun upsert(checkpoint: TaskCheckpoint) {
        try {
            writableDatabase.execSQL(
                "INSERT INTO task_checkpoints(task_id, conversation_id, prompt, status, step_count, active_role, active_model, last_package, last_error, created_at, updated_at) " +
                    "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(task_id) DO UPDATE SET " +
                    "conversation_id=excluded.conversation_id, prompt=excluded.prompt, status=excluded.status, " +
                    "step_count=excluded.step_count, active_role=excluded.active_role, active_model=excluded.active_model, " +
                    "last_package=excluded.last_package, last_error=excluded.last_error, updated_at=excluded.updated_at",
                arrayOf<Any?>(
                    checkpoint.taskId,
                    checkpoint.conversationId,
                    checkpoint.prompt,
                    checkpoint.status.name,
                    checkpoint.stepCount,
                    checkpoint.activeRole,
                    checkpoint.activeModel,
                    checkpoint.lastPackage,
                    checkpoint.lastError,
                    checkpoint.createdAtMs,
                    checkpoint.updatedAtMs,
                ),
            )
            // Keep table bounded to last 80 checkpoints.
            writableDatabase.execSQL(
                "DELETE FROM task_checkpoints WHERE task_id NOT IN (SELECT task_id FROM task_checkpoints ORDER BY updated_at DESC LIMIT 80)",
            )
        } catch (e: Exception) {
            Log.w("TaskCheckpoint", "upsert failed", e)
        }
    }

    override fun get(taskId: String): TaskCheckpoint? = try {
        readableDatabase.rawQuery(
            "SELECT task_id, conversation_id, prompt, status, step_count, active_role, active_model, last_package, last_error, created_at, updated_at " +
                "FROM task_checkpoints WHERE task_id = ?",
            arrayOf(taskId),
        ).use { c -> if (c.moveToFirst()) readRow(c) else null }
    } catch (e: Exception) {
        Log.w("TaskCheckpoint", "get failed", e)
        null
    }

    override fun listRecent(limit: Int): List<TaskCheckpoint> = try {
        readableDatabase.rawQuery(
            "SELECT task_id, conversation_id, prompt, status, step_count, active_role, active_model, last_package, last_error, created_at, updated_at " +
                "FROM task_checkpoints ORDER BY updated_at DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(readRow(c))
            }
        }
    } catch (e: Exception) {
        Log.w("TaskCheckpoint", "listRecent failed", e)
        emptyList()
    }

    override fun markActiveInterrupted(nowMs: Long, reason: String): Int = try {
        val cv = android.content.ContentValues().apply {
            put("status", TaskRunStatus.INTERRUPTED.name)
            put("last_error", reason)
            put("updated_at", nowMs)
        }
        writableDatabase.update(
            "task_checkpoints",
            cv,
            "status IN (?, ?, ?, ?)",
            arrayOf(
                TaskRunStatus.QUEUED.name,
                TaskRunStatus.RUNNING.name,
                TaskRunStatus.PAUSED.name,
                TaskRunStatus.WAITING_CONFIRM.name,
            ),
        )
    } catch (e: Exception) {
        Log.w("TaskCheckpoint", "markActiveInterrupted failed", e)
        0
    }

    override fun clear() {
        try {
            writableDatabase.delete("task_checkpoints", null, null)
        } catch (e: Exception) {
            Log.w("TaskCheckpoint", "clear failed", e)
        }
    }

    private fun readRow(c: android.database.Cursor): TaskCheckpoint = TaskCheckpoint(
        taskId = c.getString(0),
        conversationId = c.getLong(1),
        prompt = c.getString(2),
        status = TaskRunStatus.entries.firstOrNull { it.name == c.getString(3) } ?: TaskRunStatus.FAILED,
        stepCount = c.getInt(4),
        activeRole = c.getString(5),
        activeModel = c.getString(6),
        lastPackage = c.getString(7),
        lastError = c.getString(8),
        createdAtMs = c.getLong(9),
        updatedAtMs = c.getLong(10),
    )

    companion object {
        const val DB_NAME = "task_checkpoints.db"
        const val VERSION = 1
        val CREATE_SQL = listOf(
            "CREATE TABLE task_checkpoints (" +
                "task_id TEXT PRIMARY KEY, " +
                "conversation_id INTEGER NOT NULL, " +
                "prompt TEXT NOT NULL, " +
                "status TEXT NOT NULL, " +
                "step_count INTEGER NOT NULL DEFAULT 0, " +
                "active_role TEXT NOT NULL DEFAULT '', " +
                "active_model TEXT NOT NULL DEFAULT '', " +
                "last_package TEXT NOT NULL DEFAULT '', " +
                "last_error TEXT, " +
                "created_at INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL)",
            "CREATE INDEX idx_task_checkpoints_updated ON task_checkpoints(updated_at DESC)",
        )
    }
}

/**
 * Scrubbed checkpoint store tracking running/paused/queued/interrupted tasks across step boundaries and restarts.
 */
class TaskCheckpointStore(
    private val backend: TaskCheckpointBackend = InMemoryTaskCheckpointBackend(),
    private val knownSecrets: () -> Collection<String> = { emptyList() },
) {
    private val _recent = MutableStateFlow(backend.listRecent())
    val recent: StateFlow<List<TaskCheckpoint>> = _recent.asStateFlow()

    fun save(checkpoint: TaskCheckpoint) {
        val secrets = knownSecrets()
        val scrubbed = checkpoint.copy(
            prompt = SecretRedactor.redact(checkpoint.prompt, secrets).take(320),
            lastError = checkpoint.lastError?.let { SecretRedactor.redact(it, secrets).take(320) },
        )
        backend.upsert(scrubbed)
        _recent.value = backend.listRecent()
    }

    fun get(taskId: String): TaskCheckpoint? = backend.get(taskId)

    fun markActiveInterrupted(nowMs: Long = System.currentTimeMillis(), reason: String): Int {
        val changed = backend.markActiveInterrupted(nowMs, SecretRedactor.redact(reason, knownSecrets()))
        if (changed > 0) _recent.value = backend.listRecent()
        return changed
    }

    fun clear() {
        backend.clear()
        _recent.update { emptyList() }
    }
}
