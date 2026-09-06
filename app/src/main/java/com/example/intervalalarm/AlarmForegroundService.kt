package com.example.intervalalarm

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
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
        const val PREFS_NAME = "interval_alarm_prefs"
        const val KEY_WAS_RUNNING = "alarm_was_running"
        private const val TAG = "AlarmForegroundService"

        /** Key, unter dem der Zeitpunkt der nächsten Auslösung einer
         *  Erinnerung gespeichert wird. Wird auch von MainActivity/Adapter
         *  gelesen, um einen Countdown IN DER APP anzuzeigen. */
        fun nextAlarmKey(reminderId: String) = "next_alarm_$reminderId"
    }

    private lateinit var prefs: SharedPreferences
    private var handler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isRunning = false
    private var isMuted = false

    private lateinit var repository: ReminderRepository
    private var activeReminders = mutableListOf<Reminder>()
    private val timers = mutableMapOf<String, CountDownTimer>()
    private val nextAlarmTimes = mutableMapOf<String, Long>()

    // Jede Erinnerung bekommt ihren EIGENEN MediaPlayer, damit sich mehrere
    // Töne nicht gegenseitig abwürgen.
    private val mediaPlayers = mutableMapOf<String, MediaPlayer>()
    private val stopCallbacks = mutableMapOf<String, Runnable>()
    private val loudnessEnhancers = mutableMapOf<String, LoudnessEnhancer>()

    // Aktualisiert einmal pro Sekunde die Dauer-Benachrichtigung mit dem
    // Countdown bis zur nächsten fälligen Erinnerung.
    private var tickerRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        repository = ReminderRepository(this)
        handler = Handler(Looper.getMainLooper())
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_ALARM" -> startAlarm()
            "STOP_ALARM" -> stopAlarm()

            // Eine einzelne Erinnerung live an-/ausschalten, während der
            // Dienst bereits läuft (Schalter in der Liste).
            "TOGGLE_REMINDER" -> {
                val id = intent.getStringExtra("reminder_id")
                val enabled = intent.getBooleanExtra("enabled", true)
                if (id != null) toggleReminder(id, enabled)
            }

            // Kurzfristig alle laufenden Töne stumm schalten / wieder laut
            // machen, über die Aktion in der Benachrichtigung.
            "TOGGLE_MUTE" -> toggleMute()

            else -> {
                // Der Service wurde vom System neu gestartet, ohne dass wir
                // explizit START_ALARM geschickt haben. Falls die Alarme
                // vorher liefen, machen wir sofort weiter. Android verlangt
                // außerdem, dass ein Service, der vorher im Vordergrund
                // lief, nach einem Neustart erneut sehr schnell
                // startForeground() aufruft – sonst kann es zum Absturz
                // kommen.
                if (!isRunning) {
                    if (prefs.getBoolean(KEY_WAS_RUNNING, false)) {
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
            startForeground(NOTIFICATION_ID, buildServiceNotification("Keine aktiven Erinnerungen"))
            stopSelf()
            return
        }

        isRunning = true
        isMuted = false
        prefs.edit().putBoolean(KEY_WAS_RUNNING, true).apply()

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

    /** Schaltet eine einzelne Erinnerung live an oder aus, während der
     *  Dienst bereits läuft – ohne alle anderen zu beeinflussen. */
    private fun toggleReminder(reminderId: String, enabled: Boolean) {
        if (!isRunning) return

        if (enabled) {
            if (activeReminders.none { it.id == reminderId }) {
                val reminder = repository.loadReminders().find { it.id == reminderId } ?: return
                if (reminder.fileUri.isNullOrBlank()) return
                activeReminders.add(reminder)
                startTimerForReminder(reminder)
            }
        } else {
            timers.remove(reminderId)?.cancel()
            nextAlarmTimes.remove(reminderId)
            prefs.edit().remove(nextAlarmKey(reminderId)).apply()
            stopMediaPlayerFor(reminderId)
            activeReminders.removeAll { it.id == reminderId }
        }

        if (activeReminders.isEmpty()) {
            stopAlarm()
        } else {
            updateServiceNotification(buildCountdownText())
        }
    }

    /** Schaltet ALLE gerade abgespielten/zukünftigen Töne kurzfristig stumm
     *  bzw. wieder laut, ohne die Countdown-Timer anzuhalten. */
    private fun toggleMute() {
        if (!isRunning) return
        isMuted = !isMuted

        for ((id, player) in mediaPlayers) {
            if (isMuted) {
                player.setVolume(0f, 0f)
            } else {
                val reminder = activeReminders.find { it.id == id }
                val volumePercent = reminder?.volume ?: 100
                val nativeVolume = if (volumePercent <= 100) volumePercent / 100f else 1f
                player.setVolume(nativeVolume, nativeVolume)
            }
        }

        updateServiceNotification(buildCountdownText())
    }

    private fun startTimerForReminder(reminder: Reminder) {
        timers[reminder.id]?.cancel()

        val intervalMs = reminder.getIntervalMs()
        if (intervalMs <= 0) return

        val nextTime = System.currentTimeMillis() + intervalMs
        nextAlarmTimes[reminder.id] = nextTime
        prefs.edit().putLong(nextAlarmKey(reminder.id), nextTime).apply()

        val timer = object : CountDownTimer(intervalMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                // Restzeit steht bereits in nextAlarmTimes/SharedPreferences.
            }

            override fun onFinish() {
                if (reminder.useTimeWindow && !isWithinTimeWindow(reminder)) {
                    startTimerForReminder(reminder)
                    return
                }

                playAlarmSound(reminder)

                if (reminder.showNotification) {
                    showAlarmNotification(reminder)
                }

                startTimerForReminder(reminder)
            }
        }

        timers[reminder.id] = timer
        timer.start()
    }

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
        prefs.edit().putBoolean(KEY_WAS_RUNNING, false).apply()

        tickerRunnable?.let { handler?.removeCallbacks(it) }
        tickerRunnable = null

        for (timer in timers.values) {
            timer.cancel()
        }
        timers.clear()

        val editor = prefs.edit()
        for (reminder in activeReminders) {
            editor.remove(nextAlarmKey(reminder.id))
        }
        editor.apply()

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

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(this@AlarmForegroundService, Uri.parse(fileUri))
                setOnCompletionListener { stopMediaPlayerFor(reminder.id) }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer Fehler bei \"${reminder.name}\": what=$what extra=$extra")
                    showPlaybackErrorNotification(reminder)
                    stopMediaPlayerFor(reminder.id)
                    true
                }
                prepare()
            }

            val enhancer = VolumeHelper.apply(player, reminder.volume)
            enhancer?.let { loudnessEnhancers[reminder.id] = it }

            if (isMuted) {
                player.setVolume(0f, 0f)
            }

            mediaPlayers[reminder.id] = player
            player.start()

            if (reminder.limitDuration) {
                val stopCallback = Runnable { stopMediaPlayerFor(reminder.id) }
                stopCallbacks[reminder.id] = stopCallback
                handler?.postDelayed(stopCallback, reminder.maxSeconds * 1000L)
            }

            Log.d(TAG, "Alarm Sound für \"${reminder.name}\" wird abgespielt")
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Abspielen von ${reminder.name}: ${e.message}")
            showPlaybackErrorNotification(reminder)
        }
    }

    private fun showPlaybackErrorNotification(reminder: Reminder) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ALARM_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ Ton konnte nicht abgespielt werden")
            .setContentText("\"${reminder.name}\": Bitte die Audiodatei in den Einstellungen erneut auswählen.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(("error_" + reminder.id).hashCode(), notification)
    }

    private fun stopMediaPlayerFor(reminderId: String) {
        stopCallbacks.remove(reminderId)?.let { handler?.removeCallbacks(it) }
        loudnessEnhancers.remove(reminderId)?.let {
            try { it.release() } catch (_: Exception) {}
        }
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

        val muteIntent = Intent(this, AlarmForegroundService::class.java).apply {
            action = "TOGGLE_MUTE"
        }
        val mutePendingIntent = PendingIntent.getService(
            this, 2, muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val muteLabel = if (isMuted) "🔊 Laut" else "🔇 Stumm"

        // Roter Punkt = stumm geschaltet, grüner Punkt = aktiv & laut.
        val dotColor = if (isMuted) Color.parseColor("#E53935") else Color.parseColor("#43A047")
        val statusText = if (isMuted) "$text · Stumm" else text

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setLargeIcon(buildStatusDot(dotColor))
            .setContentTitle("⏰ Interval Alarm Multi")
            .setContentText(statusText)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_lock_idle_alarm, muteLabel, mutePendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stoppen", stopPendingIntent)
            .build()
    }

    /** Zeichnet einen einfachen farbigen Punkt als Bitmap – dient als
     *  Status-Anzeige (rot = stumm, grün = aktiv) im großen Icon der
     *  Benachrichtigung. */
    private fun buildStatusDot(color: Int): Bitmap {
        val size = 64
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, paint)
        return bitmap
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
