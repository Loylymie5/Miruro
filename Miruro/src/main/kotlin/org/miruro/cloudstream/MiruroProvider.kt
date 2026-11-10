package org.miruro.cloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
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
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val catalogKey = "miruro/catalog".toByteArray(Charsets.US_ASCII)
    private val streamingConfig: JSONObject by lazy { getJson("$mainUrl/api/config").optJSONObject("streaming") ?: JSONObject() }

    private fun request(url: String, method: String = "GET", body: String? = null, referer: String = "$mainUrl/"): String {
        val builder = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .header("Accept", "application/json, application/octet-stream;q=0.9, */*;q=0.8")
        if (method == "POST") builder.post((body ?: "{}").toRequestBody(jsonType))
        return http.newCall(builder.build()).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) throw IllegalStateException("Miruro HTTP ${response.code}: ${bytes.toString(Charsets.UTF_8).take(180)}")
            val contentType = response.header("Content-Type").orEmpty().substringBefore(';').trim()
            if (contentType == "application/octet-stream") decodeCatalog(bytes)
            else bytes.toString(Charsets.UTF_8)
        }
    }

    private fun getJson(url: String): JSONObject = JSONObject(request(url))
    private fun api(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val query = params.entries.joinToString("&") { "${it.key.urlEncode()}=${it.value.urlEncode()}" }
        return getJson("$mainUrl/api/$path" + if (query.isEmpty()) "" else "?$query")
    }

    private fun decodeCatalog(bytes: ByteArray): String {
        val unmasked = ByteArray(bytes.size) { i -> (bytes[i].toInt() xor catalogKey[i % catalogKey.size].toInt()).toByte() }
        return GZIPInputStream(ByteArrayInputStream(unmasked)).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun String.urlEncode() = java.net.URLEncoder.encode(this, "UTF-8")

    private fun anilist(query: String, page: Int = 1, sort: String = "POPULARITY_DESC"): JSONArray {
        val gql = """
            query (${ '$' }page:Int, ${ '$' }search:String) {
              Page(page:${ '$' }page, perPage:20) {
                media(type:ANIME, search:${ '$' }search, sort:[$sort], isAdult:false) {
                  id idMal format status episodes averageScore popularity seasonYear
                  title { romaji english native } coverImage { extraLarge large }
                }
              }
            }
        """.trimIndent()
        val variables = JSONObject().put("page", page).put("search", query.ifBlank { JSONObject.NULL })
        val payload = JSONObject().put("query", gql).put("variables", variables)
        val result = JSONObject(request("https://graphql.anilist.co", "POST", payload.toString()))
        return result.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: JSONArray()
    }

    private fun mediaTitle(media: JSONObject): String {
        val title = media.optJSONObject("title")
        return title?.optString("english")?.takeIf { it.isNotBlank() && it != "null" }
            ?: title?.optString("romaji")?.takeIf { it.isNotBlank() && it != "null" }
            ?: "Unknown anime"
    }

    private fun mediaUrl(media: JSONObject) = "miruro://anilist/${media.optInt("id")}"
    private fun poster(media: JSONObject) = media.optJSONObject("coverImage")?.optString("extraLarge")?.takeIf { it.startsWith("http") }
        ?: media.optJSONObject("coverImage")?.optString("large")

    private fun toSearch(media: JSONObject): SearchResponse =
        newAnimeSearchResponse(mediaTitle(media), mediaUrl(media), TvType.Anime) { posterUrl = poster(media) }

    override suspend fun search(query: String): List<SearchResponse> =
        anilist(query, 1, "SEARCH_MATCH").toList().mapNotNull { it as? JSONObject }.map(::toSearch)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val popular = anilist("", page, "POPULARITY_DESC").toList().mapNotNull { it as? JSONObject }.map(::toSearch)
        val trending = anilist("", page, "TRENDING_DESC").toList().mapNotNull { it as? JSONObject }.map(::toSearch)
        return newHomePageResponse(
            listOf(
                HomePageList("Popular anime", popular),
                HomePageList("Trending anime", trending)
            ),
            hasNext = page < 5
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("""miruro://anilist/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val media = anilistById(id) ?: return null
        val title = mediaTitle(media)
        val miruroTitle = api("v1/anime", mapOf("anilist_id_in" to id.toString(), "limit" to "100"))
            .optJSONArray("data")?.optJSONObject(0) ?: return null
        val miruroId = miruroTitle.optString("id").takeIf { it.isNotBlank() } ?: return null
        val episodeRows = fetchEpisodes(miruroId)
        val counts = miruroTitle.optJSONObject("episode_counts")
        val subCount = counts?.optInt("sub", episodeRows.size) ?: episodeRows.size
        val dubCount = counts?.optInt("dub", 0) ?: 0
        fun makeEpisodes(variant: String, maxCount: Int): List<Episode> = episodeRows.mapNotNull { ep ->
            val number = ep.optDouble("episode_number", -1.0)
            if (number <= 0 || number > maxCount) return@mapNotNull null
            val epTitle = ep.optString("title").takeIf { it.isNotBlank() && it != "null" }
            newEpisode("miruro://episode/$miruroId/${numberText(number)}/$variant") {
                name = epTitle ?: "Episode ${numberText(number)}"
                episode = number.toInt()
                posterUrl = ep.optString("thumbnail_url").takeIf { it.startsWith("http") } ?: poster(media)
                description = buildString {
                    ep.optString("canon_type").takeIf { it.isNotBlank() && it != "null" }?.let { append(it.replace('_',' ')) }
                    ep.optString("aired_on").takeIf { it.isNotBlank() && it != "null" }?.let { if (isNotEmpty()) append(" • "); append(it) }
                }.ifBlank { null }
            }
        }
        val subEpisodes = makeEpisodes("sub", subCount)
        val dubEpisodes = makeEpisodes("dub", dubCount)
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
            addEpisodes(DubStatus.Subbed, subEpisodes)
            addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    private fun anilistById(id: Int): JSONObject? {
        val gql = """query { Media(id:$id, type:ANIME) { id title { romaji english } description genres seasonYear status coverImage { extraLarge large } } }"""
        return JSONObject(request("https://graphql.anilist.co", "POST",
            JSONObject().put("query", gql).toString()))
            .optJSONObject("data")?.optJSONObject("Media")
    }

    private fun fetchEpisodes(miruroId: String): List<JSONObject> {
        val all = mutableListOf<JSONObject>()
        var cursor: String? = null
        repeat(30) {
            val params = mutableMapOf("limit" to "10000")
            if (cursor != null) params["cursor"] = cursor!!
            val page = api("v1/anime/$miruroId/episodes", params)
            val data = page.optJSONArray("data") ?: return all
            for (i in 0 until data.length()) {
                val ep = data.optJSONObject(i) ?: continue
                val kind = ep.optString("kind")
                if (kind.isBlank() || kind == "regular") all += ep
            }
            cursor = page.optString("next_cursor").takeIf { page.optBoolean("has_more") && it.isNotBlank() && it != "null" }
            if (cursor == null) return all
        }
        return all
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val match = Regex("""miruro://episode/([^/]+)/([^/]+)/(sub|dub)""").find(data) ?: return false
        val miruroId = match.groupValues[1]
        val episodeNo = match.groupValues[2]
        val requestedVariant = match.groupValues[3]
        val play = api("v1/anime/$miruroId/episodes/$episodeNo/play")
        val tracks = play.optJSONArray("tracks") ?: return false
        var emitted = false
        for (i in 0 until tracks.length()) {
            val track = tracks.optJSONObject(i) ?: continue
            val trackName = track.optString("track")
            if (requestedVariant == "dub" && trackName != "dub") continue
            if (requestedVariant == "sub" && trackName !in setOf("sub", "ssub")) continue
            val providers = track.optJSONArray("providers") ?: continue
            for (p in 0 until providers.length()) {
                val provider = providers.optJSONObject(p) ?: continue
                val providerKey = provider.optString("provider")
                val subtitles = provider.optJSONArray("subtitles") ?: JSONArray()
                for (s in 0 until subtitles.length()) {
                    val sub = subtitles.optJSONObject(s) ?: continue
                    val subUrl = sub.optString("file").ifBlank { sub.optString("url") }
                    if (subUrl.startsWith("http")) {
                        subtitleCallback(SubtitleFile(
                            sub.optString("label").ifBlank { sub.optString("language").ifBlank { "Subtitle" } },
                            subUrl
                        ))
                    }
                }
                val servers = provider.optJSONArray("servers") ?: continue
                for (s in 0 until servers.length()) {
                    val server = servers.optJSONObject(s) ?: continue
                    val headers = server.optJSONObject("headers") ?: JSONObject()
                    val referer = headers.optString("Referer").takeIf { it.startsWith("http") } ?: "$mainUrl/"
                    val extraHeaders = mutableMapOf<String, String>()
                    val keys = headers.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (!key.equals("Referer", true)) extraHeaders[key] = headers.optString(key)
                    }
                    val streams = server.optJSONArray("streams") ?: JSONArray()
                    val embedNode = server.opt("embed")
                    val embedUrl = when (embedNode) {
                        is JSONObject -> embedNode.optString("url")
                        is String -> embedNode
                        else -> ""
                    }.takeIf { it.startsWith("http") }
                    val providerConfig = streamingConfig.optJSONObject(providerKey)
                    val isEmbedProvider = providerConfig?.optString("relationship") == "embed"
                    var serverEmitted = false
                    if (!isEmbedProvider) {
                        for (j in 0 until streams.length()) {
                            val stream = streams.optJSONObject(j) ?: continue
                            val streamUrl = stream.optString("url")
                            if (!streamUrl.startsWith("http")) continue
                            val format = stream.optString("format").lowercase()
                            val qualityText = stream.optString("quality").ifBlank { server.optString("server") }
                            val quality = Regex("""(\d{3,4})""").find(qualityText)?.groupValues?.get(1)?.toIntOrNull()
                                ?: Qualities.Unknown.value
                            val type = if (format == "hls" || streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            callback(newExtractorLink(
                                source = "Miruro • $providerKey • ${trackName.uppercase()}",
                                name = "Miruro • $providerKey • ${trackName.uppercase()}",
                                url = streamUrl,
                                type = type
                            ) {
                                this.referer = referer
                                this.quality = quality
                                this.headers = extraHeaders
                            })
                            serverEmitted = true
                            emitted = true
                        }
                    }
                    if ((isEmbedProvider || !serverEmitted) && embedUrl != null) {
                        try {
                            loadExtractor(embedUrl, referer, subtitleCallback) { link ->
                                callback(link)
                                emitted = true
                            }
                        } catch (_: Exception) {
                            // Keep resolving the remaining providers if this embed is unsupported.
                        }
                    }
                }
            }
        }
        return emitted
    }

    private fun JSONArray.toList(): List<Any?> = (0 until length()).map { opt(it) }
    private fun JSONArray.toStringList(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
    private fun numberText(n: Double) = if (n % 1.0 == 0.0) n.toLong().toString() else n.toString()

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
