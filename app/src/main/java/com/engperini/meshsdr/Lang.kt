package com.engperini.meshsdr

import java.util.Locale

/** App language: English or Portuguese (Brazil). Follows the system unless chosen in the app. */
object Lang {
    @Volatile var portuguese: Boolean = Locale.getDefault().language == "pt"

    /** Applies a saved choice: "pt", "en", or null for the system language. */
    fun apply(code: String?) {
        portuguese = when (code) {
            "pt" -> true
            "en" -> false
            else -> Locale.getDefault().language == "pt"
        }
    }
}

/** The text in the current app language. */
fun tr(en: String, pt: String): String = if (Lang.portuguese) pt else en
