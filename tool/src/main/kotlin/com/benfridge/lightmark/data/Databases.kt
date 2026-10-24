package com.benfridge.lightmark.data

import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase

/**
 * One database for the process.
 *
 * Room expects a single instance per file. Each `build()` opens its own connection
 * pool, and the library, the chapter list and the reader were each building their own,
 * so navigating into a chapter left three live against the same file. Harmless while
 * nothing observes invalidation, but it is three sets of connections and a writer
 * contending with itself for no reason.
 *
 * Holding it statically does not leak: `buildDatabase` builds against the application
 * context, not the activity.
 */
object Databases {

    const val NAME: String = "lightmark.db"

    @Volatile
    private var instance: LightmarkDatabase? = null

    fun highlights(context: SealedLightContext): HighlightDao = database(context).highlights()

    private fun database(context: SealedLightContext): LightmarkDatabase =
        instance ?: synchronized(this) {
            instance ?: context
                .buildDatabase(LightmarkDatabase::class.java, NAME)
                .also { instance = it }
        }
}
