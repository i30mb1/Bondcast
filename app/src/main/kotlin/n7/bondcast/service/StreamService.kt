package n7.bondcast.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import n7.bondcast.MainActivity
import n7.bondcast.R
import n7.bondcast.appGraph

/**
 * Foreground-сервис на время стрима: держит процесс и доступ к камере/микрофону в фоне.
 * Сам стримом не управляет — этим занимается [n7.bondcast.stream.StreamController].
 */
internal class StreamService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            appGraph().streamController.stop()
            return START_NOT_STICKY
        }

        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.app_notification_channel_streaming), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stream_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.app_notification_content_text))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.app_notification_stop_action), stopIntent)
            .build()

        // типы camera/microphone для foreground-сервиса появились в API 30; на API 29 шлём без типа
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            serviceType,
        )
        return START_NOT_STICKY
    }

    internal companion object {
        private const val CHANNEL_ID = "streaming"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "n7.bondcast.action.STOP_STREAM"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, StreamService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamService::class.java))
        }
    }
}
