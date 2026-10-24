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

    /**
     * On the LP3's 419ppi panel these come out at roughly 2.3mm, 2.7mm, 3.1mm and
     * 3.8mm of type. A paperback sets body text at about 3.5mm, so Medium is already
     * a little smaller than a book and Tiny is noticeably so — sharp at this pixel
     * density, but small. Below about 0.6 it stops being comfortable to read for long.
     */
    const val TINY: Float = 0.72f
    const val SMALL: Float = 0.85f
    const val DEFAULT: Float = 1.0f
    const val LARGE: Float = 1.2f

    /** Smallest first. */
    val STEPS: List<Float> = listOf(TINY, SMALL, DEFAULT, LARGE)

    /** The step nearest [value], so a stored number can never fall between steps. */
    fun nearest(value: Float): Float =
        STEPS.minByOrNull { kotlin.math.abs(it - value) } ?: DEFAULT

    /** The next step up, wrapping back to the smallest. */
    fun next(current: Float): Float {
        val index = STEPS.indexOf(nearest(current))
        return STEPS[(index + 1) % STEPS.size]
    }

    fun labelFor(value: Float): String = when (nearest(value)) {
        TINY -> "Tiny"
        SMALL -> "Small"
        LARGE -> "Large"
        else -> "Medium"
    }
}
