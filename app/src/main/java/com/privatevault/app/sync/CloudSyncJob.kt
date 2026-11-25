package com.privatevault.app.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * H4: Google Drive passes while Nuvori is closed. A pass reads and writes only the transport store, so
 * it runs while the vault is locked: it uploads this device's waiting changes and stores what other
 * devices sent for the next unlock. Uses the system job scheduler, which needs no extra library.
 *
 * - [scheduleSoon]: right after a change, in case Android ends Nuvori before the pass finishes.
 * - [scheduleRetry]: after a pass failed for a reason that may pass (offline, Google busy).
 * - [schedulePeriodic]: every 15 minutes or so while Drive is connected, unless the check interval
 *   is "Sync now only".
 */
class CloudSyncJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            // M9: the periodic check also brings back automatic Wi-Fi sync that Android ended, where
            // Android allows the start; a refusal just waits for the next open or reboot.
            if (params.jobId == PERIODIC) LanSyncService.restartIfOn(applicationContext)
            val done = runCatching { CloudSyncRunner.runInBackground(applicationContext) }.getOrDefault(false)
            // A periodic job comes back on its own; a one-time job that failed asks again with backoff.
            jobFinished(params, !done && params.jobId != PERIODIC)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return params.jobId != PERIODIC
    }

    companion object {
        private const val SOON = 4_101
        private const val RETRY = 4_102
        private const val PERIODIC = 4_103
        private const val RETRY_AFTER_MS = 5L * 60 * 1000
        private const val PERIOD_MS = 15L * 60 * 1000

        private fun scheduler(context: Context): JobScheduler? =
            context.getSystemService(JobScheduler::class.java)

        private fun builder(context: Context, id: Int) =
            JobInfo.Builder(id, ComponentName(context, CloudSyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)

        private fun schedule(context: Context, job: JobInfo) {
            runCatching { scheduler(context)?.schedule(job) }
        }

        /** Asks for a pass as soon as there is a network; an expedited job where Android allows one. */
        fun scheduleSoon(context: Context) {
            val app = context.applicationContext
            if (!driveConnected(app)) return
            // The expedited quota can run out; a plain job with the same ID then runs it a little later.
            val expedited = Build.VERSION.SDK_INT >= 31 && runCatching {
                scheduler(app)?.schedule(builder(app, SOON).setExpedited(true).build()) == JobScheduler.RESULT_SUCCESS
            }.getOrDefault(false)
            if (!expedited) schedule(app, builder(app, SOON).build())
        }

        /** The pass that [scheduleSoon] stood in for has finished. */
        fun cancelSoon(context: Context) {
            runCatching { scheduler(context.applicationContext)?.cancel(SOON) }
        }

        fun scheduleRetry(context: Context) {
            val app = context.applicationContext
            if (!driveConnected(app)) return
            schedule(app, builder(app, RETRY).setMinimumLatency(RETRY_AFTER_MS).build())
        }

        /** Starts or stops the periodic check to match the Drive connection and the chosen interval. */
        fun schedulePeriodic(context: Context) {
            val app = context.applicationContext
            CloudSyncRunner.loadCheckInterval(app)
            if (!driveConnected(app) || CloudSyncRunner.checkInterval.value == 0L) {
                runCatching { scheduler(app)?.cancel(PERIODIC) }
                return
            }
            if (scheduler(app)?.getPendingJob(PERIODIC) != null) return
            schedule(app, builder(app, PERIODIC).setPeriodic(PERIOD_MS).setPersisted(true).build())
        }

        fun cancelAll(context: Context) {
            val scheduler = scheduler(context.applicationContext) ?: return
            listOf(SOON, RETRY, PERIODIC).forEach { runCatching { scheduler.cancel(it) } }
        }

        private fun driveConnected(context: Context): Boolean =
            runCatching { LanSyncService.store(context).cloudState().enabled }.getOrDefault(false)
    }
}
