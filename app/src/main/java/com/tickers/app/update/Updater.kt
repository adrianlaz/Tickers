package com.tickers.app.update

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.tickers.app.BuildConfig
import com.tickers.app.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: GitHubRelease) : UpdateState
    data class Downloading(val release: GitHubRelease, val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class NeedsPermission(val release: GitHubRelease) : UpdateState
    data class Failed(val message: String, val release: GitHubRelease? = null) : UpdateState
}

/**
 * Self-updater for a sideloaded app: checks GitHub Releases, downloads the APK and hands it to
 * PackageInstaller. Android verifies the APK is signed with the same key as the installed app,
 * so no extra integrity check is needed beyond HTTPS + size.
 */
class Updater(private val context: Context) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = _state.asStateFlow()

    private val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)

    val enabled: Boolean get() = BuildConfig.GITHUB_REPO.contains('/')
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Automatic check on app start, throttled (unauthenticated GitHub API: 60 requests/hour). */
    suspend fun checkIfDue() {
        val last = prefs.getLong(KEY_LAST_CHECK, 0)
        if (System.currentTimeMillis() - last >= AUTO_CHECK_INTERVAL_MS) check(silent = true)
    }

    /** [silent]: automatic check, so "up to date" and errors are not shown. */
    suspend fun check(silent: Boolean = false) {
        if (!enabled) return
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading || s is UpdateState.Installing) return
        _state.value = UpdateState.Checking
        val result = try {
            val (owner, repo) = BuildConfig.GITHUB_REPO.split('/', limit = 2)
            val release = GitHub.api.latestRelease(owner, repo)
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            when {
                !Versions.isNewer(release.tagName, currentVersion) -> UpdateState.UpToDate
                release.apk == null -> UpdateState.Failed("Release ${release.tagName} has no APK")
                else -> UpdateState.Available(release)
            }
        } catch (e: CancellationException) {
            _state.value = UpdateState.Idle
            throw e
        } catch (e: Exception) {
            UpdateState.Failed("Update check failed: ${e.message}")
        }
        _state.value = if (silent && result !is UpdateState.Available) UpdateState.Idle else result
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    suspend fun downloadAndInstall(release: GitHubRelease) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            _state.value = UpdateState.NeedsPermission(release)
            return
        }
        val asset = release.apk ?: return
        try {
            val file = download(release, asset)
            _state.value = UpdateState.Installing
            install(file)
        } catch (e: CancellationException) {
            _state.value = UpdateState.Available(release)
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("Update failed: ${e.message}", release)
        }
    }

    /** Settings screen where the user allows this app to install updates. */
    fun permissionSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Called by MainActivity (foreground) with the PackageInstaller status intent. */
    fun onInstallStatus(activity: Activity, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) activity.startActivity(confirm)
            }
            // On success the app process is replaced; this is rarely reached.
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.Idle
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                _state.value = UpdateState.Failed(
                    if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "Update cancelled"
                    else "Install failed: ${msg ?: "status $status"}"
                )
            }
        }
    }

    private suspend fun download(release: GitHubRelease, asset: GitHubAsset): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "update.apk")
            _state.value = UpdateState.Downloading(release, 0f)
            GitHub.http.newCall(Request.Builder().url(asset.downloadUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body
                val total = asset.size.takeIf { it > 0 } ?: body.contentLength()
                body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReported = 0f
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val p = if (total > 0) done.toFloat() / total else 0f
                            if (p - lastReported >= 0.01f) {
                                lastReported = p
                                _state.value = UpdateState.Downloading(release, p)
                            }
                        }
                    }
                }
                if (asset.size > 0 && file.length() != asset.size) {
                    file.delete()
                    throw IOException("Incomplete download (${file.length()} of ${asset.size} bytes)")
                }
            }
            file
        }

    private suspend fun install(file: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            // Result comes back to MainActivity (foreground), which shows the system prompt.
            // Explicit intent + FLAG_MUTABLE: the system adds the status extras.
            val callback = Intent(context, MainActivity::class.java)
                .setAction(ACTION_INSTALL_STATUS)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getActivity(context, sessionId, callback, flags)
            session.commit(pending.intentSender)
        }
        file.delete()
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.tickers.app.INSTALL_STATUS"
        private const val KEY_LAST_CHECK = "last_check"
        private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
