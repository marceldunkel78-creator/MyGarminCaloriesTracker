package com.example.mygarmincaloriestracker

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Blocks late writes from an old Activity or an in-flight Health Connect read across restore. */
internal object BackupGate {
    private val lock = ReentrantLock()
    private var generation = 0L
    @Volatile var busy: Boolean = false
        private set

    @Synchronized fun capture(): Long = generation

    @Synchronized fun isCurrent(epoch: Long): Boolean = !busy && epoch == generation

    @Synchronized fun begin(): Boolean {
        if (busy) return false
        busy = true
        generation++
        return true
    }

    @Synchronized fun end() { busy = false }

    fun <T> write(epoch: Long, operation: () -> T): T = lock.withLock {
        synchronized(this) { check(!busy && epoch == generation) { "Write superseded by backup" } }
        operation()
    }

    fun <T> backup(operation: () -> T): T = lock.withLock { operation() }
}
