package com.example.tail.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.tail.data.SettingsRepository
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

private const val TAG = "GoogleDriveManager"

/** OAuth scope string used for all Drive REST calls. */
private const val DRIVE_SCOPE = "oauth2:https://www.googleapis.com/auth/drive.file"

/** File name prefix for backups stored in Google Drive. */
const val GDRIVE_BACKUP_PREFIX = "tail_gdrive_backup_"

/** One backup file listed from Google Drive. */
data class GDriveBackupEntry(
    val fileId: String,
    val name: String,
    val sizeBytes: Long,
    val createdTime: String
)

/** Settings exposed to the Google Drive section of the Settings UI. */
data class GDriveConfig(
    val autoEnabled: Boolean = false,
    val accountName: String = "",
    val lastBackupDate: String = ""
)

/**
 * Result of the sign-in activity: either the signed-in [account] or an
 * actionable [errorCode]/[errorMessage]. Code 10 (DEVELOPER_ERROR) means the
 * app's package name + signing SHA-1 is not registered in Google Cloud
 * Console — the account picker opens, an account is chosen, then Google
 * rejects the app.
 */
data class SignInOutcome(
    val account: GoogleSignInAccount? = null,
    val errorCode: Int? = null,
    val errorMessage: String? = null
) {
    val cancelled: Boolean
        get() = errorCode == 13 /* CommonStatusCodes.CANCEL */ ||
                errorCode == GoogleSignInStatusCodes.SIGN_IN_CANCELLED
}

/**
 * Google Drive backup transport built on Google Sign-In (play-services-auth)
 * plus the Drive REST v3 API over [HttpURLConnection] — no Google API client
 * libraries needed.
 *
 * Flow:
 *  1. The Settings UI launches [signInIntent]; the result is handled by
 *     [handleSignInResult], which persists the account name.
 *  2. An OAuth access token for the `drive.file` scope is fetched on demand
 *     via [GoogleAuthUtil.getToken] (cached/refreshed by Play Services).
 *  3. Uploads use the exact same [BackupBundle] JSON that SAF exports write
 *     ([BackupManager.writeBackupJson]), so a Drive backup is byte-identical
 *     in format to a local one and can be restored anywhere.
 *  4. Daily auto-backup mirrors [AutoBackupManager]: on the first launch of
 *     each day, if enabled + signed in, one `tail_gdrive_backup_YYYY-MM-DD.json`
 *     is uploaded (same-day file is replaced, not duplicated).
 */
class GoogleDriveManager(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val backupManager: BackupManager
) {

    /** Live view of the Drive-backup settings for the Settings UI. */
    val configFlow: Flow<GDriveConfig> = settingsRepo.settingsFlow.map {
        GDriveConfig(
            autoEnabled = it.gdriveAutoEnabled,
            accountName = it.gdriveAccountName,
            lastBackupDate = it.gdriveLastBackupDate
        )
    }

    private fun signInClient(): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope("https://www.googleapis.com/auth/drive.file"))
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    /** Intent to launch for the "Sign in with Google" flow. */
    fun signInIntent(): Intent = signInClient().signInIntent

    /** The account currently signed in on this device (silent), or null. */
    fun currentAccount(): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    /**
     * Handles the result of the sign-in activity. Persists the account name
     * on success. Returns a [SignInOutcome] whose [SignInOutcome.errorCode]
     * distinguishes cancellation from real failures (code 10 =
     * DEVELOPER_ERROR: package/SHA-1 not registered in Google Cloud Console).
     */
    suspend fun handleSignInResult(data: Intent?): SignInOutcome {
        val account = try {
            GoogleSignIn.getSignedInAccountFromIntent(data).result
        } catch (e: ApiException) {
            val name = try {
                GoogleSignInStatusCodes.getStatusCodeString(e.statusCode)
            } catch (_: Exception) {
                "code ${e.statusCode}"
            }
            val msg = "Sign-in failed (code ${e.statusCode}: $name)"
            Log.w(TAG, "$msg — ${e.message}")
            return SignInOutcome(errorCode = e.statusCode, errorMessage = msg)
        } catch (e: Exception) {
            Log.w(TAG, "Sign-in failed: ${e.message}")
            return SignInOutcome(errorMessage = "Sign-in failed: ${e.message}")
        }
        if (!account.email.isNullOrBlank()) {
            settingsRepo.saveGdriveAccountName(account.email!!)
        }
        return SignInOutcome(account = account)
    }

    /** Signs out of Google and clears the stored account name. */
    suspend fun signOut() {
        settingsRepo.saveGdriveAccountName("")
        settingsRepo.saveGdriveAutoEnabled(false)
        try {
            signInClient().signOut()
        } catch (e: Exception) {
            Log.w(TAG, "signOut failed: ${e.message}")
        }
    }

    /** Persists the auto-backup toggle. */
    suspend fun saveAutoEnabled(enabled: Boolean) =
        settingsRepo.saveGdriveAutoEnabled(enabled)

    // ─────────────────────────────────────────────────────────────────────
    //  Drive REST helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Intent that re-requests Drive permission, set when the last token fetch
     * threw [UserRecoverableAuthException] (consent needed). The UI should
     * launch it, then retry the failed operation.
     */
    @Volatile
    var pendingConsentIntent: Intent? = null
        private set

    /** Fetches (and caches) an access token for the signed-in account. */
    private fun accessToken(): String? {
        val account = currentAccount()?.account ?: return null
        pendingConsentIntent = null
        return try {
            GoogleAuthUtil.getToken(context, account, DRIVE_SCOPE)
        } catch (e: UserRecoverableAuthException) {
            // Consent is required — surface the recovery intent to the UI.
            pendingConsentIntent = e.intent
            Log.w(TAG, "Token needs user consent: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Token fetch failed: ${e.message}")
            null
        }
    }

    private fun clearToken(token: String) {
        try {
            GoogleAuthUtil.clearToken(context, token)
        } catch (_: Exception) {
        }
    }

    private fun http(url: String, method: String, token: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    private fun errorBody(conn: HttpURLConnection): String = try {
        conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(300) ?: ""
    } catch (_: Exception) {
        ""
    }

    /**
     * Uploads (or replaces) the backup file with the given [fileName].
     * The JSON body is produced by [writeJson] (e.g.
     * [BackupManager.writeBackupJson]) and STAGED into a cache temp file
     * first, so the multi-MB body never lives on the Java heap and the exact
     * Content-Length is known for fixed-length streaming upload.
     * Returns the Drive fileId on success.
     */
    suspend fun uploadBackupJson(
        fileName: String,
        writeJson: suspend (OutputStream) -> Unit
    ): Result<String> =
        withContext(Dispatchers.IO) {
            var token = accessToken() ?: return@withContext Result.failure(
                IllegalStateException(
                    if (pendingConsentIntent != null) {
                        "Google needs you to approve Drive access — try again to grant it"
                    } else {
                        "Not signed in (or Drive consent revoked) — sign in again"
                    }
                )
            )
            val tmp = File(context.cacheDir, "gdrive_upload_${System.currentTimeMillis()}.json")
            try {
                // Stage the JSON to disk (memory-flat serialization).
                try {
                    tmp.outputStream().use { writeJson(it) }
                } catch (e: Exception) {
                    return@withContext Result.failure(
                        IllegalStateException("Building backup JSON failed: ${e.message}", e)
                    )
                }
                val bodyLen = tmp.length()

                // If today's file already exists, replace its content instead
                // of creating a duplicate.
                val existing = listBackupsWithToken(token).firstOrNull { it.name == fileName }
                val fileId: String = if (existing != null) {
                    val conn = http(
                        "https://www.googleapis.com/upload/drive/v3/files/${existing.fileId}?uploadType=media",
                        "PATCH", token
                    )
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setFixedLengthStreamingMode(bodyLen)
                    conn.outputStream.use { out ->
                        tmp.inputStream().use { it.copyTo(out) }
                    }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        conn.disconnect()
                        return@withContext Result.failure(
                            IllegalStateException("Drive update failed (HTTP $code): ${errorBody(conn)}")
                        )
                    }
                    val id = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        .optString("id", "")
                    conn.disconnect()
                    id
                } else {
                    val boundary = "tail" + System.currentTimeMillis()
                    val meta = JSONObject().put("name", fileName).toString()
                    val before = ("--$boundary\r\n" +
                            "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
                            "$meta\r\n" +
                            "--$boundary\r\n" +
                            "Content-Type: application/json\r\n\r\n").toByteArray(Charsets.UTF_8)
                    val after = "\r\n--$boundary--".toByteArray(Charsets.UTF_8)

                    val conn = http(
                        "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart",
                        "POST", token
                    )
                    conn.doOutput = true
                    conn.setRequestProperty(
                        "Content-Type", "multipart/related; boundary=$boundary"
                    )
                    conn.setFixedLengthStreamingMode(before.size + bodyLen + after.size)
                    conn.outputStream.use { out ->
                        out.write(before)
                        tmp.inputStream().use { it.copyTo(out) }
                        out.write(after)
                    }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        conn.disconnect()
                        return@withContext Result.failure(
                            IllegalStateException("Drive upload failed (HTTP $code): ${errorBody(conn)}")
                        )
                    }
                    val id = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        .optString("id", "")
                    conn.disconnect()
                    id
                }
                if (fileId.isBlank()) {
                    Result.failure(IllegalStateException("Drive returned no file id"))
                } else {
                    Result.success(fileId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "upload failed", e)
                Result.failure(e)
            } finally {
                runCatching { tmp.delete() }
            }
        }

    /** Lists Tail backups in Drive (newest first). */
    suspend fun listBackups(): Result<List<GDriveBackupEntry>> = withContext(Dispatchers.IO) {
        val token = accessToken()
            ?: return@withContext Result.failure(
                IllegalStateException(
                    if (pendingConsentIntent != null) {
                        "Google needs you to approve Drive access — try again to grant it"
                    } else {
                        "Not signed in"
                    }
                )
            )
        try {
            Result.success(listBackupsWithToken(token))
        } catch (e: Exception) {
            Log.e(TAG, "list failed", e)
            Result.failure(e)
        }
    }

    private fun listBackupsWithToken(token: String): List<GDriveBackupEntry> {
        val q = URLEncoder.encode(
            "name contains '$GDRIVE_BACKUP_PREFIX' and trashed = false", "UTF-8"
        )
        val url = "https://www.googleapis.com/drive/v3/files?q=$q" +
                "&orderBy=createdTime%20desc&pageSize=50" +
                "&fields=files(id,name,size,createdTime)"
        val conn = http(url, "GET", token)
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = errorBody(conn)
            conn.disconnect()
            throw IllegalStateException("Drive list failed (HTTP $code): $err")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val arr = JSONObject(body).optJSONArray("files") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            GDriveBackupEntry(
                fileId = obj.optString("id", ""),
                name = obj.optString("name", ""),
                sizeBytes = obj.optString("size", "-1").toLongOrNull() ?: -1L,
                createdTime = obj.optString("createdTime", "")
            )
        }.filter { it.fileId.isNotBlank() }
    }

    /** Streams a backup's JSON from Drive directly into [dest] (memory-flat). */
    private fun downloadToFile(fileId: String, dest: File): Result<Unit> {
        val token = accessToken()
            ?: return Result.failure(IllegalStateException("Not signed in"))
        return try {
            val conn = http(
                "https://www.googleapis.com/drive/v3/files/$fileId?alt=media",
                "GET", token
            )
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = errorBody(conn)
                conn.disconnect()
                return Result.failure(
                    IllegalStateException("Drive download failed (HTTP $code): $err")
                )
            }
            dest.outputStream().use { out -> conn.inputStream.use { it.copyTo(out) } }
            conn.disconnect()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "download failed", e)
            Result.failure(e)
        }
    }

    /** Deletes a backup file from Drive. */
    suspend fun deleteBackup(fileId: String): Boolean = withContext(Dispatchers.IO) {
        val token = accessToken() ?: return@withContext false
        try {
            val conn = http("https://www.googleapis.com/drive/v3/files/$fileId", "DELETE", token)
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "delete failed: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  High-level operations
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Builds a full backup with [BackupManager] and uploads it to Drive as
     * [fileName]. Returns a human-readable status message.
     */
    suspend fun backupNow(fileName: String = defaultBackupFileName()): Result<String> {
        if (currentAccount() == null) {
            return Result.failure(IllegalStateException("Sign in to Google first"))
        }
        return try {
            val res = uploadBackupJson(fileName) { out -> backupManager.writeBackupJson(out) }
            res.map { fileName }
        } catch (e: Exception) {
            Log.e(TAG, "backupNow failed", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads the backup [fileId] and applies it via the standard
     * [BackupManager.importBackup] path (full restore, same validation).
     */
    suspend fun restoreFromDrive(fileId: String): BackupResult = withContext(Dispatchers.IO) {
        // importBackup reads via ContentResolver — a file:// Uri works fine.
        // Stream the download straight to disk: the multi-MB backup never
        // materializes as a String on the heap.
        val tmp = File(context.cacheDir, "gdrive_restore_${System.currentTimeMillis()}.json")
        val dl = downloadToFile(fileId, tmp)
        if (dl.isFailure) {
            return@withContext BackupResult.Failure(
                "Drive download failed: ${dl.exceptionOrNull()?.message}"
            )
        }
        try {
            backupManager.importBackup(Uri.fromFile(tmp))
        } catch (e: Exception) {
            BackupResult.Failure("Restore failed: ${e.message}", e)
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /** Result of the daily auto-backup gate. */
    sealed class AutoResult {
        object Disabled : AutoResult()
        object NotSignedIn : AutoResult()
        object AlreadyDoneToday : AutoResult()
        data class Backed(val fileName: String) : AutoResult()
        data class Failed(val message: String) : AutoResult()
    }

    /**
     * Once-per-day gate, mirroring [AutoBackupManager.runIfNeeded]. Called on
     * app start after the local auto-backup so Drive uploads never delay the
     * local one.
     */
    suspend fun runAutoBackupIfNeeded(today: LocalDate = LocalDate.now()): AutoResult {
        val settings = settingsRepo.settingsFlow.first()
        if (!settings.gdriveAutoEnabled) return AutoResult.Disabled
        if (currentAccount() == null) return AutoResult.NotSignedIn
        val todayStr = today.toString()
        if (settings.gdriveLastBackupDate == todayStr) return AutoResult.AlreadyDoneToday

        val res = backupNow("$GDRIVE_BACKUP_PREFIX$todayStr.json")
        return if (res.isSuccess) {
            settingsRepo.saveGdriveLastBackupDate(todayStr)
            val name = res.getOrNull().orEmpty()
            Log.i(TAG, "Drive auto-backup wrote $name")
            AutoResult.Backed(name)
        } else {
            val msg = res.exceptionOrNull()?.message ?: "unknown error"
            Log.w(TAG, "Drive auto-backup failed: $msg")
            AutoResult.Failed(msg)
        }
    }

    /** Default file name for manual backups (includes time to keep each one). */
    private fun defaultBackupFileName(): String {
        val now = java.time.Instant.now().toString().replace(':', '-').substringBefore('.')
        return "$GDRIVE_BACKUP_PREFIX$now.json"
    }
}
