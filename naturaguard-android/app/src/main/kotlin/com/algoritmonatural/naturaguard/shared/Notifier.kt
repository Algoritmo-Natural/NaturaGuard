package com.algoritmonatural.naturaguard.shared

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.algoritmonatural.naturaguard.MainActivity

/** Regista o alerta e, se for aviso ou pior, mostra uma notificacao. */
object Notifier {
    private const val CHANNEL = "alertas"

    fun raise(context: Context, event: SecurityEvent) {
        val app = context.applicationContext
        val id = EventLogger(app).log(event)
        if (event.severity.rank >= Severity.WARNING.rank) show(app, id, event)
    }

    private fun show(context: Context, id: String, event: SecurityEvent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Alertas de seguranca", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (event.severity == Severity.CRITICAL) "NaturaGuard: CRITICO" else "NaturaGuard: aviso"
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(event.message)
            .setStyle(Notification.BigTextStyle().bigText(event.message))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(id.hashCode(), notification)
    }
}
