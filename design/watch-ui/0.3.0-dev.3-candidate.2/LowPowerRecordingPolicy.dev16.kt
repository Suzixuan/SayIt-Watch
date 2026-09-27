package com.sayit.watch.settings

/** User-configurable delay before the recording UI enters its low-power presentation. */
object LowPowerRecordingPolicy {
    const val DEFAULT_AFTER_SECONDS: Int = 10
    const val MIN_AFTER_SECONDS: Int = 1
    const val MAX_AFTER_SECONDS: Int = 180

    fun parseSeconds(value: String): Int? =
        value.trim().toIntOrNull()?.takeIf { it in MIN_AFTER_SECONDS..MAX_AFTER_SECONDS }

    fun sanitizeStored(value: Int): Int =
        value.takeIf { it in MIN_AFTER_SECONDS..MAX_AFTER_SECONDS } ?: DEFAULT_AFTER_SECONDS
}
