package com.framegen.pvr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast

/**
 * Servico em primeiro plano (obrigatorio para MediaProjection).
 * Recebe o resultado da permissao de captura e entrega o MediaProjection
 * para o overlay/engine.
 */
class ProjectionService : Service() {

    companion object {
        const val ACTION_STOP = "com.framegen.pvr.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "framegen"
        private const val NOTIF_ID = 1
    }

    private var projection: MediaProjection? = null
    private val main = Handler(Looper.getMainLooper())

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post { stopSelf() }
        }

        // Android 14: o app capturado saiu / voltou para a tela.
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            Bus.overlay?.setCaptureVisible(isVisible)
        }

                override fun onCapturedContentResize(width: Int, height: Int) {
            Bus.overlay?.onCaptureResize(width, height)
        }
    

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        val overlay = Bus.overlay
        if (data == null || code == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (overlay == null) {
            Toast.makeText(this, "Ative o servico de acessibilidade do FrameGen primeiro.", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        releaseProjection()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data)
        mp.registerCallback(callback, main)
        projection = mp
        Bus.running = true
        overlay.startEngine(mp)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Bus.running = false
        Bus.overlay?.stopEngine()
        releaseProjection()
        super.onDestroy()
    }

    private fun releaseProjection() {
        projection?.let {
            try {
                it.unregisterCallback(callback)
                it.stop()
            } catch (_: Exception) {
            }
        }
        projection = null
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "FrameGen PVR", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, ProjectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("FrameGen PVR ativo")
            .setContentText("Interpolando quadros do app capturado")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Parar", stop)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }
}
