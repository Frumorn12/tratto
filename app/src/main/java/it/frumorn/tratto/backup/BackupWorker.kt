package it.frumorn.tratto.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Backup automatico in background. Dove va lo decide [DestinazioneBackup], diverso nelle due
 * versioni: Google Drive nella completa (il token si chiede senza interfaccia: se Google vuole di
 * nuovo il consenso il worker si ferma e lo stato diventa "serve accesso"), una cartella scelta
 * dall'utente nella libera.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        DestinazioneBackup.esegui(applicationContext)
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Il messaggio per la UI l'ha gia' salvato la destinazione.
        if (DestinazioneBackup.daRiprovare(e) && runAttemptCount < MAX_TENTATIVI) Result.retry() else Result.failure()
    }

    companion object {
        // Nomi di quando c'era solo Drive: cambiarli lascerebbe in coda il lavoro gia' pianificato.
        private const val GIORNALIERO = "backup-drive-giornaliero"
        private const val ADESSO = "backup-drive-adesso"
        private const val MAX_TENTATIVI = 5

        /** Rete richiesta: nessuna se la destinazione non la usa (una cartella sul tablet). */
        private fun rete(soloWifi: Boolean) = when {
            !DestinazioneBackup.USA_RETE -> NetworkType.NOT_REQUIRED
            soloWifi -> NetworkType.UNMETERED
            else -> NetworkType.CONNECTED
        }

        /** Pianifica, aggiorna o cancella il backup giornaliero secondo [ImpostazioniBackup]. */
        fun pianifica(context: Context) {
            val s = ImpostazioniBackup.stato(context).value
            val wm = WorkManager.getInstance(context)
            if (!s.automatico || !DestinazioneBackup.collegata(s)) {
                wm.cancelUniqueWork(GIORNALIERO)
                return
            }
            val vincoli = Constraints.Builder()
                .setRequiredNetworkType(rete(s.soloWifi))
                .setRequiresCharging(s.soloInCarica)
                .setRequiresBatteryNotLow(true)
                .build()
            val richiesta = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(vincoli)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(GIORNALIERO, ExistingPeriodicWorkPolicy.UPDATE, richiesta)
        }

        /** "Backup ora": parte appena c'e' una rete qualsiasi. Se uno e' gia' in coda non ne aggiunge un altro. */
        fun backupOra(context: Context) {
            val richiesta = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(rete(soloWifi = false)).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ADESSO, ExistingWorkPolicy.KEEP, richiesta)
        }
    }
}
