package com.example.intervalalarm

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.*

class AlarmForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "interval_alarm_channel"
        const val CHANNEL_ALARM_ID = "interval_alarm_alert_channel"
        const val NOTIFICATION_ID = 1
        const val ALARM_NOTIFICATION_ID = 2
        private const val TAG = "AlarmForegroundService"
    }

    private lateinit var prefs: SharedPreferences
    private var mediaPlayer: MediaPlayer? = null
    private var countdownTimer: CountDownTimer? = null
    private var handler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isRunning = false
    private var nextAlarmTime: Long = 0
    private var wasOutsideTimeWindow = false   // neu

    // Neu für mehrere Alarme
    private lateinit var repository: ReminderRepository
    private var activeReminders = mutableListOf<Reminder>()
    private val timers = mutableMapOf<String, CountDownTimer>()   // id → Timer
    private val nextAlarmTimes = mutableMapOf<String, Long>()     // id → nächste Zeit

    override fun onCreate() {
    super.onCreate()
    prefs = getSharedPreferences("interval_alarm_prefs", MODE_PRIVATE)
    repository = ReminderRepository(this)
    handler = Handler(Looper.getMainLooper())
    createNotificationChannels()
  }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_ALARM" -> startAlarm()
            "STOP_ALARM" -> stopAlarm()
            "REQUEST_STATUS" -> sendStatusUpdate()
        }
        return START_STICKY // Neustart nach Kill durch System
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Kanal für dauerhaft angezeigte Service-Notification
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Interval Alarm Service",
                NotificationManager.IMPORTANCE_LOW // Kein Ton! Nur Anzeige
            ).apply {
                description = "Zeigt an, dass der Interval Alarm aktiv ist"
                setSound(null, null) // KEIN Systemsound!
            }

            // Kanal für die Alarm-Benachrichtigungen
            val alertChannel = NotificationChannel(
                CHANNEL_ALARM_ID,
                "Alarm Benachrichtigungen",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Benachrichtigungen wenn der Alarm feuert"
                setSound(null, null) // WIR spielen den Sound selbst ab!
                // WICHTIG: Kein System-Alarmton verwenden!
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun startAlarm() {
    if (isRunning) return

    // Alle Erinnerungen laden
    activeReminders = repository.loadReminders()
        .filter { it.enabled && !it.fileUri.isNullOrBlank() }
        .toMutableList()

    if (activeReminders.isEmpty()) {
        Log.w(TAG, "Keine aktiven Erinnerungen mit Audiodatei gefunden")
        stopSelf()
        return
    }

    isRunning = true

    // WakeLock
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = pm.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "IntervalAlarm::WakeLock"
    ).apply {
        acquire(24 * 60 * 60 * 1000L)
    }

    // Foreground starten
    val notification = buildServiceNotification("${activeReminders.size} Erinnerung(en) aktiv")
    startForeground(NOTIFICATION_ID, notification)

    // Für jede Erinnerung einen Timer starten
    for (reminder in activeReminders) {
        startTimerForReminder(reminder)
    }

    Log.d(TAG, "Service gestartet mit ${activeReminders.size} Erinnerungen")
}
    
       private fun startTimerForReminder(reminder: Reminder) {
    // Alten Timer für diese ID stoppen, falls vorhanden
    timers[reminder.id]?.cancel()

    val intervalMs = reminder.getIntervalMs()
    if (intervalMs <= 0) return

    val nextTime = System.currentTimeMillis() + intervalMs
    nextAlarmTimes[reminder.id] = nextTime

    val timer = object : CountDownTimer(intervalMs, 1000) {
        override fun onTick(millisUntilFinished: Long) {
            // Hier prüfen wir später das Zeitfenster pro Erinnerung
            // Vorerst nur den Countdown weiterlaufen lassen
        }

        override fun onFinish() {
            // Zeitfenster prüfen
            if (reminder.useTimeWindow && !isWithinTimeWindow(reminder)) {
                // Außerhalb → einfach neu starten ohne Sound
                startTimerForReminder(reminder)
                return
            }

            // Alarm auslösen
            playAlarmSound(reminder)

            if (reminder.customNotification) {
                showAlarmNotification(reminder)
            }

            // Nächsten Timer für diese Erinnerung starten
            startTimerForReminder(reminder)
        }
    }

    timers[reminder.id] = timer
    timer.start()
}
      



        private fun stopAlarm() {
        isRunning = false

        // Timer stoppen
        countdownTimer?.cancel()
        countdownTimer = null

        // MediaPlayer stoppen
        stopMediaPlayer()

        // WakeLock freigeben
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        // Status senden
        sendStatusUpdate()

        // Service beenden
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.d(TAG, "Alarm Service gestoppt")
    }

    private fun startNextTimer() {
        countdownTimer?.cancel()
        wasOutsideTimeWindow = false

        val intervalValue = prefs.getInt("interval_value", 5)
        val intervalUnit = prefs.getInt("interval_unit", 1) // 0=Sek, 1=Min, 2=Std

        val intervalMs = when (intervalUnit) {
            0 -> intervalValue * 1000L          // Sekunden
            1 -> intervalValue * 60 * 1000L     // Minuten
            2 -> intervalValue * 3600 * 1000L   // Stunden
            else -> intervalValue * 60 * 1000L
        }

        nextAlarmTime = System.currentTimeMillis() + intervalMs

        countdownTimer = object : CountDownTimer(intervalMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
    val useTimeWindow = prefs.getBoolean("use_time_window", false)
    val isOutside = useTimeWindow && !isWithinTimeWindow()

    if (isOutside) {
        // Nur einmal die Pausiert-Meldung setzen, wenn wir gerade das Zeitfenster verlassen
        if (!wasOutsideTimeWindow) {
            updateServiceNotification("⏸ Außerhalb des Zeitfensters - Pausiert")
            wasOutsideTimeWindow = true
        }
        sendCountdownUpdate(millisUntilFinished)
        return
    }

    // Wir sind wieder im erlaubten Zeitfenster
    wasOutsideTimeWindow = false

    // Notification & UI aktualisieren
    val hours = millisUntilFinished / 3600000
    val mins = (millisUntilFinished % 3600000) / 60000
    val secs = (millisUntilFinished % 60000) / 1000
    val timeStr = if (hours > 0) {
        String.format("Nächster Alarm in: %02d:%02d:%02d", hours, mins, secs)
    } else {
        String.format("Nächster Alarm in: %02d:%02d", mins, secs)
    }

    if (prefs.getBoolean("show_notification", true)) {
        updateServiceNotification("⏰ $timeStr")
    }

    sendCountdownUpdate(millisUntilFinished)
}

            override fun onFinish() {
                // Zeitfenster prüfen
                if (prefs.getBoolean("use_time_window", false) && !isWithinTimeWindow()) {
                    // Außerhalb des Fensters - nächsten Timer starten ohne Sound
                    startNextTimer()
                    return
                }

                // ALARM! Sound abspielen
                playAlarmSound()

                // Custom Notification senden (falls aktiviert)
                if (prefs.getBoolean("custom_notification", false)) {
                    showAlarmNotification()
                }

                // Nächsten Timer starten
                startNextTimer()
            }
        }.start()
    }

    private fun playAlarmSound(reminder: Reminder) {
    stopMediaPlayer()

    val fileUri = reminder.fileUri ?: return
    val volume = reminder.volume / 100f

    try {
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setDataSource(this@AlarmForegroundService, Uri.parse(fileUri))
            setVolume(volume, volume)
            prepare()
            start()
        }

        if (reminder.limitDuration) {
            handler?.postDelayed({
                stopMediaPlayer()
            }, reminder.maxSeconds * 1000L)
        } else {
            mediaPlayer?.setOnCompletionListener {
                stopMediaPlayer()
            }
        }

        Log.d(TAG, "Alarm Sound für \"${reminder.name}\" wird abgespielt")
    } catch (e: Exception) {
        Log.e(TAG, "Fehler beim Abspielen von ${reminder.name}: ${e.message}")
    }
}

    private fun stopMediaPlayer() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        mediaPlayer = null
    }

    private fun isWithinTimeWindow(reminder: Reminder): Boolean {
    val now = Calendar.getInstance()
    val currentTime = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

    val startTime = reminder.startHour * 60 + reminder.startMinute
    val endTime = reminder.endHour * 60 + reminder.endMinute

    return if (startTime <= endTime) {
        currentTime in startTime..endTime
    } else {
        // Über Mitternacht
        currentTime >= startTime || currentTime <= endTime
    }
}

    private fun showAlarmNotification(reminder: Reminder) {
    val text = reminder.notificationText.ifBlank { "Zeit für eine Pause! ⏰" }

    val openIntent = Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
        this, 0, openIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(this, CHANNEL_ALARM_ID)
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setContentTitle("⏰ ${reminder.name}")
        .setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .build()

    val manager = getSystemService(NotificationManager::class.java)
    // Jede Erinnerung bekommt eine eigene Notification-ID
    manager.notify(reminder.id.hashCode(), notification)
}

    private fun buildServiceNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop-Action in der Notification
        val stopIntent = Intent(this, AlarmForegroundService::class.java).apply {
            action = "STOP_ALARM"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ Interval Alarm")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true) // KEIN Sound!
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stoppen", stopPendingIntent)
            .build()
    }

    private fun updateServiceNotification(text: String) {
        val notification = buildServiceNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun getTotalIntervalMs(): Long {
        val intervalValue = prefs.getInt("interval_value", 5)
        val intervalUnit = prefs.getInt("interval_unit", 1)
        return when (intervalUnit) {
            0 -> intervalValue * 1000L
            1 -> intervalValue * 60 * 1000L
            2 -> intervalValue * 3600 * 1000L
            else -> intervalValue * 60 * 1000L
        }
    }

    private fun sendCountdownUpdate(remainingMs: Long) {
        val intent = Intent("COUNTDOWN_UPDATE").apply {
            putExtra("remaining_ms", remainingMs)
            putExtra("total_interval_ms", getTotalIntervalMs())
            putExtra("is_running", isRunning)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendStatusUpdate() {
        val remaining = if (isRunning) {
            maxOf(0, nextAlarmTime - System.currentTimeMillis())
        } else 0L

        val intent = Intent("COUNTDOWN_UPDATE").apply {
            putExtra("remaining_ms", remaining)
            putExtra("total_interval_ms", getTotalIntervalMs())
            putExtra("is_running", isRunning)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopAlarm()
    }
}
