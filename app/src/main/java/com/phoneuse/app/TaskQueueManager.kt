package com.phoneuse.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.UUID

data class QueuedTask(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: Long?,
    val prompt: String,
    val source: Source = Source.APP,
    val enqueuedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Single-active-executor FIFO task queue so new user prompts never interrupt or overwrite
 * a running phone automation task.
 */
class TaskQueueManager(
    private val maxQueueSize: Int = 20,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val fifo = ArrayDeque<QueuedTask>()

    private val _activeTask = MutableStateFlow<QueuedTask?>(null)
    val activeTask: StateFlow<QueuedTask?> = _activeTask.asStateFlow()

    private val _queuedTasks = MutableStateFlow<List<QueuedTask>>(emptyList())
    val queuedTasks: StateFlow<List<QueuedTask>> = _queuedTasks.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    fun tryAcquireActive(task: QueuedTask): Boolean = synchronized(lock) {
        if (_activeTask.value != null) return false
        _paused.value = false
        _activeTask.value = task
        true
    }

    fun enqueueOrAcquire(
        conversationId: Long?,
        prompt: String,
        source: Source = Source.APP,
    ): EnqueueResult {
        val task = QueuedTask(
            conversationId = conversationId,
            prompt = prompt.trim(),
            source = source,
            enqueuedAtMs = clock(),
        )
        synchronized(lock) {
            if (_activeTask.value == null && fifo.isEmpty()) {
                _paused.value = false
                _activeTask.value = task
                return EnqueueResult.StartedImmediately(task)
            }
            if (fifo.size >= maxQueueSize) {
                return EnqueueResult.QueueFull
            }
            fifo.addLast(task)
            _queuedTasks.value = fifo.toList()
            return EnqueueResult.Queued(task, position = fifo.size)
        }
    }

    /** Enqueues [task] strictly behind any running task. */
    fun enqueue(
        conversationId: Long?,
        prompt: String,
        source: Source = Source.APP,
    ): QueuedTask? = synchronized(lock) {
        if (fifo.size >= maxQueueSize) return null
        val task = QueuedTask(
            conversationId = conversationId,
            prompt = prompt.trim(),
            source = source,
            enqueuedAtMs = clock(),
        )
        fifo.addLast(task)
        _queuedTasks.value = fifo.toList()
        task
    }

    /** Releases the active executor slot and pops the next queued task (if any) without releasing ownership if popped. */
    fun releaseAndPopNext(): QueuedTask? = synchronized(lock) {
        _paused.value = false
        val next = fifo.pollFirst()
        _activeTask.value = next
        _queuedTasks.value = fifo.toList()
        next
    }

    fun releaseActiveOnly() = synchronized(lock) {
        _paused.value = false
        _activeTask.value = null
    }

    fun popNextIfIdle(): QueuedTask? = synchronized(lock) {
        if (_activeTask.value != null) return null
        val next = fifo.pollFirst() ?: return null
        _paused.value = false
        _activeTask.value = next
        _queuedTasks.value = fifo.toList()
        next
    }

    fun cancelQueued(taskId: String): Boolean = synchronized(lock) {
        val removed = fifo.removeIf { it.id == taskId }
        if (removed) _queuedTasks.value = fifo.toList()
        removed
    }

    fun clearQueue(): Int = synchronized(lock) {
        val n = fifo.size
        fifo.clear()
        _queuedTasks.value = emptyList()
        n
    }

    fun setPaused(paused: Boolean): Boolean = synchronized(lock) {
        if (_activeTask.value == null && paused) return false
        _paused.value = paused
        true
    }

    fun togglePaused(): Boolean = synchronized(lock) {
        if (_activeTask.value == null) return false
        val next = !_paused.value
        _paused.value = next
        next
    }

    sealed interface EnqueueResult {
        data class StartedImmediately(val task: QueuedTask) : EnqueueResult
        data class Queued(val task: QueuedTask, val position: Int) : EnqueueResult
        data object QueueFull : EnqueueResult
    }
}
