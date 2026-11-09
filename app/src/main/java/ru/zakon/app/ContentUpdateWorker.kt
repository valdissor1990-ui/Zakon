package ru.zakon.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Performs content refresh without blocking app startup or requiring the user to open Settings. */
class ContentUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = LegalStore(applicationContext)
        return try {
            updateContentIfAvailable(applicationContext, store)
            Result.success()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Exception) {
            // Invalid signatures, malformed releases and incompatible content must not be retried endlessly.
            Result.failure()
        } finally {
            store.close()
        }
    }
}

object ContentUpdateScheduler {
    private const val ONE_TIME_NAME = "zakon-content-update-on-launch"
    private const val PERIODIC_NAME = "zakon-content-update-periodic"

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val initial = OneTimeWorkRequestBuilder<ContentUpdateWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            ONE_TIME_NAME,
            ExistingWorkPolicy.KEEP,
            initial
        )

        val periodic = PeriodicWorkRequestBuilder<ContentUpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )
    }
}
