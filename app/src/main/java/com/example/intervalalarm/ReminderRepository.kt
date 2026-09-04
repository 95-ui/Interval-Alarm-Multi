package com.example.intervalalarm.multi

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class ReminderRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("interval_alarm_multi_prefs", Context.MODE_PRIVATE)

    fun loadReminders(): MutableList<Reminder> {
        val json = prefs.getString("reminders", null) ?: return mutableListOf()
        val list = mutableListOf<Reminder>()

        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    Reminder(
                        id = obj.getString("id"),
                        name = obj.optString("name", "Erinnerung"),
                        enabled = obj.optBoolean("enabled", true),
                        intervalValue = obj.optInt("intervalValue", 5),
                        intervalUnit = obj.optInt("intervalUnit", 1),
                        fileUri = obj.optString("fileUri", null),
                        fileName = obj.optString("fileName", "Keine Datei gewählt"),
                        volume = obj.optInt("volume", 80),
                        limitDuration = obj.optBoolean("limitDuration", false),
                        maxSeconds = obj.optInt("maxSeconds", 10),
                        useTimeWindow = obj.optBoolean("useTimeWindow", false),
                        startHour = obj.optInt("startHour", 8),
                        startMinute = obj.optInt("startMinute", 0),
                        endHour = obj.optInt("endHour", 22),
                        endMinute = obj.optInt("endMinute", 0),
                        showNotification = obj.optBoolean("showNotification", true),
                        customNotification = obj.optBoolean("customNotification", false),
                        notificationText = obj.optString("notificationText", "Zeit für eine Pause! ⏰")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveReminders(reminders: List<Reminder>) {
        val array = JSONArray()
        for (r in reminders) {
            val obj = JSONObject().apply {
                put("id", r.id)
                put("name", r.name)
                put("enabled", r.enabled)
                put("intervalValue", r.intervalValue)
                put("intervalUnit", r.intervalUnit)
                put("fileUri", r.fileUri)
                put("fileName", r.fileName)
                put("volume", r.volume)
                put("limitDuration", r.limitDuration)
                put("maxSeconds", r.maxSeconds)
                put("useTimeWindow", r.useTimeWindow)
                put("startHour", r.startHour)
                put("startMinute", r.startMinute)
                put("endHour", r.endHour)
                put("endMinute", r.endMinute)
                put("showNotification", r.showNotification)
                put("customNotification", r.customNotification)
                put("notificationText", r.notificationText)
            }
            array.put(obj)
        }
        prefs.edit().putString("reminders", array.toString()).apply()
    }
}
