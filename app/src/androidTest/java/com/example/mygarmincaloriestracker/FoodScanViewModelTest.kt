package com.example.mygarmincaloriestracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.mlkit.vision.barcode.common.Barcode
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoodScanViewModelTest {
    @Test fun resultIsDeliveredWhenCollectorStartsAfterTaskCompletes() = runBlocking {
        val source = TaskCompletionSource<Barcode>()
        val model = FoodScanViewModel()
        val date = LocalDate.of(2026, 1, 1)
        model.start(date, source.task)
        source.setException(IllegalStateException("Scanner unavailable"))
        val event = withTimeout(5000) { model.events.first() }
        assertEquals(FoodScanEvent.Failed(date), event)
    }
}
