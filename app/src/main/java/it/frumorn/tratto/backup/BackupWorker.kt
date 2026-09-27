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
 * Backup su Drive in background. Il token si chiede senza interfaccia: se Google vuole di nuovo il
 * consenso il worker si ferma e lo stato diventa "serve accesso" (lo mostra la UI).
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        DriveBackup(applicationContext).sincronizza()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (_: DriveBackup.AccessoNecessario) {
        Result.failure()
    } catch (e: Exception) {
        // Il messaggio per la UI l'ha gia' salvato sincronizza().
        if (e.temporaneo() && runAttemptCount < MAX_TENTATIVI) Result.retry() else Result.failure()
    }

    companion object {
        private const val GIORNALIERO = "backup-drive-giornaliero"
        private const val ADESSO = "backup-drive-adesso"
        private const val MAX_TENTATIVI = 5

        /** Pianifica, aggiorna o cancella il backup giornaliero secondo [ImpostazioniBackup]. */
        fun pianifica(context: Context) {
            val s = ImpostazioniBackup.stato(context).value
            val wm = WorkManager.getInstance(context)
            if (!s.automatico || s.account == null) {
                wm.cancelUniqueWork(GIORNALIERO)
                return
            }
            val vincoli = Constraints.Builder()
                .setRequiredNetworkType(if (s.soloWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
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
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ADESSO, ExistingWorkPolicy.KEEP, richiesta)
        }
    }
}
