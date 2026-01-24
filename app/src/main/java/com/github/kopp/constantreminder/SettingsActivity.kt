package com.github.kopp.constantreminder

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Calendar

class SettingsActivity : AppCompatActivity() {

    private lateinit var adapter: ScenarioAdapter
    private val scenarios = mutableListOf<SilenceScenario>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.settingsToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        scenarios.addAll(ReminderReceiver.getSilenceScenarios(this))

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewScenarios)
        adapter = ScenarioAdapter(scenarios) { scenario ->
            deleteScenario(scenario)
        }
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        findViewById<Button>(R.id.btnAddScenario).setOnClickListener {
            showAddScenarioDialog()
        }
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
            
            val dayStrings = s.days.map { day ->
                when (day) {
                    Calendar.MONDAY -> "Mon"
                    Calendar.TUESDAY -> "Tue"
                    Calendar.WEDNESDAY -> "Wed"
                    Calendar.THURSDAY -> "Thu"
                    Calendar.FRIDAY -> "Fri"
                    Calendar.SATURDAY -> "Sat"
                    Calendar.SUNDAY -> "Sun"
                    else -> ""
                }
            }
            holder.daysText.text = dayStrings.joinToString(", ")
            holder.deleteBtn.setOnClickListener { onDelete(s) }
        }

        override fun getItemCount() = scenarios.size
    }
}
