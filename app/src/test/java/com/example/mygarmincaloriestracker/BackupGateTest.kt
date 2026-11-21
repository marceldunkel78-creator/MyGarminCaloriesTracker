package com.example.mygarmincaloriestracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupGateTest {
    @Test fun delayedWriteIsRejectedAcrossBackupEvenWhenItWaitedForLock() {
        val epoch = BackupGate.capture()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val attempted = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val holder = executor.submit {
                BackupGate.backup {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val oldWrite = executor.submit<Boolean> {
                attempted.countDown()
                try { BackupGate.write(epoch) { true } } catch (_: IllegalStateException) { false }
            }
            assertTrue(attempted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(BackupGate.begin())
            assertEquals(false, BackupGate.isCurrent(epoch))
            release.countDown()
            holder.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(false, oldWrite.get(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            release.countDown()
            BackupGate.end()
            executor.shutdownNow()
        }
        assertEquals(false, BackupGate.isCurrent(epoch))
        assertTrue(BackupGate.isCurrent(BackupGate.capture()))
    }

    @Test fun backupInvalidatesWritesCapturedBeforeItStarted() {
        val epoch = BackupGate.capture()
        assertTrue(BackupGate.begin())
        try {
            assertThrows(IllegalStateException::class.java) { BackupGate.write(epoch) { } }
            assertEquals(42, BackupGate.backup { 42 })
        } finally {
            BackupGate.end()
        }
        assertThrows(IllegalStateException::class.java) { BackupGate.write(epoch) { } }
        assertEquals(7, BackupGate.write(BackupGate.capture()) { 7 })
    }
}
