package com.myseed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Base64
import org.jsoup.nodes.Element

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
        val doc = app.get(if (page == 1) request.data else "${request.data}page/$page/", headers = headers).document
        val results = doc.select("a.movie__block").mapNotNull { it.toSearchResult() }
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
        val isSeries = title.contains("مسلسل") || href.contains("series")
        return newMovieSearchResponse(title, href, if (isSeries) TvType.TvSeries else TvType.Movie) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/find/?word=${query.trim().replace(" ", "+")}" 
        return app.get(url, headers = headers).document.select("a.movie__block").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1.post__name")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "MySeed"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst(".post__story")?.text()?.trim()
        val isEpisode = title.contains("الحلقة") || title.contains("حلقة")
        return if (isEpisode) {
            val season = Regex("الموسم\\s*(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val episode = Regex("الحلقة\\s*(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(newEpisode(url) {
                name = title; this.season = season; this.episode = episode; posterUrl = poster
            })) { this.posterUrl = poster; this.plot = plot }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) { this.posterUrl = poster; this.plot = plot }
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

    private suspend fun loadServerLink(
        link: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
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
            callback(
                newExtractorLink(
                    source = name,
                    name = "MySeed Direct",
                    url = directVideo
                ) {
                    this.referer = link
                    this.quality = Qualities.P1080.value
                }
            )
            return
        }

        val embeddedLinks = page?.select("iframe[src], video source[src]")
            ?.map { it.absUrl("src").ifBlank { it.attr("src") } }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()

        if (embeddedLinks.isEmpty() || depth >= 2) {
            loadExtractor(link, referer, subtitleCallback, callback)
        } else {
            embeddedLinks.forEach { embedded ->
                loadServerLink(embedded, link, subtitleCallback, callback, depth + 1)
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
        val doc = app.get(watchUrl, headers = headers).document

        val playerLink = doc.selectFirst("#asPlayerFrame iframe[src], .player__iframe iframe[src]")
            ?.attr("src")
            ?.let { fixUrl(it) }

        if (!playerLink.isNullOrBlank()) {
            loadServerLink(playerLink, watchUrl, subtitleCallback, callback)
            return true
        }

        doc.select(".servers__list li[data-link]").forEach { server ->
            val link = server.attr("data-link").let { if (it.startsWith("/")) "$mainUrl$it" else it }
            if (link.isNotBlank()) loadServerLink(link, watchUrl, subtitleCallback, callback)
        }
        return true
    }
}
