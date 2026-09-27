package it.frumorn.tratto.googledocs

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import it.frumorn.tratto.backup.temporaneo
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Finisce di aggiornare il documento Google di una nota dopo la chiusura dell'editor, o quando
 * l'app va in secondo piano: parte appena c'e' una rete e continua anche ad app chiusa, come
 * BackupWorker. Un lavoro per nota; uno nuovo prende il posto di quello in coda.
 */
class DocsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val nota = inputData.getString(NOTA) ?: return Result.failure()
        return try {
            GoogleDocs.eseguiInBackground(applicationContext, nota)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Il messaggio per la UI l'ha gia' messo GoogleDocs.
            if (e.temporaneo() && runAttemptCount < MAX_TENTATIVI) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val NOTA = "nota"
        private const val MAX_TENTATIVI = 5

        private fun nome(notaId: String) = "google-docs-$notaId"

        fun accoda(context: Context, notaId: String) {
            val richiesta = OneTimeWorkRequestBuilder<DocsWorker>()
                .setInputData(workDataOf(NOTA to notaId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(nome(notaId), ExistingWorkPolicy.REPLACE, richiesta)
        }

        fun annulla(context: Context, notaId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(nome(notaId))
        }
    }
}
