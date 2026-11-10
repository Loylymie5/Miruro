package org.miruro.cloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class MiruroProvider : MainAPI() {
    override var name = "Miruro"
    override var mainUrl = "https://www.miruro.cx"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime)
    override val hasMainPage = true
    override var hasQuickSearch = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val base64Url = Base64.getUrlEncoder().withoutPadding()
    private val base64UrlDecoder = Base64.getUrlDecoder()

    private fun request(
        url: String,
        method: String = "GET",
        body: String? = null,
        referer: String = "$mainUrl/"
    ): ByteArray {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .header("Accept", "application/json, text/plain, */*")

        if (method == "POST") {
            builder.post((body ?: "{}").toRequestBody(jsonType))
        }

        return http.newCall(builder.build()).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "HTTP ${response.code}: ${bytes.toString(Charsets.UTF_8).take(180)}"
                )
            }
            bytes
        }
    }

    private fun requestText(url: String, method: String = "GET", body: String? = null): String =
        request(url, method, body).toString(Charsets.UTF_8)

    private fun anilist(query: String, page: Int = 1, sort: String = "POPULARITY_DESC"): JSONArray {
        val gql = """
            query (${'$'}page:Int, ${'$'}search:String) {
              Page(page:${'$'}page, perPage:20) {
                media(type:ANIME, search:${'$'}search, sort:[$sort], isAdult:false) {
                  id idMal format status episodes averageScore popularity seasonYear
                  title { romaji english native }
                  coverImage { extraLarge large }
                }
              }
            }
        """.trimIndent()
        val variables = JSONObject()
            .put("page", page)
            .put("search", query.ifBlank { JSONObject.NULL })
        val payload = JSONObject().put("query", gql).put("variables", variables)
        val result = JSONObject(requestText("https://graphql.anilist.co", "POST", payload.toString()))
        return result.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: JSONArray()
    }

    private fun anilistById(id: Int): JSONObject? {
        val gql = """
            query {
              Media(id:$id, type:ANIME) {
                id title { romaji english } description genres seasonYear status
                coverImage { extraLarge large }
              }
            }
        """.trimIndent()
        return JSONObject(requestText("https://graphql.anilist.co", "POST", JSONObject().put("query", gql).toString()))
            .optJSONObject("data")?.optJSONObject("Media")
    }

    private fun mediaTitle(media: JSONObject): String {
        val title = media.optJSONObject("title")
        return title?.optString("english")?.takeIf { it.isNotBlank() && it != "null" }
            ?: title?.optString("romaji")?.takeIf { it.isNotBlank() && it != "null" }
            ?: "Unknown anime"
    }

    private fun mediaUrl(media: JSONObject) = "$mainUrl/anilist/${media.optInt("id")}"

    private fun poster(media: JSONObject): String? =
        media.optJSONObject("coverImage")?.optString("extraLarge")?.takeIf { it.startsWith("http") }
            ?: media.optJSONObject("coverImage")?.optString("large")?.takeIf { it.startsWith("http") }

    private fun toSearch(media: JSONObject): SearchResponse =
        newAnimeSearchResponse(mediaTitle(media), mediaUrl(media), TvType.Anime) {
            posterUrl = poster(media)
        }

    override suspend fun search(query: String): List<SearchResponse> =
        anilist(query, 1, "SEARCH_MATCH").toObjectList().map(::toSearch)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val popular = anilist("", page, "POPULARITY_DESC").toObjectList().map(::toSearch)
        val trending = anilist("", page, "TRENDING_DESC").toObjectList().map(::toSearch)
        return newHomePageResponse(
            listOf(HomePageList("Popular anime", popular), HomePageList("Trending anime", trending)),
            hasNext = page < 5
        )
    }

    /**
     * Miruro Pipe request. The request object is base64url-encoded JSON in the `e` query parameter.
     * The endpoint response is base64url-encoded gzip JSON.
     */
    private fun pipe(path: String, query: JSONObject): JSONObject {
        val payload = JSONObject()
            .put("path", path)
            .put("method", "GET")
            .put("query", query)
            .put("body", JSONObject.NULL)
            .put("version", "0.1.0")
        val encoded = base64Url.encodeToString(payload.toString().toByteArray(Charsets.UTF_8))
        val raw = request("$mainUrl/api/secure/pipe?e=$encoded")
        val text = decodePipeResponse(raw)
        return JSONObject(text)
    }

    private fun decodePipeResponse(raw: ByteArray): String {
        // Pipe responses are base64url text containing gzip-compressed JSON.
        val compact = raw.toString(Charsets.UTF_8).trim()
        val compressed = try {
            base64UrlDecoder.decode(compact)
        } catch (e: IllegalArgumentException) {
            // Some deployments may return plain JSON; accept that only when it is clearly JSON.
            if (compact.startsWith("{") || compact.startsWith("[")) return compact
            throw IllegalStateException("Miruro Pipe returned an unexpected response")
        }
        val decoded = try {
            GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
        } catch (e: Exception) {
            // Also accept a base64url-encoded, uncompressed JSON response.
            compressed
        }
        return decoded.toString(Charsets.UTF_8)
    }

    private fun JSONArray.toObjectList(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }

    private fun safeText(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = obj.optString(key).trim()
            if (value.isNotEmpty() && value != "null") return value
        }
        return null
    }

    private fun encodeEpisodeId(id: String): String =
        base64Url.encodeToString(id.toByteArray(Charsets.UTF_8))

    private fun episodeData(anilistId: Int, provider: String, category: String, episodeId: String): String =
        "miruro://episode/$anilistId/${provider.urlEncode()}/$category/${encodeEpisodeId(episodeId)}"

    private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("(?:/anilist/|miruro://anilist/)(\\d+)/?$").find(url)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val media = runCatching { anilistById(id) }.getOrNull() ?: return null
        val title = mediaTitle(media)

        val episodeResponse = runCatching {
            pipe("episodes", JSONObject().put("anilistId", id))
        }.getOrElse { return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster(media)
            plot = media.optString("description").replace(Regex("<[^>]*>"), " ").takeIf { it.isNotBlank() && it != "null" }
            tags = media.optJSONArray("genres")?.toStringList().orEmpty()
            year = media.optInt("seasonYear").takeIf { it > 0 }
            addEpisodes(DubStatus.Subbed, emptyList())
        } }

        val providers = episodeResponse.optJSONObject("providers") ?: JSONObject()
        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()
        val providerKeys = providers.keys()

        while (providerKeys.hasNext()) {
            val providerKey = providerKeys.next()
            val providerObj = providers.optJSONObject(providerKey) ?: continue
            val episodeGroups = providerObj.optJSONObject("episodes") ?: providerObj
            for (category in listOf("sub", "dub")) {
                val list = episodeGroups.optJSONArray(category) ?: continue
                for (ep in list.toObjectList()) {
                    val originalId = safeText(ep, "id", "episodeId", "episode_id") ?: continue
                    val number = ep.optDouble("number", ep.optDouble("episode", -1.0))
                    if (number <= 0.0) continue
                    val numberLabel = if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()
                    val episode = newEpisode(episodeData(id, providerKey, category, originalId)) {
                        name = safeText(ep, "title", "name") ?: "Episode $numberLabel"
                        this.episode = number.toInt()
                        posterUrl = safeText(ep, "image", "thumbnail", "thumbnail_url") ?: poster(media)
                        description = safeText(ep, "airDate", "air_date", "duration")
                    }
                    if (category == "dub") dubEpisodes.add(episode) else subEpisodes.add(episode)
                }
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster(media)
            plot = media.optString("description").replace(Regex("<[^>]*>"), " ").takeIf { it.isNotBlank() && it != "null" }
            tags = media.optJSONArray("genres")?.toStringList().orEmpty()
            year = media.optInt("seasonYear").takeIf { it > 0 }
            showStatus = when (media.optString("status")) {
                "RELEASING" -> ShowStatus.Ongoing
                "FINISHED" -> ShowStatus.Completed
                else -> ShowStatus.Ongoing
            }
            addEpisodes(DubStatus.Subbed, subEpisodes.distinctBy { it.data })
            addEpisodes(DubStatus.Dubbed, dubEpisodes.distinctBy { it.data })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val match = Regex("""miruro://episode/(\\d+)/([^/]+)/(sub|dub)/([^/]+)""").find(data) ?: return false
        val anilistId = match.groupValues[1].toIntOrNull() ?: return false
        val provider = java.net.URLDecoder.decode(match.groupValues[2], "UTF-8")
        val category = match.groupValues[3]
        val episodeId = try {
            base64UrlDecoder.decode(match.groupValues[4]).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            return false
        }
        val encodedEpisodeId = encodeEpisodeId(episodeId)
        val result = runCatching {
            pipe(
                "sources",
                JSONObject()
                    .put("episodeId", encodedEpisodeId)
                    .put("provider", provider)
                    .put("category", category)
                    .put("anilistId", anilistId)
            )
        }.getOrElse { return false }

        var emitted = false
        val subtitles = result.optJSONArray("subtitles") ?: JSONArray()
        for (sub in subtitles.toObjectList()) {
            val subtitleUrl = safeText(sub, "file", "url") ?: continue
            if (!subtitleUrl.startsWith("http")) continue
            subtitleCallback(SubtitleFile(safeText(sub, "label", "language", "lang") ?: "Subtitle", subtitleUrl))
        }

        val streams = result.optJSONArray("streams") ?: JSONArray()
        for (stream in streams.toObjectList()) {
            val streamUrl = safeText(stream, "url", "file") ?: continue
            if (!streamUrl.startsWith("http")) continue
            val typeText = safeText(stream, "type", "format")?.lowercase().orEmpty()
            val linkType = if (typeText.contains("m3u8") || typeText.contains("hls") || streamUrl.contains(".m3u8")) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            val qualityText = safeText(stream, "quality", "label").orEmpty()
            val quality = Regex("(\\d{3,4})").find(qualityText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Qualities.Unknown.value
            callback(
                newExtractorLink(
                    source = "Miruro • $provider • ${category.uppercase()}",
                    name = "Miruro • ${qualityText.ifBlank { provider }}",
                    url = streamUrl,
                    type = linkType
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = quality
                }
            )
            emitted = true
        }
        return emitted
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it).takeIf { value -> value.isNotBlank() && value != "null" } }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
