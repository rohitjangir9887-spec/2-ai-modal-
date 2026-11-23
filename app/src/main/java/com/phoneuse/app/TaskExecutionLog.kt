package com.phoneuse.app

import com.phoneuse.security.SecretRedactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

data class TaskLogEntry(
    val timestampMs: Long,
    val taskId: String,
    val level: String,
    val message: String,
)

/**
 * Ring-buffered execution log that automatically redacts API keys, Bearer headers,
 * passwords, and OTP tokens before retention or UI display.
 */
class TaskExecutionLog(
    private val capacity: Int = 200,
    private val knownSecrets: () -> Collection<String> = { emptyList() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val ring = ArrayDeque<TaskLogEntry>(capacity)
    private val _entries = MutableStateFlow<List<TaskLogEntry>>(emptyList())
    val entries: StateFlow<List<TaskLogEntry>> = _entries.asStateFlow()

    fun append(taskId: String, message: String, level: String = "INFO") {
        val clean = SecretRedactor.redact(message, knownSecrets()).trim()
        if (clean.isEmpty()) return
        val entry = TaskLogEntry(
            timestampMs = clock(),
            taskId = taskId,
            level = level,
            message = clean.take(400),
        )
        synchronized(lock) {
            if (ring.size >= capacity) ring.removeFirst()
            ring.addLast(entry)
            _entries.value = ring.toList()
        }
    }

    fun forTask(taskId: String): List<TaskLogEntry> = synchronized(lock) {
        ring.filter { it.taskId == taskId }
    }

    fun clear() {
        synchronized(lock) {
            ring.clear()
            _entries.value = emptyList()
        }
    }
}
