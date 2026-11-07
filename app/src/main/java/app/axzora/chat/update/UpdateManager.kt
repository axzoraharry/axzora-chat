package app.axzora.chat.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import app.axzora.chat.util.AppConstants
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class Available(val info: UpdateInfo) : UpdateState()
    data class Downloading(val progress: Float, val info: UpdateInfo) : UpdateState()
    data class ReadyToInstall(val info: UpdateInfo, val apkPath: String) : UpdateState()
    data class Installing(val info: UpdateInfo) : UpdateState()
    data class PendingUserAction(val info: UpdateInfo) : UpdateState()
    object Success : UpdateState()
    data class Error(val message: String) : UpdateState()
}

enum class UpdateSource { PLAY_STORE, GITHUB }

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val releaseNotes: String,
    val forceUpdate: Boolean,
    val sha256: String = "",
    val source: UpdateSource = UpdateSource.GITHUB
)

class UpdateManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        const val ACTION_UPDATE_STATUS = "app.axzora.chat.UPDATE_STATUS"
        const val EXTRA_SESSION_ID = "session_id"
        private const val PREFS_NAME = "update_prefs"
        private const val PREF_CACHED_APK_PATH = "cached_apk_path"
        private const val PREF_CACHED_VERSION_CODE = "cached_version_code"
        private const val PREF_CACHED_VERSION_NAME = "cached_version_name"
        private const val PREF_LAST_CHECK_TIME = "last_check_time"
        private const val PREF_CUSTOM_FEED_URL = "custom_feed_url"

        @Volatile
        private var INSTANCE: UpdateManager? = null

        fun getInstance(context: Context): UpdateManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UpdateManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun getInstanceOrNull(): UpdateManager? = INSTANCE
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(AppConstants.Update.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(AppConstants.Update.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(AppConstants.Update.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val playAppUpdateManager: AppUpdateManager by lazy {
        AppUpdateManagerFactory.create(context)
    }

    private var currentUpdateInfo: UpdateInfo? = null
    private var periodicCheckJob: Job? = null
    private var downloadJob: Job? = null
    private var isPeriodicCheckRunning = false

    init {
        restoreCachedState()
        startPeriodicChecks()
    }

    fun startPeriodicChecks() {
        if (isPeriodicCheckRunning) return
        isPeriodicCheckRunning = true
        periodicCheckJob = scope.launch {
            while (isActive) {
                val lastCheckTime = prefs.getLong(PREF_LAST_CHECK_TIME, 0)
                if (System.currentTimeMillis() - lastCheckTime >= AppConstants.Update.MIN_CHECK_INTERVAL_MS) {
                    performUpdateCheck()
                    prefs.edit().putLong(PREF_LAST_CHECK_TIME, System.currentTimeMillis()).apply()
                }
                delay(AppConstants.Update.CHECK_INTERVAL_MS)
            }
        }
    }

    fun stopPeriodicChecks() {
        periodicCheckJob?.cancel()
        periodicCheckJob = null
        isPeriodicCheckRunning = false
    }

    private fun restoreCachedState() {
        val cachedPath = prefs.getString(PREF_CACHED_APK_PATH, null)
        val cachedVersionCode = prefs.getInt(PREF_CACHED_VERSION_CODE, -1)
        val cachedVersionName = prefs.getString(PREF_CACHED_VERSION_NAME, null)

        if (cachedPath != null && cachedVersionCode > 0 && cachedVersionName != null) {
            val apkFile = File(cachedPath)
            if (apkFile.exists() && apkFile.length() > 0 && cachedVersionCode > getCurrentVersionCode()) {
                val info = UpdateInfo(cachedVersionCode, cachedVersionName, "", "", false, source = UpdateSource.GITHUB)
                currentUpdateInfo = info
                _updateState.value = UpdateState.ReadyToInstall(info, cachedPath)
                return
            }
        }
        clearCachedApk()
    }

    fun getCurrentVersionCode(): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode.toInt()
            else @Suppress("DEPRECATION") pInfo.versionCode
        } catch (e: Exception) { 0 }
    }

    fun getCurrentVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }
    }

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { context.packageManager.canRequestPackageInstalls() } catch (_: SecurityException) { false }
        } else true
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } ?: false
        } else {
            @Suppress("DEPRECATION") cm.activeNetworkInfo?.isConnected == true
        }
    }

    fun checkForUpdate() {
        val state = _updateState.value
        if (state is UpdateState.Downloading || state is UpdateState.Installing || state is UpdateState.ReadyToInstall) return
        scope.launch { performUpdateCheck() }
    }

    private suspend fun performUpdateCheck() {
        val state = _updateState.value
        if (state is UpdateState.Downloading || state is UpdateState.Installing || state is UpdateState.ReadyToInstall) return
        if (!isNetworkAvailable()) return

        try {
            _updateState.value = UpdateState.Checking

            val playStoreUpdate = tryPlayStoreCheck()
            if (playStoreUpdate != null) {
                currentUpdateInfo = playStoreUpdate
                _updateState.value = UpdateState.Available(playStoreUpdate)
                return
            }

            val latest = fetchLatestUpdate()
            if (latest == null) {
                _updateState.value = UpdateState.Idle
                return
            }

            if (latest.versionCode <= getCurrentVersionCode()) {
                _updateState.value = UpdateState.Idle
                return
            }

            currentUpdateInfo = latest
            _updateState.value = UpdateState.Available(latest)
            downloadUpdate(latest)

        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            if (_updateState.value is UpdateState.Checking) _updateState.value = UpdateState.Idle
        }
    }

    private suspend fun tryPlayStoreCheck(): UpdateInfo? = suspendCancellableCoroutine { continuation ->
        try {
            playAppUpdateManager.appUpdateInfo
                .addOnSuccessListener { info: AppUpdateInfo ->
                    if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                        info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                        continuation.resume(UpdateInfo(info.availableVersionCode(), "Play Store Update", "", "", false, source = UpdateSource.PLAY_STORE))
                    } else {
                        continuation.resume(null)
                    }
                }
                .addOnFailureListener { continuation.resume(null) }
        } catch (e: Exception) {
            continuation.resume(null)
        }
    }

    /**
     * Resolution order: custom self-hosted JSON feed (if configured) → GitHub Releases.
     * The custom feed is the path that works with a private repo / LAN / NAS host.
     */
    private suspend fun fetchLatestUpdate(): UpdateInfo? {
        val customUrl = getCustomFeedUrl()
        if (customUrl.isNotBlank()) {
            val custom = fetchFromCustomFeed(customUrl)
            if (custom != null) return custom
        }
        return fetchGitHubVersionInfo()
    }

    private suspend fun fetchFromCustomFeed(url: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Axzora-Chat/${getCurrentVersionName()}")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val versionCode = json.optInt("versionCode", 0)
                val versionName = json.optString("versionName", "")
                val apkUrl = json.optString("apkUrl", "")
                val sha256 = json.optString("sha256", "").lowercase()
                val releaseNotes = json.optString("releaseNotes", "")
                val forceUpdate = json.optBoolean("forceUpdate", false)
                if (versionCode <= 0 || apkUrl.isBlank()) return@withContext null
                UpdateInfo(versionCode, versionName, apkUrl, releaseNotes, forceUpdate, sha256, UpdateSource.GITHUB)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch custom update feed", e)
            null
        }
    }

    private suspend fun fetchGitHubVersionInfo(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(AppConstants.Update.GITHUB_API_URL)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "Zemzeme-Android/${getCurrentVersionName()}")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 403) return@withContext null
                if (!response.isSuccessful) return@withContext null

                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val tagName = json.getString("tag_name")
                val releaseNotes = json.optString("body", "")
                val assets = json.getJSONArray("assets")
                val apkUrl = findBestApkUrl(assets) ?: return@withContext null
                val versionCode = parseSemanticVersionCode(tagName)
                // Optional integrity hint: a line "sha256: <64-hex>" (or "sha256 = <hex>") in the release body.
                val sha256 = Regex("(?i)sha256\\s*[:=]\\s*([0-9a-f]{64})").find(releaseNotes)?.groupValues?.get(1)?.lowercase() ?: ""

                UpdateInfo(versionCode, tagName.removePrefix("v"), apkUrl, releaseNotes, false, sha256, UpdateSource.GITHUB)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch GitHub release", e)
            null
        }
    }

    private fun parseSemanticVersionCode(tagName: String): Int {
        return try {
            val parts = tagName.removePrefix("v").trim().split(".")
            when (parts.size) {
                1 -> parts[0].toInt() * 10000
                2 -> parts[0].toInt() * 10000 + parts[1].toInt() * 100
                else -> parts[0].toInt() * 10000 + parts[1].toInt() * 100 + (parts.getOrNull(2)?.toIntOrNull() ?: 0)
            }
        } catch (e: Exception) { 0 }
    }

    private fun findBestApkUrl(assets: JSONArray): String? {
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        var universalUrl: String? = null

        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            if (!name.endsWith(".apk")) continue
            val url = asset.getString("browser_download_url")
            when {
                primaryAbi == "arm64-v8a" && name.contains(AppConstants.Update.APK_ARM64_PATTERN) -> return url
                primaryAbi == "armeabi-v7a" && name.contains(AppConstants.Update.APK_ARMV7_PATTERN) -> return url
                primaryAbi == "x86_64" && name.contains(AppConstants.Update.APK_X86_64_PATTERN) -> return url
                primaryAbi == "x86" && name.contains(AppConstants.Update.APK_X86_PATTERN) && !name.contains("x86_64") -> return url
                name.contains(AppConstants.Update.APK_UNIVERSAL_PATTERN) -> universalUrl = url
            }
        }
        return universalUrl
    }

    private fun downloadUpdate(info: UpdateInfo) {
        if (!isNetworkAvailable()) {
            _updateState.value = UpdateState.Error("No network connection")
            return
        }

        downloadJob = scope.launch {
            try {
                _updateState.value = UpdateState.Downloading(0f, info)

                val apkFile = downloadApk(info.apkUrl) { progress ->
                    _updateState.value = UpdateState.Downloading(progress, info)
                }

                // Integrity gate: never hand an unverified APK to the installer when the
                // feed declares a sha256. Mismatch → delete the file and fail closed.
                if (info.sha256.isNotBlank()) {
                    val actual = sha256Of(apkFile)
                    if (actual.isBlank() || !actual.equals(info.sha256, ignoreCase = true)) {
                        apkFile.delete()
                        prefs.edit()
                            .remove(PREF_CACHED_APK_PATH)
                            .remove(PREF_CACHED_VERSION_CODE)
                            .remove(PREF_CACHED_VERSION_NAME)
                            .apply()
                        val exp = info.sha256.take(12)
                        val got = if (actual.isBlank()) "(unreadable)" else actual.take(12)
                        _updateState.value = UpdateState.Error("Checksum mismatch: expected $exp…, got $got…")
                        return@launch
                    }
                }

                prefs.edit()
                    .putString(PREF_CACHED_APK_PATH, apkFile.absolutePath)
                    .putInt(PREF_CACHED_VERSION_CODE, info.versionCode)
                    .putString(PREF_CACHED_VERSION_NAME, info.versionName)
                    .apply()

                _updateState.value = UpdateState.ReadyToInstall(info, apkFile.absolutePath)

            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    _updateState.value = UpdateState.Idle
                } else {
                    _updateState.value = UpdateState.Error(e.message ?: "Download failed")
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _updateState.value = UpdateState.Idle
    }

    private suspend fun downloadApk(url: String, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val apkFile = File(context.filesDir, "pending_update.apk")
        val existingBytes = if (apkFile.exists()) apkFile.length() else 0L

        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "Zemzeme-Android/${getCurrentVersionName()}")
        if (existingBytes > 0) requestBuilder.header("Range", "bytes=$existingBytes-")

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val isResuming = response.code == 206
            if (!response.isSuccessful && response.code != 206) {
                if (existingBytes > 0) {
                    apkFile.delete()
                    return@use downloadApkFresh(url, apkFile, onProgress)
                }
                throw IOException("Download failed: HTTP ${response.code}")
            }

            val body = response.body ?: throw IOException("Empty response body")
            val contentLength = body.contentLength()
            val totalSize = if (isResuming) existingBytes + contentLength else contentLength

            if (!isResuming && apkFile.exists()) apkFile.delete()

            FileOutputStream(apkFile, isResuming).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Long = existingBytes
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        onProgress(if (totalSize > 0) (bytesRead.toFloat() / totalSize).coerceIn(0f, 1f) else -1f)
                    }
                }
            }
            apkFile
        }
    }

    private suspend fun downloadApkFresh(url: String, apkFile: File, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", "Zemzeme-Android/${getCurrentVersionName()}").build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response body")
            val contentLength = body.contentLength()
            FileOutputStream(apkFile).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        onProgress(if (contentLength > 0) (bytesRead.toFloat() / contentLength).coerceIn(0f, 1f) else -1f)
                    }
                }
            }
            apkFile
        }
    }

    fun installUpdate() {
        val state = _updateState.value
        val apkPath = (state as? UpdateState.ReadyToInstall)?.apkPath ?: return
        val info = currentUpdateInfo ?: return

        if (!canRequestPackageInstalls()) {
            _updateState.value = UpdateState.Error("Install permission required")
            return
        }

        scope.launch {
            try {
                _updateState.value = UpdateState.Installing(info)
                installApk(File(apkPath))
            } catch (e: Exception) {
                _updateState.value = UpdateState.Error(e.message ?: "Installation failed")
            }
        }
    }

    fun checkAndInstallUpdate() {
        if (_updateState.value is UpdateState.ReadyToInstall) installUpdate() else checkForUpdate()
    }

    fun testShowUpdateDialog() {
        // Simulate a ReadyToInstall state for UI testing
        val info = UpdateInfo(
            versionCode = getCurrentVersionCode() + 1,
            versionName = "test-dialog",
            apkUrl = "",
            releaseNotes = "This is a test update dialog",
            forceUpdate = false,
            source = UpdateSource.GITHUB
        )
        currentUpdateInfo = info
        _updateState.value = UpdateState.ReadyToInstall(info, "")
    }

    private suspend fun installApk(apkFile: File) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            installViaPackageInstaller(apkFile)
        } else {
            withContext(Dispatchers.Main) { installViaIntent(apkFile) }
        }
    }

    private fun installViaPackageInstaller(apkFile: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = packageInstaller.createSession(params)
        packageInstaller.openSession(sessionId).use { session ->
            session.openWrite("update.apk", 0, apkFile.length()).use { output ->
                apkFile.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val intent = Intent(context, UpdateReceiver::class.java).apply {
                action = ACTION_UPDATE_STATUS
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pendingIntent.intentSender)
        }
    }

    private fun installViaIntent(apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        currentUpdateInfo?.let { _updateState.value = UpdateState.PendingUserAction(it) }
    }

    internal fun onInstallStatusReceived(status: Int, message: String?) {
        val info = currentUpdateInfo
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                clearCachedApk()
                _updateState.value = UpdateState.Success
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (info != null) _updateState.value = UpdateState.PendingUserAction(info)
            }
            else -> {
                _updateState.value = UpdateState.Error(message ?: "Installation failed (status: $status)")
            }
        }
    }

    private fun sha256Of(file: File): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "sha256 computation failed", e)
            ""
        }
    }

    fun getCustomFeedUrl(): String = prefs.getString(PREF_CUSTOM_FEED_URL, "") ?: ""

    fun setCustomFeedUrl(url: String) {
        val cleaned = url.trim().ifBlank { "" }
        prefs.edit().putString(PREF_CUSTOM_FEED_URL, cleaned).apply()
        Log.i(TAG, if (cleaned.isBlank()) "Custom update feed cleared" else "Custom update feed set to $cleaned")
    }

    private fun clearCachedApk() {
        prefs.getString(PREF_CACHED_APK_PATH, null)?.let { File(it).delete() }
        prefs.edit().remove(PREF_CACHED_APK_PATH).remove(PREF_CACHED_VERSION_CODE).remove(PREF_CACHED_VERSION_NAME).apply()
    }

    fun dismissUpdate() {
        when (val state = _updateState.value) {
            is UpdateState.PendingUserAction -> {
                currentUpdateInfo?.let {
                    _updateState.value = UpdateState.ReadyToInstall(it, prefs.getString(PREF_CACHED_APK_PATH, "") ?: "")
                }
            }
            is UpdateState.Error -> _updateState.value = UpdateState.Idle
            else -> {}
        }
    }

    fun hasCachedUpdate(): Boolean = _updateState.value is UpdateState.ReadyToInstall

    fun resetState() { _updateState.value = UpdateState.Idle }
}
