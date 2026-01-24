package com.github.kopp.constantreminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

data class Reminder(
    val id: Int,
    val name: String,
    val text: String,
    var intervalMs: Long,
    val createdAtMs: Long = System.currentTimeMillis(),
    var lastShownMs: Long = 0,
    var totalShownCount: Int = 0
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("text", text)
            put("intervalMs", intervalMs)
            put("createdAtMs", createdAtMs)
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
                json.optLong("createdAtMs", System.currentTimeMillis()),
                json.optLong("lastShownMs", 0),
                json.optInt("totalShownCount", 0)
            )
        }
    }
}

data class SilenceScenario(
    val id: Int,
    val days: List<Int>, // Calendar.MONDAY, etc.
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("days", JSONArray(days))
            put("startHour", startHour)
            put("startMinute", startMinute)
            put("endHour", endHour)
            put("endMinute", endMinute)
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): SilenceScenario {
            val daysJson = json.getJSONArray("days")
            val daysList = mutableListOf<Int>()
            for (i in 0 until daysJson.length()) {
                daysList.add(daysJson.getInt(i))
            }
            return SilenceScenario(
                json.getInt("id"),
                daysList,
                json.getInt("startHour"),
                json.getInt("startMinute"),
                json.getInt("endHour"),
                json.getInt("endMinute")
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
        const val ACTION_SNOOZE = "com.github.kopp.constantreminder.SNOOZE"
        
        const val EXTRA_REMINDER_ID = "reminder_id"
        
        const val PREFS_NAME = "ReminderPrefs"
        const val KEY_REMINDERS = "reminders_list"
        const val KEY_SCENARIOS = "scenarios_list"
        const val KEY_BACKUP_URI = "backup_folder_uri"
        const val KEY_RANDOM_DELAY = "random_delay_min"
        const val KEY_BACKUP_ENABLED = "backup_enabled"
        const val KEY_LAST_BACKUP_DATE = "last_backup_date"

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
            val jsonString = jsonArray.toString()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_REMINDERS, jsonString).apply()
        }

        fun getSilenceScenarios(context: Context): MutableList<SilenceScenario> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonString = prefs.getString(KEY_SCENARIOS, null) ?: return mutableListOf()
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<SilenceScenario>()
            for (i in 0 until jsonArray.length()) {
                list.add(SilenceScenario.fromJsonObject(jsonArray.getJSONObject(i)))
            }
            return list
        }

        fun saveSilenceScenarios(context: Context, scenarios: List<SilenceScenario>) {
            val jsonArray = JSONArray()
            scenarios.forEach { jsonArray.put(it.toJsonObject()) }
            val jsonString = jsonArray.toString()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_SCENARIOS, jsonString).apply()
        }

        private fun performPeriodicBackup(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_BACKUP_ENABLED, false)) return

            val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val lastBackup = prefs.getString(KEY_LAST_BACKUP_DATE, "")
            if (today == lastBackup) return

            val remindersJson = prefs.getString(KEY_REMINDERS, "[]") ?: "[]"
            val scenariosJson = prefs.getString(KEY_SCENARIOS, "[]") ?: "[]"
            
            saveToExternalFolder(context, "ConstantReminders_reminders_backup.json", remindersJson)
            saveToExternalFolder(context, "ConstantReminders_scenarios_backup.json", scenariosJson)
            
            prefs.edit().putString(KEY_LAST_BACKUP_DATE, today).apply()
        }

        private fun saveToExternalFolder(context: Context, filename: String, content: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val uriStr = prefs.getString(KEY_BACKUP_URI, null) ?: return
            
            try {
                val folderUri = Uri.parse(uriStr)
                val pickedDir = DocumentFile.fromTreeUri(context, folderUri)
                
                var file = pickedDir?.findFile(filename)
                if (file == null) {
                    file = pickedDir?.createFile("application/json", filename)
                }
                
                file?.uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(content.toByteArray())
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun scheduleExactAlarm(context: Context, reminder: Reminder, delayMs: Long? = null, triggerTimeMs: Long? = null) {
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

            val triggerTime = when {
                triggerTimeMs != null -> triggerTimeMs
                delayMs != null -> System.currentTimeMillis() + delayMs
                else -> {
                    val baseTime = if (reminder.lastShownMs > 0) reminder.lastShownMs else reminder.createdAtMs
                    baseTime + reminder.intervalMs
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        }

        fun isWithinScenario(now: Calendar, scenario: SilenceScenario): Boolean {
            val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val startMinutes = scenario.startHour * 60 + scenario.startMinute
            val endMinutes = scenario.endHour * 60 + scenario.endMinute

            return if (startMinutes < endMinutes) {
                // Same day scenario
                if (now.get(Calendar.DAY_OF_WEEK) !in scenario.days) return false
                currentMinutes in startMinutes until endMinutes
            } else {
                // Overlaps midnight
                val prevDay = if (now.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) Calendar.SATURDAY else now.get(Calendar.DAY_OF_WEEK) - 1
                val yesterdayCorrected = if (now.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY && prevDay == Calendar.SATURDAY) Calendar.SATURDAY 
                                         else if (now.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY) Calendar.SUNDAY 
                                         else now.get(Calendar.DAY_OF_WEEK) - 1

                val isStartedYesterday = yesterdayCorrected in scenario.days && currentMinutes < endMinutes
                val isStartingToday = now.get(Calendar.DAY_OF_WEEK) in scenario.days && currentMinutes >= startMinutes
                
                isStartedYesterday || isStartingToday
            }
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
            ACTION_SNOOZE -> {
                val snoozeMs = Math.min(15 * 60 * 1000L, reminder.intervalMs)
                scheduleExactAlarm(context, reminder, snoozeMs)
                dismissNotification(context, reminderId)
            }
            else -> {
                val now = Calendar.getInstance()
                val scenarios = getSilenceScenarios(context)
                val activeScenario = scenarios.find { isWithinScenario(now, it) }

                if (activeScenario != null) {
                    // Silenced! Reschedule after silence period ends + random delay
                    val nextAllowedTime = getEndOfSilenceMs(now, activeScenario)
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val maxRandomDelay = prefs.getInt(KEY_RANDOM_DELAY, 10)
                    val randomOffset = Random.nextLong(1, maxRandomDelay + 1L) * 60 * 1000L
                    scheduleExactAlarm(context, reminder, triggerTimeMs = nextAllowedTime + randomOffset)
                } else {
                    performPeriodicBackup(context) // Run daily backup when notification fires
                    updateShownStats(context, reminderId)
                    createNotificationChannel(context)
                    showNotification(context, reminder)
                    
                    // Re-fetch to get updated lastShownMs for next schedule
                    val updatedReminder = getReminders(context).find { it.id == reminderId }
                    if (updatedReminder != null) {
                        scheduleExactAlarm(context, updatedReminder)
                    }
                }
            }
        }
    }

    private fun getEndOfSilenceMs(now: Calendar, scenario: SilenceScenario): Long {
        val end = now.clone() as Calendar
        end.set(Calendar.HOUR_OF_DAY, scenario.endHour)
        end.set(Calendar.MINUTE, scenario.endMinute)
        end.set(Calendar.SECOND, 0)
        end.set(Calendar.MILLISECOND, 0)
        
        if (end.before(now)) {
            end.add(Calendar.DAY_OF_MONTH, 1)
        }
        return end.timeInMillis
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
            
            // Reschedule with the new interval
            scheduleExactAlarm(context, reminders[index])
            
            val minutes = newInterval / 60000.0
            Toast.makeText(context, context.getString(R.string.toast_new_interval, reminder.name, minutes), Toast.LENGTH_SHORT).show()
        }
    }

    private fun dismissNotification(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.notification_channel_name)
            val descriptionText = context.getString(R.string.notification_channel_description)
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

        val snoozeIntent = Intent(context, ReminderReceiver::class.java).apply { 
            action = ACTION_SNOOZE 
            putExtra(EXTRA_REMINDER_ID, reminder.id)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(context, reminder.id * 10 + 4, snoozeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, "gratitude_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(reminder.name)
            .setContentText(reminder.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(okPendingIntent) // Clicking the notification body acts as "OK"
            .addAction(0, context.getString(R.string.action_increase), increasePendingIntent)
            .addAction(0, context.getString(R.string.action_snooze), snoozePendingIntent)
            .addAction(0, context.getString(R.string.action_decrease), decreasePendingIntent)

        with(NotificationManagerCompat.from(context)) {
            notify(reminder.id, builder.build())
        }
    }
}
