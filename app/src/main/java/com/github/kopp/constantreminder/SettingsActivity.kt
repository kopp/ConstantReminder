package com.github.kopp.constantreminder

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.Slider
import java.util.Calendar

class SettingsActivity : AppCompatActivity() {

    private lateinit var adapter: ScenarioAdapter
    private val scenarios = mutableListOf<SilenceScenario>()
    
    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            getSharedPreferences(ReminderReceiver.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(ReminderReceiver.KEY_BACKUP_URI, it.toString()).apply()
            updateFolderUI(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.settingsToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences(ReminderReceiver.PREFS_NAME, Context.MODE_PRIVATE)

        // Random Delay
        val sliderDelay = findViewById<Slider>(R.id.sliderRandomDelay)
        sliderDelay.value = prefs.getInt(ReminderReceiver.KEY_RANDOM_DELAY, 10).toFloat()
        sliderDelay.addOnChangeListener { _, value, _ ->
            prefs.edit().putInt(ReminderReceiver.KEY_RANDOM_DELAY, value.toInt()).apply()
        }

        // Backup Enabled
        val checkBackup = findViewById<CheckBox>(R.id.checkBackupEnabled)
        checkBackup.isChecked = prefs.getBoolean(ReminderReceiver.KEY_BACKUP_ENABLED, false)
        checkBackup.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(ReminderReceiver.KEY_BACKUP_ENABLED, isChecked).apply()
        }

        // Folder Picker
        findViewById<Button>(R.id.btnSelectFolder).setOnClickListener {
            folderPickerLauncher.launch(null)
        }
        val folderUriStr = prefs.getString(ReminderReceiver.KEY_BACKUP_URI, null)
        if (folderUriStr != null) {
            updateFolderUI(Uri.parse(folderUriStr))
        }

        // Scenarios
        scenarios.addAll(ReminderReceiver.getSilenceScenarios(this))
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewScenarios)
        adapter = ScenarioAdapter(scenarios) { scenario ->
            deleteScenario(scenario)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<Button>(R.id.btnAddScenario).setOnClickListener {
            showAddScenarioDialog()
        }
    }

    private fun updateFolderUI(uri: Uri) {
        findViewById<TextView>(R.id.textSelectedFolder).text = uri.path
    }

    private fun showAddScenarioDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_scenario, null)
        val btnStart = view.findViewById<Button>(R.id.btnStartTime)
        val btnEnd = view.findViewById<Button>(R.id.btnEndTime)
        
        var startH = 22
        var startM = 0
        var endH = 7
        var endM = 0

        btnStart.text = "%02d:%02d".format(startH, startM)
        btnEnd.text = "%02d:%02d".format(endH, endM)

        btnStart.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                startH = h
                startM = m
                btnStart.text = "%02d:%02d".format(h, m)
            }, startH, startM, true).show()
        }

        btnEnd.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                endH = h
                endM = m
                btnEnd.text = "%02d:%02d".format(h, m)
            }, endH, endM, true).show()
        }

        val dayChecks = listOf<CheckBox>(
            view.findViewById(R.id.checkMon),
            view.findViewById(R.id.checkTue),
            view.findViewById(R.id.checkWed),
            view.findViewById(R.id.checkThu),
            view.findViewById(R.id.checkFri),
            view.findViewById(R.id.checkSat),
            view.findViewById(R.id.checkSun)
        )
        val dayValues = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_add_scenario_title)
            .setView(view)
            .setPositiveButton(R.string.dialog_add) { _, _ ->
                val selectedDays = dayChecks.mapIndexedNotNull { index, cb -> if (cb.isChecked) dayValues[index] else null }
                if (selectedDays.isNotEmpty()) {
                    val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
                    val newScenario = SilenceScenario(id, selectedDays, startH, startM, endH, endM)
                    scenarios.add(newScenario)
                    ReminderReceiver.saveSilenceScenarios(this, scenarios)
                    adapter.notifyItemInserted(scenarios.size - 1)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun deleteScenario(scenario: SilenceScenario) {
        val index = scenarios.indexOf(scenario)
        if (index != -1) {
            scenarios.removeAt(index)
            ReminderReceiver.saveSilenceScenarios(this, scenarios)
            adapter.notifyItemRemoved(index)
        }
    }

    class ScenarioAdapter(private val scenarios: List<SilenceScenario>, private val onDelete: (SilenceScenario) -> Unit) : RecyclerView.Adapter<ScenarioAdapter.ViewHolder>() {
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val timeText: TextView = view.findViewById(R.id.textScenarioTime)
            val daysText: TextView = view.findViewById(R.id.textScenarioDays)
            val deleteBtn: ImageButton = view.findViewById(R.id.btnDeleteScenario)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_scenario, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val s = scenarios[position]
            holder.timeText.text = "%02d:%02d - %02d:%02d".format(s.startHour, s.startMinute, s.endHour, s.endMinute)
            
            val context = holder.itemView.context
            val dayStrings = s.days.map { day ->
                when (day) {
                    Calendar.MONDAY -> context.getString(R.string.day_mon)
                    Calendar.TUESDAY -> context.getString(R.string.day_tue)
                    Calendar.WEDNESDAY -> context.getString(R.string.day_wed)
                    Calendar.THURSDAY -> context.getString(R.string.day_thu)
                    Calendar.FRIDAY -> context.getString(R.string.day_fri)
                    Calendar.SATURDAY -> context.getString(R.string.day_sat)
                    Calendar.SUNDAY -> context.getString(R.string.day_sun)
                    else -> ""
                }
            }
            holder.daysText.text = dayStrings.joinToString(", ")
            holder.deleteBtn.setOnClickListener { onDelete(s) }
        }

        override fun getItemCount() = scenarios.size
    }
}
