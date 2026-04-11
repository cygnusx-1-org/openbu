package org.cygnusx1.openbu.data

import android.content.Context
import org.json.JSONObject

private data class HmsEntry(val primary: String, val paths: List<String>)

object HmsLookup {
    private var lookup: Map<String, HmsEntry>? = null

    private fun getLookup(context: Context): Map<String, HmsEntry> {
        lookup?.let { return it }
        val json = context.assets.open("hms-errors.json").bufferedReader().use { it.readText() }
        val obj = JSONObject(json)
        val map = mutableMapOf<String, HmsEntry>()
        for (key in obj.keys()) {
            val entry = obj.getJSONObject(key)
            val pathsArr = entry.getJSONArray("paths")
            map[key] = HmsEntry(
                primary = entry.getString("primary"),
                paths = (0 until pathsArr.length()).map { pathsArr.getString(it) },
            )
        }
        lookup = map
        return map
    }

    // Ordered list of wiki tags to try for a given series, most-specific first.
    // H2 family may have model-specific sections (h2c, h2d, h2d-pro, h2s) as well
    // as the shared h2 section; try the specific one first.
    private fun preferredTags(series: PrinterSeries): List<String> = when (series) {
        PrinterSeries.H2 -> listOf("h2c", "h2d", "h2d-pro", "h2s", "h2")
        else             -> listOf(series.wikiTag)
    }

    /**
     * Resolve wiki info for a given HMS code and printer series.
     *
     * - Code in table, printer tag matches → [Match] using the entry's [primary] code in the URL.
     * - Code in table, no tag matches → [NoMatch] with available paths shown to the user.
     * - Code not in table → [Match] using the printer's default tag and the raw hmsCode
     *   (page may not exist, but it's the best guess per spec).
     */
    fun resolve(context: Context, hmsCode: String, series: PrinterSeries): HmsWikiResult {
        val entry = getLookup(context)[hmsCode]

        if (entry == null) {
            val defaultTag = preferredTags(series).last()
            return HmsWikiResult.Match(
                "https://wiki.bambulab.com/en/$defaultTag/troubleshooting/hmscode/$hmsCode"
            )
        }

        val matched = preferredTags(series).firstOrNull { it in entry.paths }
        return if (matched != null) {
            HmsWikiResult.Match(
                "https://wiki.bambulab.com/en/$matched/troubleshooting/hmscode/${entry.primary}"
            )
        } else {
            HmsWikiResult.NoMatch(entry.paths)
        }
    }
}

sealed class HmsWikiResult {
    data class Match(val url: String) : HmsWikiResult()
    /** Code is in the table but has no page for this printer model. [availablePaths] are shown inline. */
    data class NoMatch(val availablePaths: List<String>) : HmsWikiResult()
}
