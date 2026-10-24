package com.benfridge.lightmark.data

/**
 * How large the reader sets its text, as a multiplier on the LightOS type scale.
 *
 * Three steps rather than a slider. A slider on this screen would need a control
 * surface the reader does not have, and the useful range is narrow: below about 0.8 the
 * text stops being comfortable on a 3.92" panel, and above about 1.25 a page holds so
 * little that turning becomes the main activity.
 */
object TextScale {

    /** Smallest first. */
    val STEPS: List<Float> = listOf(0.85f, 1.0f, 1.2f)

    const val DEFAULT: Float = 1.0f

    /** The step nearest [value], so a stored number can never fall between steps. */
    fun nearest(value: Float): Float =
        STEPS.minByOrNull { kotlin.math.abs(it - value) } ?: DEFAULT

    /** The next step up, wrapping back to the smallest. */
    fun next(current: Float): Float {
        val index = STEPS.indexOf(nearest(current))
        return STEPS[(index + 1) % STEPS.size]
    }

    fun labelFor(value: Float): String = when (STEPS.indexOf(nearest(value))) {
        0 -> "Small"
        1 -> "Medium"
        else -> "Large"
    }
}
