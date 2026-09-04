package com.example.intervalalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Wird als Backup aufgerufen falls der Service neugestartet werden muss
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
