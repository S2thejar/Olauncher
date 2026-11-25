package app.olauncher.helper

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import java.util.concurrent.TimeUnit
import kotlin.random.Random

internal class PhotoWallpapers(private val context: Context) {
    private val prefs = context.getSharedPreferences("personal_wallpapers", Context.MODE_PRIVATE)
    val count get() = photos.size
    val enabled get() = prefs.getBoolean("enabled", false)
    private val photos get() = prefs.getStringSet("photos", emptySet()).orEmpty().toSet()

    fun replace(uris: List<Uri>): Int {
        val resolver = context.contentResolver
        val selected = uris.distinct().take(MAX_PHOTOS).filter { uri ->
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
        }.map(Uri::toString).toSet()
        if (selected.isEmpty()) return 0
        val previous = photos
        prefs.edit { putStringSet("photos", selected); remove("last_photo") }
        (previous - selected).forEach { uri ->
            runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        return selected.size
    }

    fun resume() {
        if (count == 0) return
        turnOffOnlineRotation()
        prefs.edit { putBoolean("enabled", true) }
        val request = PeriodicWorkRequestBuilder<WallpaperWorker>(1, TimeUnit.DAYS)
            .setInputData(workDataOf(MODE to PHOTO_PERIODIC))
            .setInitialDelay(1, TimeUnit.DAYS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            Constants.WALLPAPER_WORKER_NAME, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request,
        )
    }

    fun pause() {
        if (enabled) {
            prefs.edit { putBoolean("enabled", false) }
            WorkManager.getInstance(context).cancelUniqueWork(Constants.WALLPAPER_WORKER_NAME)
        }
        WorkManager.getInstance(context).cancelUniqueWork(NEXT_WORK)
    }

    fun clear() {
        pause()
        val previous = photos
        prefs.edit { remove("photos"); remove("last_photo") }
        previous.forEach { uri ->
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    fun next() {
        if (count == 0) return
        turnOffOnlineRotation()
        WorkManager.getInstance(context).enqueueUniqueWork(NEXT_WORK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<WallpaperWorker>().setInputData(workDataOf(MODE to PHOTO_MANUAL)).build())
    }

    private fun turnOffOnlineRotation() {
        val upstream = Prefs(context)
        if (upstream.dailyWallpaper) {
            upstream.dailyWallpaper = false
            upstream.dailyWallpaperUrl = ""
            WorkManager.getInstance(context).cancelUniqueWork(Constants.WALLPAPER_WORKER_NAME)
        }
    }

    fun applyNext(): Boolean {
        val manager = WallpaperManager.getInstance(context)
        if (!manager.isWallpaperSupported || !manager.isSetWallpaperAllowed) return false
        return photoCandidates(photos, prefs.getString("last_photo", null)).any { photo ->
            val applied = runCatching {
                context.contentResolver.openInputStream(Uri.parse(photo))?.use { stream ->
                    manager.setStream(stream, null, true, WallpaperManager.FLAG_SYSTEM) != 0
                } == true
            }.getOrDefault(false)
            if (applied) prefs.edit { putString("last_photo", photo) }
            applied
        }
    }

    companion object {
        const val MAX_PHOTOS = 20
        const val MODE = "wallpaper_mode"
        const val PHOTO_PERIODIC = "photo_periodic"
        const val PHOTO_MANUAL = "photo_manual"
        private const val NEXT_WORK = "personal_wallpaper_next"
    }
}

internal fun photoCandidates(photos: Set<String>, last: String?, random: Random = Random.Default): List<String> =
    photos.filter { it != last }.shuffled(random) + listOfNotNull(last?.takeIf { it in photos })
