# 英语书伴读

原生 Android 手机／平板应用：导入可提取文字的 PDF、EPUB，逐段显示英文和中文译文，查询单词或短语，播放英式／美式发音，并管理生词本。

## 构建

用 Android Studio 打开项目，安装 Android SDK 35 与 JDK 17，完成 Gradle 同步后运行 `./gradlew testDebugUnitTest assembleDebug` 或在设备上启动。最低 Android 8.0（API 26）。首次使用书籍段落翻译需要下载 ML Kit 英中模型；查词优先读取随应用打包的 ECDICT 离线数据库。发音优先使用系统英式／美式离线 TTS；本地语音不可用时使用音频缓存或联网获取发音。

## 使用

阅读页顶部提供“关闭翻译／打开翻译”按钮，统一隐藏或显示所有段落后的中文译文。默认显示，开关选择在本地保存，重新打开应用后沿用。关闭时隐藏翻译进度、失败提示和重试按钮，并暂停启动新的段落翻译；已有译文仍保存在本地，再打开即可显示。

发音按钮会等待系统语音初始化，优先选择对应口音的离线语音。初始化失败、超时、缺少口音或合成播放报错时，使用有道公开发音接口 `https://dict.youdao.com/dictvoice`（`type=1` 英式，`type=2` 美式）补充，并将成功下载的 MP3 分口音保存在应用私有目录。该公开接口没有稳定性承诺，失败时显示错误弹窗和系统语音设置入口。没有系统英语语音且没有相应缓存时，首次播放需要联网；已缓存音频可在断网时重用。发音走媒体音量，静音时会提示调高媒体音量，蓝牙连接时声音可能输出到耳机。

在书架点“导入 PDF / EPUB”，选择文件。阅读时会自动记录当前段落，下次打开同一本书会回到上次位置。点击阅读页右上角“目录”可跳转到章节开头；EPUB 优先使用书籍自带的 NCX/导航目录，PDF 使用页码。旧版已导入的 EPUB 会在打开时自动补全章节标记，保留已有译文和阅读进度。阅读页中点击英文单词可查词，词条弹层提供相邻短语快捷入口；长按可选中原文，也可在顶部“查询单词或短语”输入内容查询。词条弹层提供释义、发音、外部例句和加入生词本。生词本支持搜索、查看和删除，删除后可撤销。

查词顺序为生词本缓存 → ECDICT 离线词库 → 在线服务。离线词库有 768,739 条含中文释义的词条（包括部分短语），以及 66,613 条词形映射。已收录的单词或短语直接显示中文释义、已有英文释义和音标，不请求网络或下载翻译模型；部分词条缺少音标或英文释义。

外部例句包来自 Open English WordNet 2025，包含 48,583 条词条与例句对应记录，覆盖 23,334 个单词或短语。例句筛选为包含该词或其词形的句子，每次显示最多两条；没有匹配例句时显示“暂无外部例句”。设备已安装 ML Kit 英中模型时，后台补充例句中文译文，查词和收藏不等待翻译。生词本保存释义、例句、译文和例句出处，之后直接读取本地数据库。

未收录的词或短语仍使用 Free Dictionary API、Tatoeba 和设备翻译作为联网补充。“重新查询”跳过收藏缓存，但仍优先使用离线词库。扫描版或图片型 PDF 没有可提取文字，本版不支持 OCR。PDF 的段落重建取决于原文件排版。

## 离线数据与重建

ECDICT 来源：https://github.com/skywind3000/ECDICT （MIT）。例句来源：https://en-word.net/ ，Open English WordNet Team / Princeton WordNet，CC BY 4.0 及原始 WordNet 许可。许可证随 APK 保存在 `assets/licenses`。例句包仅做筛选、词形匹配和索引，没有修改原句。

下载 ECDICT 的 `ecdict.csv` 和 `https://en-word.net/static/english-wordnet-2025-json.zip`，运行 `python3 scripts/build_offline_dictionary.py /path/ecdict.csv /path/english-wordnet-2025-json.zip` 重建 `assets/dictionary-v1.db` 和统计文件。数据库首次查词时在后台复制到应用私有目录，后续以只读方式打开；用户生词本使用独立的 Room 数据库。打包词库约 81 MiB，安装后会另外占用同等大小的私有存储。

## 本地验证环境

已使用 JDK 17 和 Android SDK 35 运行 `./gradlew testDebugUnitTest assembleDebugAndroidTest assembleDebug`。本地单元测试（含 Robolectric 启动、手机和平板界面测试）和两个 APK 的编译均通过。当前还没有已连接的设备；`connectedDebugAndroidTest` 及手机、平板上的实际交互需要模拟器或真机运行。
