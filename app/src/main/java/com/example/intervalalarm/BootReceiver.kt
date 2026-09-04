package com.example.intervalalarm.multi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Prüfen ob der Alarm vorher aktiv war
            val prefs = context.getSharedPreferences("interval_alarm_prefs", Context.MODE_PRIVATE)
            val wasRunning = prefs.getBoolean("alarm_was_running", false)

            if (wasRunning) {
                val serviceIntent = Intent(context, AlarmForegroundService::class.java).apply {
                    action = "START_ALARM"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
