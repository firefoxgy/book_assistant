# English Book Reader Implementation Plan

> **For agentic workers:** Use `superpowers:executing-plans` to implement this plan task by task. Steps use checkboxes for tracking. The user requested no Git commits, so all Git commit steps are omitted.

**Goal:** Build a phone and tablet Android app for bilingual PDF/EPUB reading, word lookup, pronunciation, and a persistent vocabulary notebook.

**Architecture:** A single Kotlin/Compose app separates file parsing, translation, dictionary lookup, speech, and Room persistence behind repository interfaces. ViewModels expose screen state; responsive Compose layouts switch at window size breakpoints. Text and translations are cached per passage.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Room, DataStore, ML Kit Translate, Android TextToSpeech, Android-compatible PDF text extraction, EPUB ZIP/XHTML parser, HTTP client.

**Spec:** `docs/superpowers/specs/2026-09-28-english-book-reader-design.md`

## Global Constraints

- Minimum Android 8.0 / API 26.
- PDF and EPUB with extractable text; scanned PDF OCR is out of scope.
- Chinese translation follows each English passage.
- No API key requirement; ML Kit model download and Free Dictionary API queries need network initially.
- Source books and user data remain private on device; only actively queried words/phrases go to the dictionary service.
- No Git commits, per user instruction.

## Review Focus

- Image-only PDF: reject with a clear extractable-text error; Task 2 test.
- Broken EPUB spine or missing XHTML: fail import without a visible partial book; Task 2 test.
- Reimporting a same-named, different book: both remain distinct; Task 2 test.
- Offline lookup after saved entry: stored meaning/example remains visible; Task 4 test.
- Device without one TTS voice: disable that voice with a clear explanation while preserving the other; Task 4 test.

---

### Task 1: Android foundation and persistence

**Files:** `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/bookassistant/App.kt`, `data/AppDatabase.kt`, `data/BookDao.kt`, `data/VocabularyDao.kt`, `data/Entities.kt`, `ui/AppNavigation.kt`; tests under `app/src/test/java/com/bookassistant/data/`.

**Interfaces:** `BookEntity(id: String, title: String, format: String, localPath: String, importedAt: Long, currentPassageId: String?)`; `PassageEntity(id: String, bookId: String, chapter: String?, order: Int, english: String)`; `TranslationEntity(passageId: String, language: String, text: String)`; `VocabularyEntity(key: String, displayText: String, chinese: String?, dictionaryJson: String?, example: String?, addedAt: Long, sourceBookId: String?, sourcePassageId: String?)`. DAOs expose Flow list/detail and insert/delete transactions.

- [ ] Create Gradle Android project, app theme, navigation shell, Room entities and DAO tests that verify cascade of passages/translations and retention of vocabulary after book deletion.
- [ ] Run `./gradlew testDebugUnitTest`; establish a passing baseline.
- [ ] Add persistent font-size preference through DataStore and verify serialization in a unit test.

### Task 2: Import and parse PDF/EPUB

**Files:** `importing/BookImporter.kt`, `importing/PdfParser.kt`, `importing/EpubParser.kt`, `importing/PassageSplitter.kt`, `ui/library/LibraryScreen.kt`, `ui/library/LibraryViewModel.kt`; parser fixtures and tests in matching test packages.

**Interfaces:** `BookParser.parse(input: InputStream): ParsedBook`; `ParsedBook(title: String?, chapters: List<ParsedChapter>)`; `ParsedChapter(title: String?, paragraphs: List<String>)`; `BookImporter.import(uri: Uri): Result<String>` returns new book ID. Import copies bytes to app-private storage and inserts metadata/passages in one transaction after successful parse.

- [ ] Write parser tests for EPUB spine order, PDF paragraph extraction, empty image-only PDF, malformed EPUB, and sentence-group splitting of a long paragraph; run the focused tests and see them fail.
- [ ] Implement parsers and importer; rerun focused tests to pass. Validate extension and content type, give user-readable failure messages, and keep same-name books distinct by generated ID.
- [ ] Add system document picker and library list with import progress, retry/error, open, and delete; run unit tests and `./gradlew assembleDebug`.

### Task 3: Bilingual reader and translation cache

**Files:** `translation/TranslationService.kt`, `translation/MlKitTranslationService.kt`, `ui/reader/ReaderViewModel.kt`, `ui/reader/ReaderScreen.kt`, `ui/reader/PassageCard.kt`; tests in `translation/` and `ui/reader/`.

**Interfaces:** `TranslationService.translate(text: String): Result<String>` and `ensureModel(): Flow<ModelStatus>`; `ReaderViewModel` exposes ordered passages, per-passage English and cached Chinese, model status, and `retryTranslation(passageId: String)` / `savePosition(passageId: String)`.

- [ ] Test ordered passages, translation cache hit, model-download failure/retry, and persisted reading position with a fake translator; run focused tests to confirm failure.
- [ ] Implement ML Kit adapter, background translation queue, Room cache, and reader state; rerun focused tests.
- [ ] Build readable paragraph-paired screen with loading/error states and font scaling; verify a long book scrolls smoothly and `./gradlew assembleDebug` passes.

### Task 4: Lookup, speech, and vocabulary

**Files:** `lookup/DictionaryService.kt`, `lookup/FreeDictionaryService.kt`, `speech/PronunciationService.kt`, `ui/lookup/LookupViewModel.kt`, `ui/lookup/LookupSheet.kt`, `ui/vocabulary/VocabularyScreen.kt`, `ui/vocabulary/VocabularyViewModel.kt`; tests in matching packages.

**Interfaces:** `DictionaryService.lookup(query: String): Result<DictionaryEntry?>`; `DictionaryEntry(word: String, phonetics: List<Phonetic>, definitions: List<Definition>)`; `PronunciationService.speak(text: String, accent: Accent): SpeechResult`; `LookupViewModel.lookup(query: String, sourceBookId: String?, sourcePassageId: String?)`; `VocabularyViewModel.add/remove/undoRemove` normalize by trimmed lowercase key.

- [ ] Test found/missing/offline dictionary entries, Chinese definition translation, single-word deduplication, phrase fallback, stored offline entry, delete/undo, and unavailable TTS voice; run tests to confirm failure.
- [ ] Implement dictionary HTTP adapter with timeouts, definition translation/cache, TTS voice availability checks, and vocabulary operations; rerun focused tests.
- [ ] Enable tap word and text-selection phrase lookup, bottom sheet, full entry detail, example playback, notebook search/list/delete; verify accessibility labels and `./gradlew testDebugUnitTest assembleDebug`.

### Task 5: Adaptive UI and end-to-end verification

**Files:** `ui/AdaptiveScaffold.kt`, `ui/library/LibraryScreen.kt`, `ui/reader/ReaderScreen.kt`, `ui/lookup/LookupSheet.kt`, `ui/vocabulary/VocabularyScreen.kt`, `README.md`.

**Interfaces:** Compact width uses bottom navigation and lookup bottom sheet; expanded width uses reading/content pane plus contextual side pane. Navigation preserves book and passage IDs across size changes.

- [ ] Add compact/expanded Compose UI tests for navigation, lookup placement, and state restoration; run tests to confirm failure.
- [ ] Implement adaptive layouts and dynamic-font behavior; rerun tests.
- [ ] Validate PDF/EPUB import, translated paragraphs, word and phrase lookup, US/UK speech, vocabulary add/delete, offline cache, and resume position on available emulator/device. Run `./gradlew testDebugUnitTest connectedDebugAndroidTest assembleDebug` where a device exists; if unavailable, report the unrun device checks explicitly.
- [ ] Document build steps, model download, network boundaries, supported file limits, and known free-dictionary limitations in `README.md`.
