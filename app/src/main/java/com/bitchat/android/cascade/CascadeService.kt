package com.bitchat.android.cascade

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bitchat.android.R
import com.bitchat.android.cascade.data.CascadeRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CascadeService : Service() {
    @Inject lateinit var repository: CascadeRepository
    override fun onCreate() {
        super.onCreate()
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("cascade_mesh","Offline emergency mesh",NotificationManager.IMPORTANCE_LOW))
        val launch=PendingIntent.getActivity(this,0,Intent(this,CascadeActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(204,NotificationCompat.Builder(this,"cascade_mesh").setSmallIcon(R.drawable.ic_cascade)
            .setContentTitle("resQ mesh · OFFLINE").setContentText("Bluetooth relay active. Tap to open command.").setOngoing(true).setContentIntent(launch).build())
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        repository.startTransport()
        return START_STICKY
    }
    override fun onDestroy() { repository.stopTransport(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder?=null
}
