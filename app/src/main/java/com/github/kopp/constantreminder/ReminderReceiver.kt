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
import org.json.JSONArray
import org.json.JSONObject

data class Reminder(
    val id: Int,
    val name: String,
    val text: String,
    var intervalMs: Long,
    var lastShownMs: Long = 0,
    var totalShownCount: Int = 0
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("text", text)
            put("intervalMs", intervalMs)
            put("lastShownMs", lastShownMs)
            put("totalShownCount", totalShownCount)
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): Reminder {
            return Reminder(
                json.getInt("id"),
                json.getString("name"),
                json.getString("text"),
                json.getLong("intervalMs"),
                json.optLong("lastShownMs", 0),
                json.optInt("totalShownCount", 0)
            )
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REMIND = "com.github.kopp.constantreminder.REMIND"
        const val ACTION_INCREASE_FREQUENCY = "com.github.kopp.constantreminder.INCREASE_FREQUENCY"
        const val ACTION_DECREASE_FREQUENCY = "com.github.kopp.constantreminder.DECREASE_FREQUENCY"
        const val ACTION_DISMISS = "com.github.kopp.constantreminder.DISMISS"
        
        const val EXTRA_REMINDER_ID = "reminder_id"
        
        const val PREFS_NAME = "ReminderPrefs"
        const val KEY_REMINDERS = "reminders_list"

        fun getReminders(context: Context): MutableList<Reminder> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonString = prefs.getString(KEY_REMINDERS, null) ?: return mutableListOf()
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<Reminder>()
            for (i in 0 until jsonArray.length()) {
                list.add(Reminder.fromJsonObject(jsonArray.getJSONObject(i)))
            }
            return list
        }

        fun saveReminders(context: Context, reminders: List<Reminder>) {
            val jsonArray = JSONArray()
            reminders.forEach { jsonArray.put(it.toJsonObject()) }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_REMINDERS, jsonArray.toString()).apply()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getIntExtra(EXTRA_REMINDER_ID, -1)
        if (reminderId == -1) return

        val reminders = getReminders(context)
        val reminder = reminders.find { it.id == reminderId } ?: return

        when (intent.action) {
            ACTION_INCREASE_FREQUENCY -> {
                updateReminderInterval(context, reminder, 1.3)
                dismissNotification(context, reminderId)
            }
            ACTION_DECREASE_FREQUENCY -> {
                updateReminderInterval(context, reminder, 0.7)
                dismissNotification(context, reminderId)
            }
            ACTION_DISMISS -> {
                dismissNotification(context, reminderId)
            }
            else -> {
                updateShownStats(context, reminderId)
                createNotificationChannel(context)
                showNotification(context, reminder)
            }
        }
    }

    private fun updateShownStats(context: Context, id: Int) {
        val reminders = getReminders(context)
        val index = reminders.indexOfFirst { it.id == id }
        if (index != -1) {
            reminders[index].lastShownMs = System.currentTimeMillis()
            reminders[index].totalShownCount++
            saveReminders(context, reminders)
        }
    }

    private fun updateReminderInterval(context: Context, reminder: Reminder, factor: Double) {
        val reminders = getReminders(context)
        val index = reminders.indexOfFirst { it.id == reminder.id }
        if (index != -1) {
            val newInterval = (reminders[index].intervalMs / factor).toLong().coerceAtLeast(60000L)
            reminders[index].intervalMs = newInterval
            saveReminders(context, reminders)
            
            rescheduleAlarm(context, reminders[index])
            
            val minutes = newInterval / 60000.0
            Toast.makeText(context, "${reminder.name}: %.1f Minuten".format(minutes), Toast.LENGTH_SHORT).show()
        }
    }

    private fun rescheduleAlarm(context: Context, reminder: Reminder) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMIND
            putExtra(EXTRA_REMINDER_ID, reminder.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + reminder.intervalMs,
            reminder.intervalMs,
            pendingIntent
        )
    }

    private fun dismissNotification(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Reminder Channel"
            val descriptionText = "Channel for all reminders"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel("gratitude_channel", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(context: Context, reminder: Reminder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        val increaseIntent = Intent(context, ReminderReceiver::class.java).apply { 
            action = ACTION_INCREASE_FREQUENCY 
            putExtra(EXTRA_REMINDER_ID, reminder.id)
        }
        val increasePendingIntent = PendingIntent.getBroadcast(context, reminder.id * 10 + 1, increaseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val okIntent = Intent(context, ReminderReceiver::class.java).apply { 
            action = ACTION_DISMISS 
            putExtra(EXTRA_REMINDER_ID, reminder.id)
        }
        val okPendingIntent = PendingIntent.getBroadcast(context, reminder.id * 10 + 2, okIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val decreaseIntent = Intent(context, ReminderReceiver::class.java).apply { 
            action = ACTION_DECREASE_FREQUENCY 
            putExtra(EXTRA_REMINDER_ID, reminder.id)
        }
        val decreasePendingIntent = PendingIntent.getBroadcast(context, reminder.id * 10 + 3, decreaseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, "gratitude_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(reminder.name)
            .setContentText(reminder.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .addAction(0, "öfter erinnern", increasePendingIntent)
            .addAction(0, "ok", okPendingIntent)
            .addAction(0, "weniger erinnern", decreasePendingIntent)

        with(NotificationManagerCompat.from(context)) {
            notify(reminder.id, builder.build())
        }
    }
}