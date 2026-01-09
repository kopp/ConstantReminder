package com.github.kopp.constantreminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import android.Manifest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

        setupSwipeToDelete(recyclerView)

        findViewById<FloatingActionButton>(R.id.floatingActionButton).setOnClickListener {
            showAddReminderDialog()
        }

        checkPermissions()
    }

    private fun setupSwipeToDelete(recyclerView: RecyclerView) {
        val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            private val background = ColorDrawable(Color.RED)
            private val deleteIcon = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.ic_menu_delete)

            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val reminderToDelete = reminders[position]
                
                // Cancel Alarm
                cancelReminder(reminderToDelete)
                
                // Remove from List and Storage
                reminders.removeAt(position)
                ReminderReceiver.saveReminders(this@MainActivity, reminders)
                adapter.notifyItemRemoved(position)
                
                Toast.makeText(this@MainActivity, "${reminderToDelete.name} deleted", Toast.LENGTH_SHORT).show()
            }

            override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, sState: Int, isActive: Boolean) {
                val itemView = vh.itemView
                val iconMargin = (itemView.height - (deleteIcon?.intrinsicHeight ?: 0)) / 2

                if (dX < 0) { // Swiping to the left
                    background.setBounds(itemView.right + dX.toInt(), itemView.top, itemView.right, itemView.bottom)
                    background.draw(c)

                    deleteIcon?.let {
                        val iconLeft = itemView.right - iconMargin - it.intrinsicWidth
                        val iconRight = itemView.right - iconMargin
                        val iconTop = itemView.top + iconMargin
                        val iconBottom = itemView.bottom - iconMargin
                        it.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                        it.draw(c)
                    }
                }
                super.onChildDraw(c, rv, vh, dX, dY, sState, isActive)
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(recyclerView)
    }

    private fun cancelReminder(reminder: Reminder) {
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
        alarmManager.cancel(pendingIntent)
    }

    private fun refreshReminders() {
        val newReminders = ReminderReceiver.getReminders(this)
        // Only update if something actually changed to avoid disrupting user interaction
        if (newReminders != reminders) {
            reminders.clear()
            reminders.addAll(newReminders)
            adapter.notifyDataSetChanged()
        }
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
        val daysInput = view.findViewById<TextInputEditText>(R.id.editTextDays)
        val hoursInput = view.findViewById<TextInputEditText>(R.id.editTextHours)
        val minutesInput = view.findViewById<TextInputEditText>(R.id.editTextMinutes)

        AlertDialog.Builder(this)
            .setTitle("Add Reminder")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                val name = nameInput.text.toString()
                val text = textInput.text.toString()
                
                val days = daysInput.text.toString().toLongOrNull() ?: 0L
                val hours = hoursInput.text.toString().toLongOrNull() ?: 0L
                val minutes = minutesInput.text.toString().toLongOrNull() ?: 0L
                
                val intervalMs = (days * 24 * 60 * 60 * 1000L) + 
                                 (hours * 60 * 60 * 1000L) + 
                                 (minutes * 60 * 1000L)

                if (name.isNotEmpty() && text.isNotEmpty() && intervalMs > 0) {
                    addReminder(name, text, intervalMs)
                } else if (intervalMs <= 0) {
                    Toast.makeText(this, "Interval must be greater than 0", Toast.LENGTH_SHORT).show()
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
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(R.id.textViewName)
            val contentText: TextView = view.findViewById(R.id.textViewText)
            val frequencyText: TextView = view.findViewById(R.id.textViewFrequency)
            val statsText: TextView = view.findViewById(R.id.textViewStats)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_reminder, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val reminder = reminders[position]
            holder.nameText.text = reminder.name
            holder.contentText.text = reminder.text
            
            val days = reminder.intervalMs / (24 * 60 * 60 * 1000)
            val hours = (reminder.intervalMs % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000)
            val minutes = (reminder.intervalMs % (60 * 60 * 1000)) / (60 * 1000)
            
            val intervalText = buildString {
                append("Interval: ")
                if (days > 0) append("${days}d ")
                if (hours > 0) append("${hours}h ")
                if (minutes > 0 || (days == 0L && hours == 0L)) append("${minutes}m")
            }.trim()
            
            holder.frequencyText.text = intervalText
            
            val lastTime = if (reminder.lastShownMs > 0) timeFormat.format(Date(reminder.lastShownMs)) else "--:--"
            holder.statsText.text = "Last: $lastTime | %d times".format(reminder.totalShownCount)
        }

        override fun getItemCount() = reminders.size
    }
}