package com.github.kopp.constantreminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import android.Manifest

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: ReminderAdapter
    private val reminders = mutableListOf<Reminder>()
    private lateinit var prefs: SharedPreferences

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == ReminderReceiver.KEY_REMINDERS) {
            runOnUiThread {
                refreshReminders()
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        prefs = getSharedPreferences(ReminderReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        reminders.addAll(ReminderReceiver.getReminders(this))
        
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewReminders)
        adapter = ReminderAdapter(reminders)
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        findViewById<FloatingActionButton>(R.id.floatingActionButton).setOnClickListener {
            showAddReminderDialog()
        }

        checkPermissions()
    }

    private fun refreshReminders() {
        reminders.clear()
        reminders.addAll(ReminderReceiver.getReminders(this))
        adapter.notifyDataSetChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun showAddReminderDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_reminder, null)
        val nameInput = view.findViewById<TextInputEditText>(R.id.editTextName)
        val textInput = view.findViewById<TextInputEditText>(R.id.editTextText)
        val intervalInput = view.findViewById<TextInputEditText>(R.id.editTextInterval)

        AlertDialog.Builder(this)
            .setTitle("Add Reminder")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                val name = nameInput.text.toString()
                val text = textInput.text.toString()
                val intervalMin = intervalInput.text.toString().toDoubleOrNull() ?: 5.0
                if (name.isNotEmpty() && text.isNotEmpty()) {
                    addReminder(name, text, (intervalMin * 60000).toLong())
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addReminder(name: String, text: String, intervalMs: Long) {
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val newReminder = Reminder(id, name, text, intervalMs)
        val currentReminders = ReminderReceiver.getReminders(this)
        currentReminders.add(newReminder)
        ReminderReceiver.saveReminders(this, currentReminders)
        // refreshReminders() will be called automatically by the preference listener
        
        startReminder(newReminder)
    }

    private fun startReminder(reminder: Reminder) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_REMIND
            putExtra(ReminderReceiver.EXTRA_REMINDER_ID, reminder.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            reminder.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                val intentSettings = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                startActivity(intentSettings)
                return
            }
        }

        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + reminder.intervalMs,
            reminder.intervalMs,
            pendingIntent
        )
    }

    class ReminderAdapter(private val reminders: List<Reminder>) : RecyclerView.Adapter<ReminderAdapter.ViewHolder>() {
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(R.id.textViewName)
            val contentText: TextView = view.findViewById(R.id.textViewText)
            val frequencyText: TextView = view.findViewById(R.id.textViewFrequency)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_reminder, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val reminder = reminders[position]
            holder.nameText.text = reminder.name
            holder.contentText.text = reminder.text
            holder.frequencyText.text = "Interval: %.1f Min".format(reminder.intervalMs / 60000.0)
        }

        override fun getItemCount() = reminders.size
    }
}