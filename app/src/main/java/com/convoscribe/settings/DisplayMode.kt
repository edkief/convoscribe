package com.convoscribe.settings

/**
 * How the app is drawn. [AUTO] uses [EINK] on an e-ink device (Onyx
 * Boox) and [STANDARD] everywhere else. [key] is the persisted value of
 * [AppSettings.displayMode].
 */
enum class DisplayMode(val key: String) {
    AUTO("auto"),
    STANDARD("standard"),
    EINK("eink");

    companion object {
        /** The mode stored as [key], or `null` for an unknown value. */
        fun fromKey(key: String): DisplayMode? = entries.firstOrNull { it.key == key }

        /**
         * `true` when the device has an e-ink screen. Onyx Boox devices
         * report "ONYX" as the manufacturer and an Onyx or Boox brand.
         */
        fun isEinkDevice(manufacturer: String, brand: String): Boolean = listOf(manufacturer, brand).any { value ->
            EINK_VENDORS.any { value.contains(it, ignoreCase = true) }
        }

        /** Whether [mode] draws the e-ink variant on a device where [einkDevice] holds. */
        fun resolvesToEink(mode: DisplayMode, einkDevice: Boolean): Boolean = when (mode) {
            AUTO -> einkDevice
            STANDARD -> false
            EINK -> true
        }

        private val EINK_VENDORS = listOf("onyx", "boox")
    }
}
