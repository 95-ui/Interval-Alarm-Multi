package com.example.intervalalarm

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.intervalalarm.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ReminderRepository
    private lateinit var adapter: ReminderAdapter
    private val reminders = mutableListOf<Reminder>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ReminderRepository(this)

        setupRecyclerView()
        setupButtons()
        loadReminders()
    }

    override fun onResume() {
        super.onResume()
        loadReminders()
    }

    private fun setupRecyclerView() {
        adapter = ReminderAdapter(
            reminders = reminders,
            onEdit = { reminder ->
                val intent = Intent(this, EditReminderActivity::class.java)
                intent.putExtra("reminder_id", reminder.id)
                startActivity(intent)
            },
            onDelete = { reminder ->
                confirmDelete(reminder)
            },
            onToggle = { reminder, isEnabled ->
                reminder.enabled = isEnabled
                repository.saveReminders(reminders)
                Toast.makeText(
                    this,
                    if (isEnabled) "${reminder.name} aktiviert" else "${reminder.name} deaktiviert",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        binding.recyclerReminders.layoutManager = LinearLayoutManager(this)
        binding.recyclerReminders.adapter = adapter
    }

    private fun setupButtons() {
        binding.fabAdd.setOnClickListener {
            val intent = Intent(this, EditReminderActivity::class.java)
            startActivity(intent)
        }

        binding.btnStartAll.setOnClickListener {
            startService()
        }

        binding.btnStopAll.setOnClickListener {
            stopService()
        }
    }

    private fun loadReminders() {
        reminders.clear()
        reminders.addAll(repository.loadReminders())
        adapter.notifyDataSetChanged()
        updateEmptyView()
    }

    private fun updateEmptyView() {
        if (reminders.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.recyclerReminders.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.recyclerReminders.visibility = View.VISIBLE
        }
    }

    private fun confirmDelete(reminder: Reminder) {
        AlertDialog.Builder(this)
            .setTitle("Erinnerung löschen")
            .setMessage("Möchtest du \"${reminder.name}\" wirklich löschen?")
            .setPositiveButton("Löschen") { _, _ ->
                val index = reminders.indexOfFirst { it.id == reminder.id }
                if (index >= 0) {
                    reminders.removeAt(index)
                    repository.saveReminders(reminders)
                    adapter.notifyItemRemoved(index)
                    updateEmptyView()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun startService() {
        val hasActive = reminders.any { it.enabled && !it.fileUri.isNullOrBlank() }
        if (!hasActive) {
            Toast.makeText(this, "Keine aktiven Erinnerungen mit Audiodatei vorhanden", Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(this, AlarmForegroundService::class.java).apply {
            action = "START_ALARM"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        Toast.makeText(this, "Erinnerungen gestartet", Toast.LENGTH_SHORT).show()
    }

    private fun stopService() {
        val intent = Intent(this, AlarmForegroundService::class.java).apply {
            action = "STOP_ALARM"
        }
        startService(intent)
        Toast.makeText(this, "Alle Erinnerungen gestoppt", Toast.LENGTH_SHORT).show()
    }
}
