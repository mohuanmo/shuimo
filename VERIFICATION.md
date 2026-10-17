# GlassKey 输入法 — 源代码落地与验证报告

> **注（2026-10-17）**：本文件是 M1 阶段（2026-09-06）的验证快照，其中「APK 未申请任何权限」等结论已被后续版本取代——现行权限以 `app/src/main/AndroidManifest.xml` 为准（INTERNET 仅用于词库订阅，另含录音与悬浮窗权限）。其余引擎测试结论仍然有效。


> 交付依据：《输入法App工程设计方案 v1.0》｜范围：源代码编写 + 验证（Android APP）
> 日期：2026-09-06

## 一、交付总览

| 项 | 结果 |
|---|---|
| C++ 核心引擎宿主测试 | **100 checks, 0 failures** |
| Kotlin/JVM 单元测试 | **17 tests, 0 failures**（4 个测试类） |
| Gradle 构建 | **BUILD SUCCESSFUL**，产出 `app-debug.apk`（1.85 MB） |
| 权限审计 | APK **未申请任何权限**（无 INTERNET，§S-02 达标） |
| 原生库 | `libglasskey.so` 双架构（arm64-v8a / x86_64）随包分发 |
| 词典资产 | `gk_zh.gkdt`（26 KB）/ `gk_en.gkdt`（12 KB）打入 APK assets |

## 二、构建环境（受限网络，全镜像替代）

- Gradle 8.9 + AGP 8.5.2 + Kotlin 2.0.20（腾讯云 / 阿里云 Maven 镜像）
- NDK 27.0.12077973、CMake 3.22.1（SDK 目录布局手工修复）、platform android-34、build-tools 34.0.0
- minSdk 26（Android 8.0+），targetSdk 34；纯 framework API，无 androidx 运行时依赖

## 三、核心引擎验证（C++ 宿主测试，10 个测试组 / 100 断言）

| 测试组 | 覆盖点 | 结果 |
|---|---|---|
| test_syllable_table | ~410 有序音节表二分查找（含前缀/超界负例） | ✅ |
| test_segment_basic | 精确切分、`xi'an` 分隔符边界边 | ✅ |
| test_segment_fuzzy | KeyVariants 键变体双向展开（si↔shi、fan↔fang、li↔ni），变体惩罚与上限 | ✅ |
| test_dict_fixture | GKDT 段头/惰性 CRC/同音词集合/前缀查询 | ✅ |
| test_dict_crc | 词典字节篡改 → E_DICT_CRC 拒载 | ✅ |
| test_decode_golden | `nihao→你好` 首选、`xi'an→西安`、整句 `jintian…tianqi 含今天/天气`、词组提交回填、20 字节封顶 | ✅ |
| test_t9 | `64426→你好`、可能拼音串横滑条（含 ni/hao） | ✅ |
| test_user_model | blob 装载 Boost 重排（县↑）、学习+会话衰减、learning=0 隔离 | ✅ |
| test_latin_engine | `hell→hello` 补全、`teh→the` Damerau 更正、`appl→apple`、提交清空 | ✅ |
| test_c_api | C ABI 全链路：创建/击键/候选/提交（文本+拼音）/退格边界/T9/用户词/模糊音/坏词典拒绝 | ✅ |

关键修正（均由测试驱动定位）：

1. **词典容器段头类型检查** `type > 7` 漏掉 SEC_WLIST=8 → 43 个级联失败的根因。
2. **unigram 概率归一化**：词分扣 `log10(Σfreq)`，消除"高频单字拼凑（四+是 8.76 分）碾压词典词（时事 7.43 分）"的模型缺陷；候选统一按综合分排序。
3. **模糊音轻惩罚**：变体权重 2.0→0.3，否则全变体词永远排在部分匹配组合之后。
4. **latin 提交输出缺失**：CommitCandidate 拉丁分支未拷贝文本（乱码来源）。
5. **latin 评分再平衡**：补全 +0.5 / 更正 −0.9×cost+0.25×adj，`hell→hello` 优先于邻键替换 `help`。
6. **Boost 封顶不一致**：cap 后统一 ×0.8；词组候选 ids 改按含 Boost 的综合分排序。

## 四、Kotlin 层验证（17 tests）

- `EditorMapperTest`（5）：输入类型 → 布局映射（文本/数字/URI/邮箱/密码）、回车动作
- `LatinCapHelperTest`（5）：Shift 三态循环、单次大写回退、句首自动大写
- `KeyboardDefsTest`（4）：三布局键位完整性（26 字母+分隔符 / T9 数字 2-9 / Shift+回车）、权重合法
- `UserDictBlobTest`（3）：`word\0pinyin\0freq(u32 LE)` blob 契约与 50 条往返一致性

## 五、APK 审计（aapt2 dump badging）

```
package: name='com.glasskey.ime' versionCode='1' versionName='1.0.0'
sdkVersion:'26'  targetSdkVersion:'34'
native-code: 'arm64-v8a' 'x86_64'
uses-permission: （无 —— 纯净版达标）
lib/arm64-v8a/libglasskey.so   lib/x86_64/libglasskey.so
assets/dict/gk_zh.gkdt (26337B)  assets/dict/gk_en.gkdt (11922B)
```

## 六、设计符合性与已知边界

| 设计条目 | 状态 |
|---|---|
| §4.1/4.2 拼音管线（切分→DAT→Beam→重排） | ✅（M1 基线：unigram+用户模型） |
| §4.2.2 T9 与拼音串横滑条 | ✅ |
| §4.2.1/4.3 模糊音键变体、n-gram 通道 | 部分（ngram 为 §7.3 预留段，M2 热更启用） |
| §4.4 拉丁补全/更正/大写状态机 | ✅ |
| §4.5 用户模型（学习/衰减/blob/SQLite 单向持久化） | ✅ |
| §5.1 三布局键盘/候选条/EditorInfo 映射 | ✅（磨砂渲染为无依赖近似，跨窗口真模糊留 M2） |
| §5.2 主 App（引导/设置/用户词管理） | ✅ |
| §7.3 GKDT + Python 构建器（含 selftest） | ✅ |
| §8.1 C ABI 边界（gk_api.h / gk_jni） | ✅ |
| §S-02 无网络权限 / §S-03 学习开关 | ✅（真机回归：安装/启用/键盘弹出需设备，建议后续在真机执行冒烟） |

**已知边界**：剪贴板历史（clipboard 包）M1 未启用，规划 M2；符号面板为常用符号直出；整句多字候选取前 N。

## 七、产物清单

- 源码：`GlassKey_src.zip`（同名单文件覆盖上传至项目资产）
- 安装包：`GlassKey-debug.apk`
- 本报告：`VERIFICATION.md`
