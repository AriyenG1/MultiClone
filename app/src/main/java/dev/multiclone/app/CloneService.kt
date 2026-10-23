package dev.multiclone.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*

class CloneService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runner: Job? = null
    private val app get() = application as MultiCloneApplication
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("cloning", "Clone generation", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, 1, notification("Preparing local APK processing"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (runner?.isActive != true) runner = scope.launch {
            try { app.ready.await(); app.queue.run { text -> manager.notify(1, notification(text)) } }
            finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }
    private fun notification(text: String) = NotificationCompat.Builder(this, "cloning")
        .setSmallIcon(dev.multiclone.app.R.drawable.ic_multiclone).setContentTitle("MultiClone")
        .setContentText(text).setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()
    override fun onTimeout(startId: Int, fgsType: Int) { scope.cancel(); stopSelf() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
