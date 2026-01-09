package com.github.kopp.constantreminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INCREASE_FREQUENCY = "com.github.kopp.constantreminder.INCREASE_FREQUENCY"
        const val ACTION_DECREASE_FREQUENCY = "com.github.kopp.constantreminder.DECREASE_FREQUENCY"
        const val ACTION_DISMISS = "com.github.kopp.constantreminder.DISMISS"
        
        const val PREFS_NAME = "ReminderPrefs"
        const val KEY_INTERVAL = "interval_ms"
        const val DEFAULT_INTERVAL = 5 * 60 * 1000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_INCREASE_FREQUENCY -> {
                updateInterval(context, 1.3) // Increase frequency
                dismissNotification(context)
            }
            ACTION_DECREASE_FREQUENCY -> {
                updateInterval(context, 0.7) // Decrease frequency
                dismissNotification(context)
            }
            ACTION_DISMISS -> {
                dismissNotification(context)
            }
            else -> {
                createNotificationChannel(context)
                showNotification(context)
            }
        }
    }

    private fun updateInterval(context: Context, factor: Double) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentInterval = prefs.getLong(KEY_INTERVAL, DEFAULT_INTERVAL)
        // frequency = 1/interval. New freq = old freq * factor => New interval = old interval / factor
        val newInterval = (currentInterval / factor).toLong().coerceAtLeast(60000L) // Min 1 minute
        
        prefs.edit().putLong(KEY_INTERVAL, newInterval).apply()
        
        rescheduleAlarm(context, newInterval)
        
        val minutes = newInterval / 60000.0
        Toast.makeText(context, "Neues Intervall: %.1f Minuten".format(minutes), Toast.LENGTH_SHORT).show()
    }

    private fun rescheduleAlarm(context: Context, intervalMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + intervalMs,
            intervalMs,
            pendingIntent
        )
    }

    private fun dismissNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(1)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Reminder Channel"
            val descriptionText = "Channel for gratitude reminders"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel("gratitude_channel", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        val increaseIntent = Intent(context, ReminderReceiver::class.java).apply { action = ACTION_INCREASE_FREQUENCY }
        val increasePendingIntent = PendingIntent.getBroadcast(context, 1, increaseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val okIntent = Intent(context, ReminderReceiver::class.java).apply { action = ACTION_DISMISS }
        val okPendingIntent = PendingIntent.getBroadcast(context, 2, okIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val decreaseIntent = Intent(context, ReminderReceiver::class.java).apply { action = ACTION_DECREASE_FREQUENCY }
        val decreasePendingIntent = PendingIntent.getBroadcast(context, 3, decreaseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, "gratitude_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Dankbarkeit")
            .setContentText("Zeit für einen Moment der Dankbarkeit.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .addAction(0, "öfter erinnern", increasePendingIntent)
            .addAction(0, "ok", okPendingIntent)
            .addAction(0, "weniger erinnern", decreasePendingIntent)

        with(NotificationManagerCompat.from(context)) {
            notify(1, builder.build())
        }
    }
}