package com.bookassistant.importing

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import java.io.File
import java.util.zip.ZipFile

data class ParsedBook(val title: String, val passages: List<Pair<String, String>>)

object BookParser {
    fun parse(context: Context, file: File, format: String, fallbackTitle: String): ParsedBook = when (format) {
        "pdf" -> parsePdf(context, file, fallbackTitle)
        "epub" -> parseEpub(file, fallbackTitle)
        else -> error("只支持 PDF 和 EPUB")
    }

    private fun parsePdf(context: Context, file: File, title: String): ParsedBook {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(file).use { document ->
            val blocks = (1..document.numberOfPages).flatMap { page ->
                val stripper = PDFTextStripper().apply { startPage = page; endPage = page }
                stripper.getText(document).split(Regex("\\n\\s*\\n"))
                    .flatMap { splitLong(it.replace(Regex("\\s*\\n\\s*"), " ").trim()) }
                    .filter { it.isNotBlank() }
                    .map { "第 ${page} 页" to it }
            }
            require(blocks.isNotEmpty()) { "PDF 不含可提取的文字，扫描版暂不支持" }
            return ParsedBook(title, blocks)
        }
    }

    internal fun parseEpub(file: File, fallback: String): ParsedBook {
        ZipFile(file).use { zip ->
            val container = zip.getEntry("META-INF/container.xml") ?: error("EPUB 缺少 container.xml")
            val containerDoc = Jsoup.parse(zip.getInputStream(container), "UTF-8", "", org.jsoup.parser.Parser.xmlParser())
            val opfPath = containerDoc.selectFirst("rootfile")?.attr("full-path")?.takeIf { it.isNotBlank() } ?: error("EPUB 缺少 OPF")
            val opfEntry = zip.getEntry(opfPath) ?: error("EPUB 的 OPF 文件不存在")
            val opf = Jsoup.parse(zip.getInputStream(opfEntry), "UTF-8", "", org.jsoup.parser.Parser.xmlParser())
            val title = opf.selectFirst("title")?.text()?.takeIf { it.isNotBlank() } ?: fallback
            val items = opf.select("manifest > item").associate { it.attr("id") to it.attr("href") }
            val base = opfPath.substringBeforeLast('/', "")
            val navigationItem = opf.select("manifest > item").firstOrNull { item ->
                item.attr("id") == opf.selectFirst("spine")?.attr("toc") && item.attr("id").isNotBlank()
            } ?: opf.select("manifest > item").firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }
                ?: opf.select("manifest > item").firstOrNull { "nav" in it.attr("properties").split(' ') }
            val navigationTitles = mutableMapOf<String, String>()
            if (navigationItem != null) {
                val navigationPath = resolveEntry(base, navigationItem.attr("href"))
                zip.getEntry(navigationPath)?.let { navigationEntry ->
                    val navigation = Jsoup.parse(zip.getInputStream(navigationEntry), "UTF-8", "", org.jsoup.parser.Parser.xmlParser())
                    val navigationBase = navigationPath.substringBeforeLast('/', "")
                    if (navigationPath.endsWith(".ncx", ignoreCase = true)) {
                        navigation.select("navPoint").forEach { point ->
                            val label = point.selectFirst("navLabel > text")?.text().orEmpty().trim()
                            val src = point.selectFirst("content")?.attr("src").orEmpty()
                            if (label.isNotBlank() && src.isNotBlank()) navigationTitles.putIfAbsent(resolveEntry(navigationBase, src), label)
                        }
                    } else {
                        navigation.select("nav a[href]").forEach { link ->
                            val label = link.text().trim()
                            val href = link.attr("href")
                            if (label.isNotBlank()) navigationTitles.putIfAbsent(resolveEntry(navigationBase, href), label)
                        }
                    }
                }
            }
            val passages = mutableListOf<Pair<String, String>>()
            for (ref in opf.select("spine > itemref")) {
                val href = items[ref.attr("idref")] ?: error("EPUB spine 引用了缺失的章节")
                val path = resolveEntry(base, href)
                val entry = zip.getEntry(path) ?: error("EPUB 章节不存在：$path")
                val doc = Jsoup.parse(zip.getInputStream(entry), "UTF-8", "")
                val chapter = navigationTitles[path] ?: doc.selectFirst("h1, h2")?.text().orEmpty().ifBlank { doc.title() }
                doc.select("p, blockquote, li").forEach { element ->
                    if (element.tagName() != "p" && element.selectFirst("p, blockquote") != null) return@forEach
                    val text = element.text().trim()
                    splitLong(text).filter { it.isNotBlank() }.forEach { passages += chapter to it }
                }
            }
            require(passages.isNotEmpty()) { "EPUB 不含可阅读的文字" }
            return ParsedBook(title, passages)
        }
    }

    internal fun splitLong(text: String): List<String> {
        if (text.length <= 500) return listOf(text)
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        val result = mutableListOf<String>()
        var buffer = ""
        for (sentence in sentences) {
            if (buffer.isNotEmpty() && buffer.length + sentence.length > 500) { result += buffer; buffer = "" }
            if (sentence.length > 500) {
                if (buffer.isNotEmpty()) { result += buffer; buffer = "" }
                result += sentence.chunked(500)
            } else buffer = if (buffer.isEmpty()) sentence else "$buffer $sentence"
        }
        if (buffer.isNotEmpty()) result += buffer
        return result
    }

    internal fun resolveEntry(base: String, href: String): String {
        val decoded = java.net.URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8")
        val segments = mutableListOf<String>()
        for (segment in (if (base.isBlank()) decoded else "$base/$decoded").split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> { require(segments.isNotEmpty()) { "EPUB 章节路径越界" }; segments.removeAt(segments.lastIndex) }
                else -> segments += segment
            }
        }
        return segments.joinToString("/")
    }
}
