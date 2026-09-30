package com.algoritmonatural.naturaguard.scan

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Verificacao periodica em segundo plano (minimo do Android: 15 minutos). */
class ScanWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        Scanner.run(applicationContext)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private const val NAME = "naturaguard-scan"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequest.Builder(ScanWorker::class.java, 15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
