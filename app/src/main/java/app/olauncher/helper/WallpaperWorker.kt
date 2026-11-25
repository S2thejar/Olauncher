package app.olauncher.helper

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class WallpaperWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    private val prefs = Prefs(applicationContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        wallpaperLock.withLock { applyWallpaper() }
    }

    private suspend fun applyWallpaper(): Result {
        val photos = PhotoWallpapers(applicationContext)
        when (inputData.getString(PhotoWallpapers.MODE)) {
            PhotoWallpapers.PHOTO_MANUAL -> return if (!prefs.dailyWallpaper && photos.applyNext()) Result.success() else Result.failure()
            PhotoWallpapers.PHOTO_UNLOCK -> {
                if (photos.onUnlock && !prefs.dailyWallpaper) photos.applyNext()
                return Result.success()
            }
            PhotoWallpapers.PHOTO_PERIODIC -> {
                if (photos.enabled && !prefs.dailyWallpaper) photos.applyNext()
                return Result.success()
            }
        }
        if (photos.enabled) return Result.success()
        val success =
            if (prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES && isOlauncherDefault(applicationContext).not())
                true
            else if (prefs.dailyWallpaper) {
                val wallType = checkWallpaperType()
                val wallpaperUrl = getTodaysWallpaper(wallType, prefs.firstOpenTime)
                if (prefs.dailyWallpaperUrl == wallpaperUrl)
                    true
                else {
                    prefs.dailyWallpaperUrl = wallpaperUrl
                    setWallpaper(applicationContext, wallpaperUrl)
                }
            } else
                true

        return if (success)
            Result.success()
        else
            Result.retry()
    }

    companion object { private val wallpaperLock = Mutex() }

    private fun checkWallpaperType(): String {
        return when (prefs.appTheme) {
            AppCompatDelegate.MODE_NIGHT_YES -> Constants.WALL_TYPE_DARK
            AppCompatDelegate.MODE_NIGHT_NO -> Constants.WALL_TYPE_LIGHT
            else -> if (applicationContext.isDarkThemeOn())
                Constants.WALL_TYPE_DARK
            else
                Constants.WALL_TYPE_LIGHT
        }
    }
}
