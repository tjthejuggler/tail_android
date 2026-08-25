package com.example.tail

import android.app.Application
import android.util.Log
import com.example.tail.data.meal.QcDiag

/**
 * Application entry point.
 *
 * Single responsibility: attach the [QcDiag] file-backed diagnostics logger
 * at the earliest possible moment (process start, before any activity,
 * worker, or receiver runs) so every quick-capture / vision-pipeline event
 * is persisted to `files/qc_diag/qc_diag.log` during NORMAL phone usage —
 * no adb attached, no special steps. The file survives reboots and is
 * retrieved later with:
 *
 * ```
 * adb exec-out run-as com.example.tail cat files/qc_diag/qc_diag.log
 * ```
 */
class TailApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            QcDiag.attach(this)
        } catch (t: Throwable) {
            Log.e("QC_DIAG", "│ INIT │ QcDiag attach failed — file logging disabled", t)
        }
    }
}
