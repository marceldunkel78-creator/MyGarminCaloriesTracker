package com.example.mygarmincaloriestracker

import androidx.lifecycle.ViewModel
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.common.Barcode
import java.time.LocalDate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

internal sealed interface FoodScanEvent {
    val epoch: Long
    data class Scanned(val date: LocalDate, val code: String, override val epoch: Long = BackupGate.capture()) : FoodScanEvent
    data class Cancelled(val date: LocalDate, override val epoch: Long = BackupGate.capture()) : FoodScanEvent
    data class Failed(val date: LocalDate, override val epoch: Long = BackupGate.capture()) : FoodScanEvent
}

/** The scanner covers the Activity, so Activity-scoped Task listeners can be removed onStop. */
internal class FoodScanViewModel : ViewModel() {
    private val pending = Channel<FoodScanEvent>(Channel.BUFFERED)
    val events = pending.receiveAsFlow()

    fun start(date: LocalDate, task: Task<Barcode>, epoch: Long = BackupGate.capture()) {
        task.addOnSuccessListener { barcode ->
            val code = barcode.rawValue
            pending.trySend(if (code.isNullOrBlank()) FoodScanEvent.Failed(date, epoch) else FoodScanEvent.Scanned(date, code, epoch))
        }.addOnCanceledListener {
            pending.trySend(FoodScanEvent.Cancelled(date, epoch))
        }.addOnFailureListener {
            pending.trySend(FoodScanEvent.Failed(date, epoch))
        }
    }

    override fun onCleared() {
        pending.close()
    }
}
