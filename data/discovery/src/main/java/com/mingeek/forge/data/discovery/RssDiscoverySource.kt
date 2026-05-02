package com.mingeek.forge.data.discovery

import android.util.Xml
import com.mingeek.forge.domain.DiscoveredModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Base [DiscoverySource] for sources that publish a standard RSS 2.0 feed.
 * Concrete sources supply the feed URL and a transform that maps an
 * [RssItem] to a [DiscoveredModel] (or null to skip).
 *
 * The base only knows about RSS — it doesn't decide what counts as a
 * "model". That's the transform's job, so a HF Blog source can filter
 * by model-family keywords while a Reddit source can extract HF repo
 * URLs from post bodies.
 */
abstract class RssDiscoverySource(
    private val httpClient: OkHttpClient,
    private val feedUrl: String,
) : DiscoverySource {

    /** Map a feed item to a [DiscoveredModel], or return null to drop it. */
    protected abstract fun transform(item: RssItem): DiscoveredModel?

    override suspend fun fetchSignals(): List<DiscoveredModel> = withContext(Dispatchers.IO) {
        val xml = runCatching { fetchFeedBody() }.getOrNull() ?: return@withContext emptyList()
        val items = runCatching { parseItems(xml) }.getOrDefault(emptyList())
        items.mapNotNull { runCatching { transform(it) }.getOrNull() }
    }

    private fun fetchFeedBody(): String? {
        val request = Request.Builder().url(feedUrl).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    private fun parseItems(xml: String): List<RssItem> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        val items = mutableListOf<RssItem>()
        var current: MutableMap<String, String>? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "item", "entry" -> current = mutableMapOf()
                        else -> if (current != null) {
                            val tag = parser.name
                            // <link href="..."/> in Atom; <link>...</link> in RSS.
                            val href = parser.getAttributeValue(null, "href")
                            if (tag == "link" && href != null) {
                                current["link"] = href
                            } else {
                                val text = readNextText(parser)
                                if (text != null) current[tag] = text
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "item" || parser.name == "entry") {
                        current?.let { items += toRssItem(it) }
                        current = null
                    }
                }
            }
            event = parser.next()
        }
        return items
    }

    private fun readNextText(parser: XmlPullParser): String? {
        if (parser.next() != XmlPullParser.TEXT) return null
        val text = parser.text
        // Advance past the END_TAG so the outer loop sees the next sibling.
        if (parser.next() != XmlPullParser.END_TAG) {
            // Mixed content — best effort; just return whatever text we got.
        }
        return text?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun toRssItem(map: Map<String, String>): RssItem = RssItem(
        title = map["title"].orEmpty(),
        link = map["link"].orEmpty(),
        description = map["description"] ?: map["summary"] ?: map["content"].orEmpty(),
        publishedAt = parseDate(map["pubDate"] ?: map["published"] ?: map["updated"]),
    )

    private fun parseDate(text: String?): Instant? {
        if (text.isNullOrBlank()) return null
        // Try a couple of common formats.
        val formats = listOf(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ISO_ZONED_DATE_TIME,
        )
        for (fmt in formats) {
            runCatching { return ZonedDateTime.parse(text, fmt).toInstant() }
        }
        return null
    }
}

data class RssItem(
    val title: String,
    val link: String,
    val description: String,
    val publishedAt: Instant?,
)
