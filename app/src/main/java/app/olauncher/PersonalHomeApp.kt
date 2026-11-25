package app.olauncher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import app.olauncher.helper.PhotoWallpapers

class PersonalHomeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android delivers USER_PRESENT to a running process (including when its Activity is stopped).
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_PRESENT) PhotoWallpapers(context).advanceOnUnlock()
            }
        }, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
