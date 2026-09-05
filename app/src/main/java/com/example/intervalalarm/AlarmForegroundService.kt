package com.example.intervalalarm

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.*

class AlarmForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "interval_alarm_channel"
        const val CHANNEL_ALARM_ID = "interval_alarm_alert_channel"
        const val NOTIFICATION_ID = 1
        private const val TAG = "AlarmForegroundService"
    }

    private lateinit var prefs: SharedPreferences
    private var handler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isRunning = false

    // Mehrere, unabhängige Alarme
    private lateinit var repository: ReminderRepository
    private var activeReminders = mutableListOf<Reminder>()
    private val timers = mutableMapOf<String, CountDownTimer>()
    private val nextAlarmTimes = mutableMapOf<String, Long>()

    // WICHTIG: jede Erinnerung bekommt ihren EIGENEN MediaPlayer.
    // Vorher gab es nur einen einzigen MediaPlayer für alle Erinnerungen –
    // dadurch hat eine neu startende Erinnerung den Ton einer anderen,
    // gerade laufenden Erinnerung abgewürgt. Jetzt läuft jeder Ton wirklich
    // unabhängig von den anderen.
    private val mediaPlayers = mutableMapOf<String, MediaPlayer>()
    private val stopCallbacks = mutableMapOf<String, Runnable>()

    // Aktualisiert einmal pro Sekunde die Dauer-Benachrichtigung mit dem
    // Countdown bis zur nächsten fälligen Erinnerung.
    private var tickerRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("interval_alarm_prefs", MODE_PRIVATE)
        repository = ReminderRepository(this)
        handler = Handler(Looper.getMainLooper())
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_ALARM" -> startAlarm()
            "STOP_ALARM" -> stopAlarm()
            else -> {
                // Der Service wurde vom System neu gestartet (z. B. weil der
                // Prozess gekillt wurde), ohne dass wir explizit START_ALARM
                // geschickt haben. Falls die Alarme vorher liefen, machen wir
                // sofort weiter. Android verlangt außerdem, dass ein Service,
                // der vorher im Vordergrund lief, nach einem Neustart erneut
                // sehr schnell startForeground() aufruft – sonst kann es zum
                // Absturz kommen.
                if (!isRunning) {
                    if (prefs.getBoolean("alarm_was_running", false)) {
                        startAlarm()
                    } else {
                        startForeground(NOTIFICATION_ID, buildServiceNotification("Bereit"))
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Interval Alarm Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zeigt an, dass der Interval Alarm aktiv ist"
                setSound(null, null)
            }

            val alertChannel = NotificationChannel(
                CHANNEL_ALARM_ID,
                "Alarm Benachrichtigungen",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Benachrichtigungen wenn der Alarm feuert"
                setSound(null, null)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun startAlarm() {
        if (isRunning) return

        activeReminders = repository.loadReminders()
            .filter { it.enabled && !it.fileUri.isNullOrBlank() }
            .toMutableList()

        if (activeReminders.isEmpty()) {
            Log.w(TAG, "Keine aktiven Erinnerungen mit Audiodatei gefunden")
            stopSelf()
            return
        }

        isRunning = true
        // Merken, dass die Alarme laufen -> der BootReceiver liest genau
        // dieses Flag nach einem Geräte-Neustart aus.
        prefs.edit().putBoolean("alarm_was_running", true).apply()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "IntervalAlarm::WakeLock"
        ).apply {
            acquire(24 * 60 * 60 * 1000L)
        }

        startForeground(
            NOTIFICATION_ID,
            buildServiceNotification("${activeReminders.size} Erinnerung(en) aktiv")
        )

        for (reminder in activeReminders) {
            startTimerForReminder(reminder)
        }

        startTicker()

        Log.d(TAG, "Service gestartet mit ${activeReminders.size} Erinnerungen")
    }

    private fun startTimerForReminder(reminder: Reminder) {
        timers[reminder.id]?.cancel()

        val intervalMs = reminder.getIntervalMs()
        if (intervalMs <= 0) return

        nextAlarmTimes[reminder.id] = System.currentTimeMillis() + intervalMs

        val timer = object : CountDownTimer(intervalMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                // Die Restzeit steht bereits in nextAlarmTimes[reminder.id];
                // der zentrale Ticker (startTicker) liest sie für die
                // Benachrichtigung aus, damit wir nicht jede Sekunde pro
                // Erinnerung einzeln etwas aktualisieren müssen.
            }

            override fun onFinish() {
                if (reminder.useTimeWindow && !isWithinTimeWindow(reminder)) {
                    // Außerhalb des Zeitfensters: einfach weiterzählen,
                    // ohne den Ton abzuspielen.
                    startTimerForReminder(reminder)
                    return
                }

                playAlarmSound(reminder)

                if (reminder.customNotification) {
                    showAlarmNotification(reminder)
                }

                startTimerForReminder(reminder)
            }
        }

        timers[reminder.id] = timer
        timer.start()
    }

    /** Läuft jede Sekunde, solange der Service aktiv ist, und zeigt in der
     *  Dauer-Benachrichtigung an, wann die nächste Erinnerung fällig ist. */
    private fun startTicker() {
        tickerRunnable?.let { handler?.removeCallbacks(it) }
        tickerRunnable = object : Runnable {
            override fun run() {
                if (!isRunning) return
                updateServiceNotification(buildCountdownText())
                handler?.postDelayed(this, 1000L)
            }
        }
        handler?.post(tickerRunnable!!)
    }

    private fun buildCountdownText(): String {
        val fallback = "${activeReminders.size} Erinnerung(en) aktiv"
        if (nextAlarmTimes.isEmpty()) return fallback

        val nextEntry = nextAlarmTimes.minByOrNull { it.value } ?: return fallback
        val reminder = activeReminders.find { it.id == nextEntry.key } ?: return fallback

        val remaining = (nextEntry.value - System.currentTimeMillis()).coerceAtLeast(0L)
        val minutes = remaining / 60000
        val seconds = (remaining % 60000) / 1000

        return "Nächste: ${reminder.name} in %02d:%02d · ${activeReminders.size} aktiv"
            .format(minutes, seconds)
    }

    private fun stopAlarm() {
        isRunning = false
        prefs.edit().putBoolean("alarm_was_running", false).apply()

        tickerRunnable?.let { handler?.removeCallbacks(it) }
        tickerRunnable = null

        for (timer in timers.values) {
            timer.cancel()
        }
        timers.clear()
        nextAlarmTimes.clear()
        activeReminders.clear()

        stopAllMediaPlayers()

        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.d(TAG, "Service gestoppt – alle Erinnerungen beendet")
    }

    private fun playAlarmSound(reminder: Reminder) {
        stopMediaPlayerFor(reminder.id)

        val fileUri = reminder.fileUri ?: return
        val volume = reminder.volume / 100f

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(this@AlarmForegroundService, Uri.parse(fileUri))
                setVolume(volume, volume)
                setOnCompletionListener {
                    stopMediaPlayerFor(reminder.id)
                }
                prepare()
                start()
            }
            mediaPlayers[reminder.id] = player

            if (reminder.limitDuration) {
                val stopCallback = Runnable { stopMediaPlayerFor(reminder.id) }
                stopCallbacks[reminder.id] = stopCallback
                handler?.postDelayed(stopCallback, reminder.maxSeconds * 1000L)
            }

            Log.d(TAG, "Alarm Sound für \"${reminder.name}\" wird abgespielt")
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Abspielen von ${reminder.name}: ${e.message}")
        }
    }

    private fun stopMediaPlayerFor(reminderId: String) {
        stopCallbacks.remove(reminderId)?.let { handler?.removeCallbacks(it) }
        mediaPlayers.remove(reminderId)?.let { player ->
            try {
                if (player.isPlaying) player.stop()
                player.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun stopAllMediaPlayers() {
        for (id in mediaPlayers.keys.toList()) {
            stopMediaPlayerFor(id)
        }
    }

    private fun isWithinTimeWindow(reminder: Reminder): Boolean {
        val now = Calendar.getInstance()
        val currentTime = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val startTime = reminder.startHour * 60 + reminder.startMinute
        val endTime = reminder.endHour * 60 + reminder.endMinute

        return if (startTime <= endTime) {
            currentTime in startTime..endTime
        } else {
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

        val stopIntent = Intent(this, AlarmForegroundService::class.java).apply {
            action = "STOP_ALARM"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ Interval Alarm Multi")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stoppen", stopPendingIntent)
            .build()
    }

    private fun updateServiceNotification(text: String) {
        val notification = buildServiceNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (isRunning) {
            stopAlarm()
        }
    }
}
