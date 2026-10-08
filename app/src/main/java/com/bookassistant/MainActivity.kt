package com.bookassistant

import android.content.Intent
import android.content.Context
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.room.Room
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bookassistant.data.*
import com.bookassistant.services.*
import com.bookassistant.ui.LookupTextToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private val Ink = Color(0xFF202A2A)
private val Teal = Color(0xFF137C72)
private val Paper = Color(0xFFF7F5EF)
private val Context.readerPrefs by preferencesDataStore(name = "reading")
private val fontSizeKey = intPreferencesKey("font_size")
private val showTranslationsKey = booleanPreferencesKey("show_translations")

class MainActivity : ComponentActivity() {
    private lateinit var db: AppDatabase
    private lateinit var speech: Speech
    private lateinit var translator: Translator
    private lateinit var offlineDictionary: OfflineDictionary

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "reader.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
        speech = Speech(this)
        translator = Translator()
        offlineDictionary = OfflineDictionary(this)
        setContent { ReaderApp(this, db, BookImporter(this, db), Dictionary(), translator, speech, offlineDictionary) }
    }

    override fun onDestroy() {
        speech.close()
        translator.close()
        offlineDictionary.close()
        db.close()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderApp(context: Context, db: AppDatabase, importer: BookImporter, dictionary: Dictionary, translator: Translator, speech: Speech, offlineDictionary: OfflineDictionary) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val speechState by speech.state.collectAsState()
    val books by db.books().all().collectAsState(initial = emptyList())
    val words by db.words().all().collectAsState(initial = emptyList())
    var selectedBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by remember { mutableStateOf<String?>(null) }
    var phraseChoices by remember { mutableStateOf(emptyList<String>()) }
    var lookup by remember { mutableStateOf<Word?>(null) }
    var lookupReady by remember { mutableStateOf(false) }
    var fullDetail by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var showContents by remember { mutableStateOf(false) }
    var chapterTarget by remember { mutableStateOf<Int?>(null) }
    var repairedBookIds by remember { mutableStateOf(emptySet<String>()) }
    var repairingBookId by remember { mutableStateOf<String?>(null) }
    val fontSize by context.readerPrefs.data.map { it[fontSizeKey] ?: 18 }.collectAsState(initial = 18)
    val showTranslations by context.readerPrefs.data.map { it[showTranslationsKey] ?: true }.collectAsState(initial = null)
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    val selectedBook = books.firstOrNull { it.id == selectedBookId }
    val passages by (selectedBookId?.let { db.books().passages(it) } ?: flowOf(emptyList<Passage>())).collectAsState(initial = emptyList())

    LaunchedEffect(selectedBook?.id, passages.size) {
        val book = selectedBook ?: return@LaunchedEffect
        val currentPassages = passages.filter { it.bookId == book.id }
        if (book.format != "epub" || currentPassages.isEmpty() || book.id in repairedBookIds || chapterEntries(currentPassages).size > 1) return@LaunchedEffect
        repairingBookId = book.id
        val result = runCatching { importer.refreshChapters(book.id) }
        repairedBookIds = repairedBookIds + book.id
        repairingBookId = null
        if (result.getOrDefault(false).not()) snackbar.showSnackbar("无法更新这本书的目录，请重新导入 EPUB")
    }

    val examples = remember { Examples() }

    fun lookupWord(value: String, forceRefresh: Boolean = false) {
        val clean = normalizeQuery(value)
        if (clean.isBlank()) return
        query = clean
        lookup = null
        lookupReady = false
        scope.launch {
            val key = clean.lowercase(Locale.ROOT)
            val existing = db.words().get(key)
            if (existing != null && !forceRefresh) { lookup = existing; lookupReady = true; return@launch }
            val local = runCatching { offlineDictionary.lookup(clean) }.getOrNull()
            if (local != null) {
                val word = Word(key, clean, local.chinese, local.phonetic, local.definition,
                    local.examples.joinToString("\n"), existing?.source ?: selectedBook?.title.orEmpty(),
                    existing?.addedAt ?: System.currentTimeMillis(),
                    exampleChinese = if (existing?.example == local.examples.joinToString("\n")) existing.exampleChinese else "",
                    exampleAttribution = if (local.examples.isEmpty()) "" else local.attribution)
                if (query == clean) { lookup = word; lookupReady = true }
                if (existing != null) db.words().put(word)
                val translated = local.examples.map { runCatching { translator.translateDownloaded(it) }.getOrNull().orEmpty() }
                if (translated.any(String::isNotBlank)) {
                    val enriched = word.copy(exampleChinese = translated.joinToString("\n"))
                    if (query == clean) lookup = enriched
                    db.words().get(key)?.takeIf { it.example == enriched.example }?.let { saved ->
                        db.words().put(saved.copy(exampleChinese = enriched.exampleChinese))
                    }
                }
                return@launch
            }
            val completed = coroutineScope {
                val dictionaryResult = async(Dispatchers.IO) { runCatching { dictionary.lookup(clean) }.getOrNull() }
                val externalResult = async(Dispatchers.IO) {
                    runCatching { examples.lookup(clean) }.getOrDefault(ExternalExamples(emptyList(), ""))
                }
                val entry = dictionaryResult.await()
                val englishDefinition = entry?.let { listOf(it.partOfSpeech, it.definition).filter(String::isNotBlank).joinToString(" · ") }.orEmpty()
                if (query == clean) lookup = Word(key, clean, "正在查询中文解释…", entry?.phonetic.orEmpty(), englishDefinition, entry?.examples?.take(2)?.joinToString("\n").orEmpty(), existing?.source ?: selectedBook?.title.orEmpty(), existing?.addedAt ?: System.currentTimeMillis(), exampleAttribution = if (entry?.examples.isNullOrEmpty()) "" else "Free Dictionary API")
                val chinese = runCatching { translator.translate(entry?.definition?.takeIf { it.isNotBlank() } ?: clean) }
                    .getOrElse { "翻译暂不可用，请联网后重试" }
                val fallbackExamples = externalResult.await()
                val selectedExamples = if (entry?.examples.isNullOrEmpty()) fallbackExamples else ExternalExamples(entry.examples.take(2), "Free Dictionary API")
                val exampleChinese = selectedExamples.sentences.map { runCatching { translator.translate(it) }.getOrDefault("") }
                Word(key, clean, chinese, entry?.phonetic.orEmpty(), englishDefinition, selectedExamples.sentences.joinToString("\n"), existing?.source ?: selectedBook?.title.orEmpty(), existing?.addedAt ?: System.currentTimeMillis(), exampleChinese.joinToString("\n"), selectedExamples.attribution)
            }
            if (query == clean) {
                lookup = completed
                lookupReady = true
                if (existing != null && !completed.chinese.startsWith("翻译暂不可用")) db.words().put(completed)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = context.contentName(uri)
            scope.launch {
                busy = true
                runCatching { importer.import(uri, name) }
                    .onSuccess { selectedBookId = it; tab = 0 }
                    .onFailure { snackbar.showSnackbar(it.message ?: "导入失败") }
                busy = false
            }
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Teal, background = Paper, surface = Paper, onSurface = Ink)) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(title = { Text(if (tab == 1) "生词本" else selectedBook?.title ?: "我的书架", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { if (selectedBook != null && tab == 0) IconButton(onClick = { chapterTarget = null; selectedBookId = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回书架") } },
                    actions = {
                        if (selectedBook != null && tab == 0) {
                            TextButton(enabled = showTranslations != null, onClick = {
                                val next = showTranslations != true
                                scope.launch { context.readerPrefs.edit { it[showTranslationsKey] = next } }
                            }) { Text(if (showTranslations == true) "关闭翻译" else "打开翻译") }
                            IconButton(onClick = { showContents = true }) { Icon(Icons.Default.List, "目录") }
                            IconButton(onClick = { scope.launch { context.readerPrefs.edit { it[fontSizeKey] = (fontSize - 1).coerceAtLeast(14) } } }) { Text("A−") }
                            IconButton(onClick = { scope.launch { context.readerPrefs.edit { it[fontSizeKey] = (fontSize + 1).coerceAtMost(28) } } }) { Text("A+") }
                        } else if (tab == 0) IconButton(onClick = { picker.launch(arrayOf("application/pdf", "application/epub+zip", "application/octet-stream")) }) { Icon(Icons.Default.Add, "导入书籍") }
                    })
            },
            bottomBar = { if (!wide) NavigationBar(modifier = Modifier.testTag("phone-bottom-nav")) { NavigationBarItem(tab == 0, { tab = 0 }, icon = { Icon(Icons.Default.MenuBook, null) }, label = { Text("阅读") }); NavigationBarItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.Default.Bookmark, null) }, label = { Text("生词本") }) } }
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) NavigationRail(modifier = Modifier.testTag("tablet-rail")) { NavigationRailItem(tab == 0, { tab = 0 }, icon = { Icon(Icons.Default.MenuBook, null) }, label = { Text("阅读") }); NavigationRailItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.Default.Bookmark, null) }, label = { Text("生词本") }) }
                if (wide && tab == 0 && selectedBook != null && query == null) {
                    LibrarySidebar(books, selectedBook.id, onOpen = { chapterTarget = null; selectedBookId = it })
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (tab == 1) VocabularyPage(words, speech, onDelete = { word -> scope.launch { db.words().delete(word.key); val result = snackbar.showSnackbar("已删除 ${word.display}", "撤销"); if (result == SnackbarResult.ActionPerformed) db.words().put(word) } }, onLookup = { lookupWord(it) })
                    else if (selectedBook == null) LibraryPage(books, busy, onImport = { picker.launch(arrayOf("application/pdf", "application/epub+zip", "application/octet-stream")) }, onOpen = { chapterTarget = null; selectedBookId = it.id }, onDelete = { book -> scope.launch { db.books().delete(book.id); File(book.path).delete() } })
                    else key(selectedBook.id) { ReadingPage(passages.filter { it.bookId == selectedBook.id }, selectedBook.position, fontSize, translator, db, selectedBook.id, onLookup = { term, _ -> lookupWord(term) }, onSuggestions = { phraseChoices = it }, onError = { scope.launch { snackbar.showSnackbar(it) } }, chapterTarget = chapterTarget, onChapterTargetHandled = { chapterTarget = null }, showTranslations = showTranslations == true) }
                }
                if (wide && query != null && !fullDetail) Box(Modifier.width(350.dp).fillMaxHeight().background(Color.White)) { LookupPanel(query!!, lookup, lookupReady, speech, words.any { it.key == lookup?.key }, phraseChoices, onPhrase = { lookupWord(it) }, onClose = { query = null }, onSave = { lookup?.let { word -> scope.launch { db.words().put(word); snackbar.showSnackbar("已加入生词本") } } }, onRetry = { lookupWord(query!!, forceRefresh = true) }, onDetails = { fullDetail = true }) }
            }
        }
        if (!wide && query != null && !fullDetail) ModalBottomSheet(onDismissRequest = { query = null }) {
            LookupPanel(query!!, lookup, lookupReady, speech, words.any { it.key == lookup?.key }, phraseChoices, onPhrase = { lookupWord(it) }, onClose = { query = null }, onSave = { lookup?.let { word -> scope.launch { db.words().put(word); snackbar.showSnackbar("已加入生词本") } } }, onRetry = { lookupWord(query!!, forceRefresh = true) }, onDetails = { fullDetail = true })
        }
        if (showContents && selectedBook != null && tab == 0) {
            val entries = chapterEntries(passages.filter { it.bookId == selectedBook.id })
            AlertDialog(onDismissRequest = { showContents = false }, title = { Text("目录") }, text = {
                if (repairingBookId == selectedBook.id) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text("正在读取章节目录…", modifier = Modifier.padding(start = 12.dp))
                    }
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                        items(entries) { entry ->
                            TextButton(onClick = { chapterTarget = entry.position; showContents = false }, modifier = Modifier.fillMaxWidth()) {
                                Text(entry.title, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { showContents = false }) { Text("关闭") } })
        }
        if (fullDetail && query != null) Dialog(onDismissRequest = { fullDetail = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth().fillMaxHeight(0.94f), shape = RoundedCornerShape(12.dp), color = Color.White) {
                LookupPanel(query!!, lookup, lookupReady, speech, words.any { it.key == lookup?.key }, phraseChoices, onPhrase = { lookupWord(it) }, onClose = { fullDetail = false }, onSave = { lookup?.let { word -> scope.launch { db.words().put(word); snackbar.showSnackbar("已加入生词本") } } }, onRetry = { lookupWord(query!!, forceRefresh = true) }, onDetails = null)
            }
        }
        speechState.error?.let { error ->
            AlertDialog(onDismissRequest = { speech.dismissError() }, title = { Text("发音未能播放") },
                text = { Text(error) },
                confirmButton = { TextButton(onClick = { speech.dismissError() }) { Text("知道了") } },
                dismissButton = { TextButton(onClick = {
                    speech.dismissError()
                    runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                        .onFailure { runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } }
                }) { Text("语音设置") } })
        }
    }
}

internal fun normalizeQuery(value: String): String = value.trim()
    .replace(Regex("\\s+"), " ")
    .trim(',', '.', ';', ':', '?', '!', '“', '”', '"')
    .take(120)
    .trim()

@Composable
private fun LibrarySidebar(books: List<Book>, selectedId: String, onOpen: (String) -> Unit) {
    Column(Modifier.width(220.dp).fillMaxHeight().background(Color(0xFFEEEEE7)).padding(12.dp)) {
        Text("书架", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(books, key = { it.id }) { book ->
                Surface(onClick = { onOpen(book.id) }, shape = RoundedCornerShape(8.dp), color = if (book.id == selectedId) Color.White else Color.Transparent) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = if (book.id == selectedId) FontWeight.SemiBold else FontWeight.Normal)
                        Text("${readingProgress(book)}%", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun Context.contentName(uri: android.net.Uri): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getString(0) ?: "book"
    }
    return uri.lastPathSegment ?: "book"
}

@Composable
private fun LibraryPage(books: List<Book>, busy: Boolean, onImport: () -> Unit, onOpen: (Book) -> Unit, onDelete: (Book) -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("把英语书变成读得懂的每一段", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onImport, enabled = !busy) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text(if (busy) "正在导入…" else "导入 PDF / EPUB") }
        if (books.isEmpty()) Text("还没有书籍。选择一本英文书开始阅读。", Modifier.padding(top = 36.dp), color = Color.Gray)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 18.dp)) {
            items(books, key = { it.id }) { book ->
                ElevatedCard(onClick = { onOpen(book) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MenuBook, null, tint = Teal, modifier = Modifier.size(38.dp))
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(book.title, fontWeight = FontWeight.SemiBold)
                            Text("${book.format.uppercase(Locale.ROOT)} · 阅读 ${readingProgress(book)}%", color = Color.Gray, fontSize = 12.sp)
                        }
                        IconButton(onClick = { onDelete(book) }) { Icon(Icons.Default.DeleteOutline, "删除书籍") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingPage(passages: List<Passage>, position: Int, fontSize: Int, translator: Translator, db: AppDatabase, bookId: String, onLookup: (String, String) -> Unit, onSuggestions: (List<String>) -> Unit, onError: (String) -> Unit, chapterTarget: Int?, onChapterTargetHandled: () -> Unit, showTranslations: Boolean) {
    val scope = rememberCoroutineScope()
    var phrase by remember { mutableStateOf("") }
    val translationErrors = remember(bookId) { mutableStateMapOf<String, String>() }
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val tracker = remember(bookId) { ReadingPositionTracker() }
    var restored by remember(bookId) { mutableStateOf(false) }
    LaunchedEffect(bookId, passages.size) {
        if (passages.isNotEmpty() && !restored) {
            val saved = (db.books().get(bookId)?.position ?: position).coerceIn(0, passages.lastIndex)
            withContext(Dispatchers.Main.immediate) { state.scrollToItem(saved + 1) }
            tracker.restored(saved)
            restored = true
        }
    }
    LaunchedEffect(bookId, restored) {
        if (restored) {
            var userScrollStarted = false
            snapshotFlow { (state.firstVisibleItemIndex - 1).coerceAtLeast(0) to state.isScrollInProgress }
                .collect { (visiblePosition, scrolling) ->
                    if (scrolling) userScrollStarted = true
                    if (userScrollStarted && tracker.shouldSave(visiblePosition)) db.books().position(bookId, visiblePosition)
                    if (!scrolling) userScrollStarted = false
                }
        }
    }
    LaunchedEffect(bookId, chapterTarget, restored) {
        if (restored && chapterTarget != null && passages.isNotEmpty()) {
            val target = chapterTarget.coerceIn(0, passages.lastIndex)
            withContext(Dispatchers.Main.immediate) { state.scrollToItem(target + 1) }
            tracker.restored(target)
            db.books().position(bookId, target)
            onChapterTargetHandled()
        }
    }
    LazyColumn(state = state, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(phrase, { phrase = it }, label = { Text("查询单词或短语") }, modifier = Modifier.weight(1f), singleLine = true)
                IconButton(onClick = { onLookup(phrase, "") }, enabled = phrase.isNotBlank()) { Icon(Icons.Default.Search, "查询短语") }
            }
        }
        items(passages, key = { it.id }) { passage ->
            val view = LocalView.current
            val clipboard = LocalClipboardManager.current
            val selectionToolbar = remember(view, clipboard, passage.id) {
                LookupTextToolbar(view, clipboard) { selected -> onSuggestions(emptyList()); onLookup(selected, passage.english) }
            }
            LaunchedEffect(passage.id, passage.chinese, showTranslations) {
                if (showTranslations && passage.chinese == null) runCatching { translator.translate(passage.english) }
                    .onSuccess { db.books().translate(passage.id, it) }
                    .onFailure { if (it is CancellationException) throw it; translationErrors[passage.id] = "翻译失败，请联网后重试" }
            }
            Column(Modifier.fillMaxWidth().widthIn(max = 760.dp)) {
                if (passage.chapter.isNotBlank() && (passage.position == 0 || passages.getOrNull(passage.position - 1)?.chapter != passage.chapter)) Text(passage.chapter, fontWeight = FontWeight.Bold, color = Teal, modifier = Modifier.padding(bottom = 10.dp))
                CompositionLocalProvider(LocalTextToolbar provides selectionToolbar) {
                    SelectionContainer {
                        ClickableText(text = buildAnnotatedString { append(passage.english) }, style = androidx.compose.ui.text.TextStyle(color = Ink, fontSize = fontSize.sp, lineHeight = (fontSize * 1.55).sp), onClick = { offset ->
                            val token = wordAt(passage.english, offset)
                            if (token.isNotBlank()) { onSuggestions(phrasesAt(passage.english, offset)); onLookup(token, sentenceAt(passage.english, offset)) }
                        })
                    }
                }
                if (showTranslations) {
                    Spacer(Modifier.height(6.dp))
                    Text(passage.chinese ?: translationErrors[passage.id] ?: "翻译中…", color = if (passage.chinese == null) Color.Gray else Color(0xFF64706B), fontSize = (fontSize - 2).sp, lineHeight = (fontSize * 1.4).sp)
                    if (passage.chinese == null && translationErrors.containsKey(passage.id)) TextButton(onClick = { scope.launch {
                        translationErrors.remove(passage.id)
                        runCatching { translator.translate(passage.english) }
                            .onSuccess { db.books().translate(passage.id, it) }
                            .onFailure { if (it is CancellationException) throw it; translationErrors[passage.id] = "翻译失败，请联网后重试"; onError("翻译失败：${it.message}") }
                    } }) { Text("重试翻译") }
                }
            }
        }
    }
}

internal fun wordAt(text: String, offset: Int): String {
    if (offset !in text.indices) return ""
    fun part(c: Char) = c.isLetter() || c == '\'' || c == '’' || c == '-'
    var start = offset
    var end = offset
    while (start > 0 && part(text[start - 1])) start--
    while (end < text.length && part(text[end])) end++
    return text.substring(start, end).trim('\'', '’', '-')
}

internal fun readingProgress(book: Book): Int = if (book.totalPassages <= 0) 0 else ((book.position + 1) * 100 / book.totalPassages).coerceIn(0, 100)

internal fun phrasesAt(text: String, offset: Int): List<String> {
    val matches = Regex("[A-Za-z]+(?:['’][A-Za-z]+)*").findAll(text).toList()
    val index = matches.indexOfFirst { offset in it.range }
    if (index < 0) return emptyList()
    return listOfNotNull(
        matches.subList(index, (index + 2).coerceAtMost(matches.size)).takeIf { it.size == 2 }?.joinToString(" ") { it.value },
        matches.subList((index - 1).coerceAtLeast(0), (index + 2).coerceAtMost(matches.size)).takeIf { it.size == 3 }?.joinToString(" ") { it.value }
    ).distinct()
}

internal fun sentenceAt(text: String, offset: Int): String {
    if (offset !in text.indices) return ""
    val left = text.lastIndexOfAny(charArrayOf('.', '!', '?'), startIndex = (offset - 1).coerceAtLeast(0))
    val right = text.indexOfAny(charArrayOf('.', '!', '?'), startIndex = offset).let { if (it < 0) text.length else it + 1 }
    return text.substring(left + 1, right).trim()
}

@Composable
private fun LookupPanel(query: String, word: Word?, ready: Boolean, speech: Speech, saved: Boolean, phrases: List<String>, onPhrase: (String) -> Unit, onClose: () -> Unit, onSave: () -> Unit, onRetry: () -> Unit, onDetails: (() -> Unit)?) {
    val speechState by speech.state.collectAsState()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(query, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f)); IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭词条") } }
        if (phrases.isNotEmpty()) {
            Text("相关短语", color = Color.Gray, fontSize = 12.sp)
            phrases.forEach { phrase -> SuggestionChip(onClick = { onPhrase(phrase) }, label = { Text(phrase, maxLines = 1) }) }
        }
        if (word == null) { CircularProgressIndicator(Modifier.padding(16.dp)); Text("正在查询释义…") }
        else {
            if (word.phonetic.isNotBlank()) Text(word.phonetic, color = Color.Gray)
            Row { TextButton(onClick = { speech.speak(query, false) }) { Icon(Icons.Default.VolumeUp, null); Text("美式发音") }; TextButton(onClick = { speech.speak(query, true) }) { Icon(Icons.Default.VolumeUp, null); Text("英式发音") } }
            if (speechState.message.isNotBlank()) Text(speechState.message, color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Text(word.chinese, style = MaterialTheme.typography.titleMedium)
            if (word.definition.isNotBlank()) Text(word.definition, color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
            if (word.example.isNotBlank()) {
                Text(if (word.exampleChinese.isBlank()) "英语例句" else "双语例句", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
                val chineseExamples = word.exampleChinese.lines()
                word.example.lines().filter(String::isNotBlank).forEachIndexed { index, example ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. $example", modifier = Modifier.weight(1f))
                        IconButton(onClick = { speech.speak(example, false) }) { Icon(Icons.Default.VolumeUp, "朗读例句") }
                    }
                    chineseExamples.getOrNull(index)?.takeIf(String::isNotBlank)?.let { Text(it, color = Color.Gray) }
                    Spacer(Modifier.height(8.dp))
                }
                if (word.exampleAttribution.isNotBlank()) Text("例句来源：${word.exampleAttribution}", color = Color.Gray, fontSize = 12.sp)
                else if (ready) Text("旧版缓存例句；可点重新查询更新外部例句", color = Color.Gray, fontSize = 12.sp)
            }
            else if (ready) Text("暂无外部例句", color = Color.Gray, modifier = Modifier.padding(top = 12.dp))
            if (word.source.isNotBlank()) Text("来源：${word.source}", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(20.dp))
            Button(onClick = onSave, enabled = ready && !saved && !word.chinese.startsWith("翻译暂不可用"), modifier = Modifier.fillMaxWidth()) { Text(if (saved) "已加入生词本" else if (ready) "加入生词本" else "查询中…") }
            if (onDetails != null) TextButton(onClick = onDetails) { Text("查看完整词条") }
            TextButton(onClick = onRetry) { Text("重新查询") }
        }
    }
}

@Composable
private fun VocabularyPage(words: List<Word>, speech: Speech, onDelete: (Word) -> Unit, onLookup: (String) -> Unit) {
    var filter by remember { mutableStateOf("") }
    val speechState by speech.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("我的生词", style = MaterialTheme.typography.headlineMedium)
        if (speechState.message.isNotBlank()) Text(speechState.message, color = Color.Gray, fontSize = 12.sp)
        OutlinedTextField(filter, { filter = it }, label = { Text("搜索单词或短语") }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), singleLine = true)
        val shown = words.filter { it.display.contains(filter, true) || it.chinese.contains(filter, true) }
        if (shown.isEmpty()) Text(if (words.isEmpty()) "阅读时点击单词，添加后会显示在这里。" else "没有匹配的词条。", color = Color.Gray, modifier = Modifier.padding(top = 24.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.key }) { word -> ElevatedCard(onClick = { onLookup(word.display) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(word.display, fontWeight = FontWeight.SemiBold, fontSize = 20.sp); Text(word.chinese, maxLines = 2, overflow = TextOverflow.Ellipsis); if (word.example.isNotBlank()) Text(word.example, fontSize = 13.sp, color = Color.Gray, maxLines = 2); if (word.exampleChinese.isNotBlank()) Text(word.exampleChinese, fontSize = 12.sp, color = Color.Gray, maxLines = 1) }
                    TextButton(onClick = { speech.speak(word.display, false) }) { Text("美", fontSize = 12.sp) }
                    TextButton(onClick = { speech.speak(word.display, true) }) { Text("英", fontSize = 12.sp) }
                    IconButton(onClick = { onDelete(word) }) { Icon(Icons.Default.DeleteOutline, "删除生词") }
                }
            } }
        }
    }
}
