package it.frumorn.tratto.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Écrit une archive d'auto-export, avec debounce via [ExistingWorkPolicy.REPLACE]. */
class AutoExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        if (!AutoExport.attivo(applicationContext)) return Result.success()
        AutoExport.esegui(applicationContext)
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e is CartellaNonRaggiungibile || e is BackupNonValido) Result.failure()
        else if (runAttemptCount < MAX_TENTATIVI) Result.retry()
        else Result.failure()
    }

    companion object {
        private const val LAVORO = "auto-export"
        private const val MAX_TENTATIVI = 3
        /** Délai de regroupement des sauvegardes note. */
        const val DEBOUNCE_SEC = 8L

        fun pianifica(context: Context, ritardoSec: Long = DEBOUNCE_SEC) {
            if (!AutoExport.attivo(context)) return
            val richiesta = OneTimeWorkRequestBuilder<AutoExportWorker>()
                .setInitialDelay(ritardoSec, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(LAVORO, ExistingWorkPolicy.REPLACE, richiesta)
        }

        fun subito(context: Context) {
            if (!AutoExport.attivo(context)) return
            val richiesta = OneTimeWorkRequestBuilder<AutoExportWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(LAVORO, ExistingWorkPolicy.REPLACE, richiesta)
        }

        fun annulla(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(LAVORO)
        }
    }
}
