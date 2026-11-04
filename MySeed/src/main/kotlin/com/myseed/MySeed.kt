package com.myseed

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject

class MySeed : MainAPI() {
    override var mainUrl = "https://m.myseed.tv"
    override var name = "MySeed"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/main/" to "الرئيسية",
        "$mainUrl/movies/" to "أحدث الأفلام",
        "$mainUrl/series/" to "أحدث المسلسلات",
        "$mainUrl/recently/" to "المضاف حديثاً",
        "$mainUrl/trend/" to "الأكثر مشاهدة",
        "$mainUrl/category/foreign-movies/" to "أفلام أجنبية",
        "$mainUrl/category/arabic-movies/" to "أفلام عربية",
        "$mainUrl/category/foreign-series/" to "مسلسلات أجنبية",
        "$mainUrl/category/arabic-series/" to "مسلسلات عربية"
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36",
        "Accept-Language" to "ar,en;q=0.8",
        "Referer" to "$mainUrl/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val results = app.get(url, headers = headers).document
            .select("a.movie__block")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = absUrl("href").ifBlank { attr("href") }
        if (href.isBlank()) return null
        val title = attr("title").takeIf { it.isNotBlank() }
            ?: selectFirst("h3")?.text()?.trim()
            ?: ""
        if (title.isBlank()) return null
        val poster = selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        val isSeries = title.contains("مسلسل") || href.contains("/series/") || href.contains("-s1-") || href.contains("-s2-")
        return newMovieSearchResponse(title, href, if (isSeries) TvType.TvSeries else TvType.Movie) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/find/?word=${query.trim().replace(" ", "+")}"
        return app.get(url, headers = headers).document
            .select("a.movie__block")
            .mapNotNull { it.toSearchResult() }
    }

    private fun episodeNumber(text: String, url: String): Int? {
        return Regex("الحلقة\\s*(\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("[-_]eps?(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun seasonNumber(text: String, url: String): Int {
        return Regex("الموسم\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("[-_]s(\\d+)[-_/]", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: 1
    }

    private suspend fun loadSeries(seriesUrl: String, fallbackDoc: org.jsoup.nodes.Document? = null): LoadResponse {
        val seriesDoc = app.get(seriesUrl, headers = headers).document
        val title = seriesDoc.selectFirst("h1.post__name")?.text()?.trim()
            ?: fallbackDoc?.selectFirst(".bread__crumbs a[href*='/selary/']")?.text()?.trim()
            ?: "MySeed Series"
        val poster = seriesDoc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = seriesDoc.selectFirst(".post__story")?.text()?.trim()

        val seasonUrls = seriesDoc.select("#seasons__list li[data-url]")
            .mapNotNull { it.attr("data-url").takeIf(String::isNotBlank) }
            .plus(seriesUrl)
            .map { fixUrl(it) }
            .distinct()

        val episodes = mutableListOf<Episode>()
        for (seasonUrl in seasonUrls) {
            val seasonDoc = if (seasonUrl.trimEnd('/') == seriesUrl.trimEnd('/')) seriesDoc
            else app.get(seasonUrl, headers = headers).document
            val season = seasonNumber(seasonDoc.selectFirst("h1.post__name")?.text().orEmpty(), seasonUrl)
            seasonDoc.select(".episodes__list a[href]").forEach { ep ->
                val epUrl = ep.absUrl("href").ifBlank { ep.attr("href") }
                if (epUrl.isBlank()) return@forEach
                val epNo = episodeNumber(ep.text(), epUrl) ?: return@forEach
                val epName = ep.selectFirst(".epi__num")?.text()?.trim()?.ifBlank { "الحلقة $epNo" }
                    ?: "الحلقة $epNo"
                episodes += newEpisode(epUrl) {
                    name = epName
                    this.season = season
                    this.episode = epNo
                    posterUrl = poster
                }
            }
        }

        val uniqueEpisodes = episodes.distinctBy { "${it.season}-${it.episode}-${it.data}" }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, uniqueEpisodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val seriesUrl = doc.selectFirst(".bread__crumbs a[href*='/selary/']")
            ?.attr("href")
            ?.takeIf(String::isNotBlank)
            ?.let { fixUrl(it) }

        if (seriesUrl != null) return loadSeries(seriesUrl, doc)
        if (url.contains("/selary/")) return loadSeries(url)

        val title = doc.selectFirst("h1.post__name")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "MySeed"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst(".post__story")?.text()?.trim()
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    private fun decodeMySeedWrapper(url: String): String? {
        if (!url.contains("m.myseed.pics/vid/?id=")) return null
        return runCatching {
            val encoded = url.substringAfter("id=").substringBefore("&")
            val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
            String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP))
        }.getOrNull()
    }

    private fun qualityValue(value: String): Int = when (value) {
        "2160" -> Qualities.P2160.value
        "1440" -> Qualities.P1440.value
        "1080" -> Qualities.P1080.value
        "720" -> Qualities.P720.value
        "480" -> Qualities.P480.value
        "360" -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private suspend fun loadServerLink(
        link: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        quality: Int = Qualities.Unknown.value,
        serverName: String = "MySeed",
        depth: Int = 0
    ) {
        val decoded = decodeMySeedWrapper(link)
        if (!decoded.isNullOrBlank()) {
            loadExtractor(decoded, referer, subtitleCallback, callback)
            return
        }

        val page = runCatching { app.get(link, headers = headers).document }.getOrNull()
        val directVideo = page?.selectFirst("video[src], video source[src]")
            ?.let { it.absUrl("src").ifBlank { it.attr("src") } }
        if (!directVideo.isNullOrBlank()) {
            callback(newExtractorLink(source = name, name = "$serverName ${if (quality > 0) "${quality}p" else "Direct"}", url = directVideo) {
                this.referer = link
                this.quality = quality
            })
            return
        }

        val embeddedLinks = page?.select("iframe[src]")
            ?.map { it.absUrl("src").ifBlank { it.attr("src") } }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()
        if (embeddedLinks.isEmpty() || depth >= 3) {
            loadExtractor(link, referer, subtitleCallback, callback)
        } else {
            embeddedLinks.forEach { embedded ->
                loadServerLink(embedded, link, subtitleCallback, callback, quality, serverName, depth + 1)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = if (data.contains("/watch/")) data else "${data.trimEnd('/')}/watch/"
        val watchResponse = app.get(watchUrl, headers = headers)
        val doc = watchResponse.document
        val body = watchResponse.text
        val postId = Regex("psot_id['\\"]\\s*:\\s*['\\"](\\d+)").find(body)?.groupValues?.get(1)
        val csrf = Regex("csrf__token['\\"]\\s*:\\s*['\\"]([^'\\"]+)").find(body)?.groupValues?.get(1)
        val currentQuality = doc.selectFirst(".servers__list li[data-link]")?.attr("data-qu") ?: "480"
        val qualities = doc.select(".qualities__list li[data-quality]")
            .mapNotNull { it.attr("data-quality").takeIf(String::isNotBlank) }
            .distinct()
            .ifEmpty { listOf(currentQuality) }

        val requestHeaders = headers + mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
        )
        val seen = hashSetOf<String>()

        for (qualityText in qualities) {
            val serverElements = if (qualityText == currentQuality) {
                doc.select(".servers__list li[data-link]")
            } else {
                if (postId == null || csrf == null) emptyList()
                else runCatching {
                    val response = app.post(
                        "$mainUrl/get__quality__servers/",
                        data = mapOf("post_id" to postId, "quality" to qualityText, "csrf_token" to csrf),
                        headers = requestHeaders,
                        referer = watchUrl
                    )
                    val html = JSONObject(response.text).optString("html")
                    if (html.isBlank()) emptyList() else Jsoup.parse(html).select("li[data-link]")
                }.getOrElse { emptyList() }
            }

            for (server in serverElements) {
                val rawLink = server.attr("data-link")
                val link = if (rawLink.startsWith("/")) "$mainUrl$rawLink" else rawLink
                if (link.isBlank()) continue
                val key = "$qualityText|$link"
                if (!seen.add(key)) continue
                val serverName = server.attr("data-server-name").ifBlank { server.text().trim() }.ifBlank { "MySeed" }
                loadServerLink(link, watchUrl, subtitleCallback, callback, qualityValue(qualityText), serverName)
            }
        }

        return true
    }
}
