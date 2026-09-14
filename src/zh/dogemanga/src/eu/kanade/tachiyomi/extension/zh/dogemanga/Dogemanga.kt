package eu.kanade.tachiyomi.extension.zh.dogemanga

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class Dogemanga : KeiSource() {

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36")
        .set("Referer", "$baseUrl/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .set("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")

    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(parseMangaCards(client.get(baseUrl).asJsoup()), false)

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (page == 1) {
            "$baseUrl/?s=1"
        } else {
            "$baseUrl/_search?o=${page.toOffset()}&s=1"
        }
        return parseMangaPage(client.get(url).asJsoup())
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (page > 1) {
                addPathSegment("_search")
                addQueryParameter("o", page.toOffset().toString())
            }
            addQueryParameter("q", query)
        }.build()

        return parseMangaPage(client.get(url).asJsoup())
    }

    private fun parseMangaPage(document: Document): MangasPage {
        val mangas = parseMangaCards(document)
        return MangasPage(mangas, document.selectFirst("a.btn[href*=_search], a.btn[href*=o=], a.btn[href*=p=]") != null)
    }

    private fun parseMangaCards(document: Document): List<SManga> = document.select(".site-card").mapNotNull { it.toSManga() }

    private fun Element.toSManga(): SManga? {
        val link = selectFirst("a.site-card__manga-title, a[href*=/m/]")
            ?: return null

        return SManga.create().apply {
            title = link.text().ifBlank { link.selectFirst("img")?.attr("alt").orEmpty() }
            url = link.attr("abs:href").toRelativeUrl()
            thumbnail_url = selectFirst("img.card-img-top, img.site-manga__cover-image")?.absUrl("src")
        }.takeIf { it.title.isNotBlank() && it.url.isNotBlank() }
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val mangaIndex = url.pathSegments.indexOf("m")
        if (mangaIndex == -1 || mangaIndex + 1 >= url.pathSize) return null

        val mangaUrl = "/m/${url.pathSegments[mangaIndex + 1]}"
        return client.get(baseUrl + mangaUrl).asJsoup().parseDetails().apply {
            this.url = mangaUrl
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val details = if (fetchDetails) {
            document.parseDetails().apply { url = manga.url }
        } else {
            manga
        }
        val chapterList = if (fetchChapters) document.parseChapters() else chapters

        return SMangaUpdate(details, chapterList)
    }

    private fun Document.parseDetails(): SManga = SManga.create().apply {
        title = selectFirst(".site-card__manga-title")?.text()
            ?: error("Missing manga title")
        thumbnail_url = selectFirst(".site-manga__cover-image")?.absUrl("src")
            ?: selectFirst("meta[property=og:image]")?.attr("content")
        author = selectFirst(".site-card h4 a, .site-card .card-subtitle a")?.text()
        description = selectFirst(".site-card__brief")?.text()
        status = when {
            selectFirst(".site-card small")?.wholeText()?.contains("連載中") == true -> SManga.ONGOING
            selectFirst(".site-card small")?.wholeText()?.contains("連載完結") == true -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    private fun Document.parseChapters(): List<SChapter> = select(".site-manga-thumbnail__link[href*=/p/]").map { element ->
        SChapter.create().apply {
            name = element.selectFirst(".text-center")?.text()
                ?: element.selectFirst("img")?.attr("alt")
                ?: error("Missing chapter name")
            url = element.attr("abs:href").toRelativeUrl()
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        val images = document.select("[data-page-image-url]").map { it.attr("abs:data-page-image-url") }
            .ifEmpty {
                document.select(".site-reader__one-per-page-image").map { it.absUrl("src") }
            }

        return images.distinct().mapIndexed { index, imageUrl ->
            Page(index, imageUrl = imageUrl)
        }
    }

    private fun String.toRelativeUrl(): String {
        val url = toHttpUrl()
        return buildString {
            append(url.encodedPath)
            url.encodedQuery?.let { append('?').append(it) }
        }
    }

    private fun Int.toOffset(): Int = (this - 1) * 24
}
