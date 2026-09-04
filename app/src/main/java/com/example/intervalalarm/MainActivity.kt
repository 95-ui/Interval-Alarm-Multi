package com.example.intervalalarm.multi

import android.Manifest
import android.app.TimePickerDialog
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.intervalalarm.databinding.ActivityMainBinding
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var testMediaPlayer: MediaPlayer? = null
    private var isAlarmRunning = false

    private var totalIntervalMs: Long = 0L

    // Countdown-Empfänger vom Service
    private val countdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val remaining = intent?.getLongExtra("remaining_ms", 0L) ?: 0L
            val total = intent?.getLongExtra("total_interval_ms", 0L) ?: 0L
            val running = intent?.getBooleanExtra("is_running", false) ?: false
            isAlarmRunning = running
            if (total > 0) totalIntervalMs = total
            updateCountdownDisplay(remaining)
            updateButtonStates()
        }
    }

    // Datei-Auswahl Ergebnis
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            // Dauerhafte Berechtigung für die Datei sichern
            contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val fileName = getFileName(it)
            prefs.edit()
                .putString("selected_file_uri", it.toString())
                .putString("selected_file_name", fileName)
                .apply()
            binding.tvSelectedFile.text = fileName
            Toast.makeText(this, "Datei gewählt: $fileName", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("interval_alarm_prefs", MODE_PRIVATE)

        requestPermissions()
        loadSavedSettings()
        setupUI()
        updateButtonStates()
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            countdownReceiver, IntentFilter("COUNTDOWN_UPDATE")
        )
        // Aktuellen Status vom Service abfragen
        val intent = Intent(this, AlarmForegroundService::class.java)
        intent.action = "REQUEST_STATUS"
        try { startService(intent) } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(countdownReceiver)
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        }
    }

    private fun loadSavedSettings() {
        // Gespeicherte Datei laden
        val fileName = prefs.getString("selected_file_name", "Keine Datei gewählt")
        binding.tvSelectedFile.text = fileName

        // Intervall laden
        binding.etIntervalValue.setText(prefs.getInt("interval_value", 5).toString())

        // Intervall-Einheit laden
        val unitIndex = prefs.getInt("interval_unit", 1) // 0=Sek, 1=Min, 2=Std
        binding.spinnerUnit.setSelection(unitIndex)

        // Abspieldauer laden
        binding.cbLimitDuration.isChecked = prefs.getBoolean("limit_duration", false)
        binding.etMaxSeconds.setText(prefs.getInt("max_seconds", 10).toString())
        binding.etMaxSeconds.isEnabled = binding.cbLimitDuration.isChecked

        // Lautstärke laden
        binding.seekBarVolume.progress = prefs.getInt("volume", 80)
        binding.tvVolumePercent.text = "${prefs.getInt("volume", 80)}%"

        // Zeitfenster laden
        binding.cbTimeWindow.isChecked = prefs.getBoolean("use_time_window", false)
        val startH = prefs.getInt("start_hour", 8)
        val startM = prefs.getInt("start_minute", 0)
        val endH = prefs.getInt("end_hour", 22)
        val endM = prefs.getInt("end_minute", 0)
        binding.btnStartTime.text = String.format("%02d:%02d", startH, startM)
        binding.btnEndTime.text = String.format("%02d:%02d", endH, endM)
        binding.layoutTimeWindow.visibility =
            if (binding.cbTimeWindow.isChecked) android.view.View.VISIBLE
            else android.view.View.GONE

        // Benachrichtigungstext laden
        binding.cbCustomNotification.isChecked = prefs.getBoolean("custom_notification", false)
        binding.etNotificationText.setText(
            prefs.getString("notification_text", "Zeit für eine Pause! ⏰")
        )
        binding.etNotificationText.isEnabled = binding.cbCustomNotification.isChecked

        // Benachrichtigungsleiste
        binding.cbShowNotification.isChecked = prefs.getBoolean("show_notification", true)
    }

    private fun setupUI() {
        // Datei wählen Button
        binding.btnSelectFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf("audio/*"))
        }

        // Test abspielen Button
        binding.btnTestPlay.setOnClickListener {
            testPlayAudio()
        }

        // Stop Test Button
        binding.btnTestStop.setOnClickListener {
            stopTestAudio()
        }

        // Lautstärke SeekBar
        binding.seekBarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvVolumePercent.text = "${progress}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Abspieldauer Checkbox
        binding.cbLimitDuration.setOnCheckedChangeListener { _, isChecked ->
            binding.etMaxSeconds.isEnabled = isChecked
        }

        // Zeitfenster Checkbox
        binding.cbTimeWindow.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutTimeWindow.visibility =
                if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
        }

        // Startzeit wählen
        binding.btnStartTime.setOnClickListener {
            val h = prefs.getInt("start_hour", 8)
            val m = prefs.getInt("start_minute", 0)
            TimePickerDialog(this, { _, hour, minute ->
                binding.btnStartTime.text = String.format("%02d:%02d", hour, minute)
                prefs.edit().putInt("start_hour", hour).putInt("start_minute", minute).apply()
            }, h, m, true).show()
        }

        // Endzeit wählen
        binding.btnEndTime.setOnClickListener {
            val h = prefs.getInt("end_hour", 22)
            val m = prefs.getInt("end_minute", 0)
            TimePickerDialog(this, { _, hour, minute ->
                binding.btnEndTime.text = String.format("%02d:%02d", hour, minute)
                prefs.edit().putInt("end_hour", hour).putInt("end_minute", minute).apply()
            }, h, m, true).show()
        }

        // Custom Notification Checkbox
        binding.cbCustomNotification.setOnCheckedChangeListener { _, isChecked ->
            binding.etNotificationText.isEnabled = isChecked
        }

        // ALARM STARTEN Button
        binding.btnStartAlarm.setOnClickListener {
            saveSettings()
            startAlarmService()
        }

        // ALARM STOPPEN Button
        binding.btnStopAlarm.setOnClickListener {
            stopAlarmService()
        }
    }

    private fun saveSettings() {
        val intervalValue = binding.etIntervalValue.text.toString().toIntOrNull() ?: 5
        val unitIndex = binding.spinnerUnit.selectedItemPosition

        prefs.edit().apply {
            putInt("interval_value", intervalValue)
            putInt("interval_unit", unitIndex)
            putBoolean("limit_duration", binding.cbLimitDuration.isChecked)
            putInt("max_seconds", binding.etMaxSeconds.text.toString().toIntOrNull() ?: 10)
            putInt("volume", binding.seekBarVolume.progress)
            putBoolean("use_time_window", binding.cbTimeWindow.isChecked)
            putBoolean("show_notification", binding.cbShowNotification.isChecked)
            putBoolean("custom_notification", binding.cbCustomNotification.isChecked)
            putString("notification_text", binding.etNotificationText.text.toString())
            apply()
        }
    }

    private fun startAlarmService() {
        val fileUri = prefs.getString("selected_file_uri", null)
        if (fileUri == null) {
            Toast.makeText(this, "Bitte zuerst eine Audiodatei wählen!", Toast.LENGTH_LONG).show()
            return
        }

        val intervalValue = binding.etIntervalValue.text.toString().toIntOrNull()
        if (intervalValue == null || intervalValue <= 0) {
            Toast.makeText(this, "Bitte einen gültigen Intervall eingeben!", Toast.LENGTH_LONG).show()
            return
        }

        saveSettings()

        // Gesamtintervall berechnen für den Kreis-Fortschritt
        val unitIndex = binding.spinnerUnit.selectedItemPosition
        totalIntervalMs = when (unitIndex) {
            0 -> intervalValue.toLong() * 1000L
            1 -> intervalValue.toLong() * 60 * 1000L
            2 -> intervalValue.toLong() * 3600 * 1000L
            else -> intervalValue.toLong() * 60 * 1000L
        }

        val intent = Intent(this, AlarmForegroundService::class.java).apply {
            action = "START_ALARM"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        isAlarmRunning = true
        updateButtonStates()
        Toast.makeText(this, "⏰ Alarm gestartet!", Toast.LENGTH_SHORT).show()
    }

    private fun stopAlarmService() {
        val intent = Intent(this, AlarmForegroundService::class.java).apply {
            action = "STOP_ALARM"
        }
        startService(intent)

        isAlarmRunning = false
        binding.circularCountdown.reset()
        updateButtonStates()
        Toast.makeText(this, "🛑 Alarm gestoppt!", Toast.LENGTH_SHORT).show()
    }

    private fun testPlayAudio() {
        stopTestAudio()
        val fileUri = prefs.getString("selected_file_uri", null)
        if (fileUri == null) {
            Toast.makeText(this, "Bitte zuerst eine Datei wählen!", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            testMediaPlayer = MediaPlayer().apply {
                setDataSource(this@MainActivity, Uri.parse(fileUri))
                val vol = binding.seekBarVolume.progress / 100f
                setVolume(vol, vol)
                prepare()
                start()
            }

            // Abspieldauer begrenzen falls aktiviert
            if (binding.cbLimitDuration.isChecked) {
                val maxSec = binding.etMaxSeconds.text.toString().toIntOrNull() ?: 10
                Handler(Looper.getMainLooper()).postDelayed({
                    stopTestAudio()
                }, maxSec * 1000L)
            }

            Toast.makeText(this, "▶️ Test-Wiedergabe...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Fehler: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopTestAudio() {
        testMediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        testMediaPlayer = null
    }

    private fun updateCountdownDisplay(remainingMs: Long) {
        if (remainingMs <= 0 || totalIntervalMs <= 0) {
            binding.circularCountdown.reset()
            return
        }
        binding.circularCountdown.setProgress(remainingMs, totalIntervalMs)
    }

    private fun updateButtonStates() {
        binding.btnStartAlarm.isEnabled = !isAlarmRunning
        binding.btnStopAlarm.isEnabled = isAlarmRunning
        binding.btnStartAlarm.alpha = if (isAlarmRunning) 0.5f else 1f
        binding.btnStopAlarm.alpha = if (isAlarmRunning) 1f else 0.5f

        // Status-Anzeige
        if (isAlarmRunning) {
            binding.tvStatus.text = "🟢 Alarm ist AKTIV"
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(this, android.R.color.holo_green_dark)
            )
        } else {
            binding.tvStatus.text = "🔴 Alarm ist INAKTIV"
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(this, android.R.color.holo_red_dark)
            )
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "Unbekannte Datei"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTestAudio()
    }
}
