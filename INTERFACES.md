# 接口速查

给要改代码的人看的：东西在哪个文件、叫什么、管什么事。只列 App 自己的接口，构建环境和测试记录不放在这里。

## Android 入口

| 位置 | 接口 | 管什么 |
|---|---|---|
| `app/src/main/java/com/glasskey/ime/GkImeService.kt` | `GkImeService : InputMethodService` | 输入法服务本体，键盘视图、候选栏、上屏、各面板切换都在这里 |
| `app/src/main/java/com/glasskey/app/MainActivity.kt` | `MainActivity` | 启用引导和设置页 |
| `app/src/main/java/com/glasskey/app/LexiconManagerActivity.kt` | `LexiconManagerActivity` | 词库订阅的添加、删除、更新 |
| `app/src/main/java/com/glasskey/app/UserDictActivity.kt` | `UserDictActivity` | 用户词列表和删除 |
| `app/src/main/java/com/glasskey/app/LearnDataActivity.kt` | `LearnDataActivity` | 学习数据查看和清空 |
| `app/src/main/java/com/glasskey/app/DevActivity.kt` | `DevActivity` | 开发者选项 |
| `app/src/main/AndroidManifest.xml` | — | 服务、Activity 和三个权限的声明 |

## 引擎

| 位置 | 接口 | 管什么 |
|---|---|---|
| `core/include/glasskey/gk_api.h` | `ime_*` | 稳定 C ABI，JNI 下面的那一层 |
| `core/include/glasskey/gk_types.h` | `EngineConfig`、`Layout`、`FuzzyMask`、`LearnSource`、`ErrorCode` | 结构体和枚举 |
| `app/src/main/java/com/glasskey/ime/engine/GkNative.kt` | `GkNative` | JNI 声明，加载 `libglasskey` |
| 同上 | `GkEngine` | Kotlin 门面，持有原生句柄，管打开、关闭和转发调用 |
| 同上 | `GkLayout`、`GkFuzzy`、`GkLearnSource` | 布局、模糊音、学习来源的常量 |

C ABI 按用途分：

- 生命周期：`ime_engine_create`、`ime_engine_destroy`、`ime_version`、`ime_last_error`
- 模式设置：`ime_set_layout`、`ime_set_fuzzy`、`ime_set_learning`
- 输入：`ime_process_key`、`ime_backspace`、`ime_reset`、`ime_get_input`、`ime_get_segmented_input`、`ime_insert_manual_separator`、`ime_set_context`
- 候选：`ime_candidate_count`、`ime_get_candidate`、`ime_get_candidate_pinyin`、`ime_commit_candidate`、`ime_t9_pinyin_count`、`ime_get_t9_pinyin`
- 学习：`ime_learn`、`ime_load_user_words`、`ime_session_end`

## 键盘和输入行为

| 位置 | 接口 | 管什么 |
|---|---|---|
| `app/src/main/java/com/glasskey/ime/ui/KeyboardDefs.kt` | `KeyboardDefs` | 26 键、九宫格、拉丁三套键位 |
| `app/src/main/java/com/glasskey/ime/ui/KeyboardView.kt` | `KeyboardView` | 自绘键盘和按键回调 |
| `app/src/main/java/com/glasskey/ime/ui/CandidateBar.kt` | `CandidateBar` | 候选栏 |
| `app/src/main/java/com/glasskey/ime/ui/ToolbarView.kt` | `ToolbarView` | 键盘上方的工具条 |
| `app/src/main/java/com/glasskey/ime/ui/SymbolPanel.kt` | `SymbolPanel` | 符号面板 |
| `app/src/main/java/com/glasskey/ime/input/EditorMapper.kt` | `EditorMapper` | `EditorInfo` 到布局和回车动作的映射 |
| `app/src/main/java/com/glasskey/ime/input/LatinCapHelper.kt` | `LatinCapHelper` | 英文大小写状态 |
| `app/src/main/java/com/glasskey/ime/pinyin/ContinuationResolver.kt` | `ContinuationResolver` | 连续拼音的切分和上屏判断 |
| `app/src/main/java/com/glasskey/ime/t9/T9InitialIndex.kt` | `T9InitialIndex` | 九宫格首字母索引 |

## 语音、手写、悬浮、剪贴板

| 位置 | 接口 | 管什么 |
|---|---|---|
| `app/src/main/java/com/glasskey/ime/voice/SpeechEngine.kt` | `AsrEngine` | 语音后端接口：`startSession`、`feedAudio`、`stopSession`、`release` |
| 同上 | `VoiceModels` | 模型文件的位置和是否就绪 |
| `app/src/main/java/com/glasskey/ime/hwr/HwrEngine.kt` | `HwrEngine` | 手写后端接口：`ready`、`displayName`、`recognize`、`close` |
| 同上 | `BuiltinHwrEngine`、`OnnxHwrEngine` | 两个实现，前者是点云占位，后者当前加载不起来 |
| `app/src/main/java/com/glasskey/ime/floating/FloatingOverlayController.kt` | `FloatingOverlayController` | 悬浮窗的显示、隐藏、拖动和宽度 |
| `app/src/main/java/com/glasskey/ime/clipboard/ClipDb.kt` | `ClipDb` | 剪贴板历史：`insert`、`all`、`setPinned`、`delete`、`clear` |
| `app/src/main/java/com/glasskey/ime/clipboard/ClipPanel.kt` | `ClipPanel` | 剪贴板面板 |

## 数据和设置

| 位置 | 接口 | 管什么 |
|---|---|---|
| `app/src/main/java/com/glasskey/ime/data/UserDictDb.kt` | `UserDictDb` | 用户词 SQLite：`upsert`、`all`、`delete`、`clear` |
| `app/src/main/java/com/glasskey/app/GkPrefs.kt` | `GkPrefs` | 主题、键盘高度、候选数、学习开关这些设置项 |
| `app/src/main/java/com/glasskey/app/LexiconStore.kt` | `LexiconStore` | 内置词库部署、文件/URL 导入、订阅管理 |
| `app/src/main/java/com/glasskey/ime/predict/NextWordPredictor.kt` | `NextWordPredictor` | 下一词预测和统计 |
| `app/src/main/java/com/glasskey/ime/predict/RecentHotWords.kt` | `RecentHotWords` | 短期热词 |

## 几个数据格式

- 用户词 blob：`word\0pinyin\0freq(u32 LE)` 一条接一条，Kotlin 拼好交给 `ime_load_user_words`
- 词典 `.gkdt`：magic `GKDT` + 版本 + 段头表，DAT 的键是空格分隔的拼音
- 订阅：记录存在 `filesDir/subs/subs.json`，下载下来的词库在 `filesDir/dict/` 和 `filesDir/corpus/`
