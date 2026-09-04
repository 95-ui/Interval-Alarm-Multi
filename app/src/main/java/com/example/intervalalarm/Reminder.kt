package com.example.intervalalarm

data class Reminder(
    val id: String = java.util.UUID.randomUUID().toString(),  // eindeutige ID
    var name: String = "Neue Erinnerung",                     // Anzeigename
    var enabled: Boolean = true,                              // An/Aus

    // Intervall
    var intervalValue: Int = 5,
    var intervalUnit: Int = 1,                                // 0 = Sekunden, 1 = Minuten, 2 = Stunden

    // Audio
    var fileUri: String? = null,
    var fileName: String = "Keine Datei gewählt",
    var volume: Int = 80,                                     // 0–100
    var limitDuration: Boolean = false,
    var maxSeconds: Int = 10,

    // Zeitfenster
    var useTimeWindow: Boolean = false,
    var startHour: Int = 8,
    var startMinute: Int = 0,
    var endHour: Int = 22,
    var endMinute: Int = 0,

    // Benachrichtigung
    var showNotification: Boolean = true,
    var customNotification: Boolean = false,
    var notificationText: String = "Zeit für eine Pause! ⏰"
) {
    /** Intervall in Millisekunden berechnen */
    fun getIntervalMs(): Long {
        return when (intervalUnit) {
            0 -> intervalValue * 1000L
            1 -> intervalValue * 60 * 1000L
            2 -> intervalValue * 3600 * 1000L
            else -> intervalValue * 60 * 1000L
        }
    }
}
