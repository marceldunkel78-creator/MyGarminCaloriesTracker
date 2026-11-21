package com.example.mygarmincaloriestracker

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class BackupStatus(val busy: Boolean = false, val message: String? = null)

/** Owns operations across configuration changes. No URI permission is retained after completion. */
internal class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableStatus = MutableStateFlow(BackupStatus())
    val status: StateFlow<BackupStatus> = mutableStatus
    val busy: Boolean get() = mutableStatus.value.busy

    fun clearMessage() { mutableStatus.value = mutableStatus.value.copy(message = null) }

    fun start(uri: Uri, importing: Boolean) {
        if (busy || !BackupGate.begin()) return
        mutableStatus.value = BackupStatus(busy = true)
        viewModelScope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    require(uri.scheme == "content") { "Unsupported document URI" }
                    val context = getApplication<Application>()
                    val resolver = context.contentResolver
                    if (importing) {
                        // Providers may block indefinitely: never hold the database lock while reading.
                        val data = (resolver.openInputStream(uri) ?: throw IOException("Cannot open backup")).use { BackupJson.read(it) }
                        currentCoroutineContext().ensureActive()
                        withContext(NonCancellable) {
                            BackupGate.backup { TrackerStore(context).use { it.restoreBackup(data) } }
                        }
                    } else {
                        val snapshot = BackupGate.backup { TrackerStore(context).use { it.backupSnapshot() } }
                        // Fully construct and validate before opening an output, which may truncate it.
                        val bytes = BackupJson.encode(snapshot)
                        (resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot create backup")).use { it.write(bytes); it.flush() }
                    }
                }
                if (importing) "Sicherung wurde vollständig wiederhergestellt." else "Sicherung wurde exportiert. Bewahre die private Datei geschützt auf."
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (importing) "Import konnte nicht abgeschlossen werden. Prüfe deine Daten, bevor du es erneut versuchst." else "Export fehlgeschlagen. Die Zieldatei könnte unvollständig sein; bitte erneut exportieren."
            } finally {
                BackupGate.end()
            }
            mutableStatus.value = BackupStatus(message = message)
        }
    }
}
