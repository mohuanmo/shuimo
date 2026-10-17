GlassKey 第三方组件声明
========================

运行时（APK 内）
----------------
- sherpa-onnx 1.13.7（语音识别 JNI 封装）：Apache License 2.0
  https://github.com/k2-fsa/sherpa-onnx
- onnxruntime Java API（ONNX 推理运行时绑定）：MIT License
  https://github.com/microsoft/onnxruntime
- 语音模型：sherpa-onnx Zipformer 中文 int8 模型与 Silero VAD，随安装包离线分发（Apache-2.0 / MIT）
- 手写模型：随安装包分发（models/hwr）；当前模型无法正常加载，手写功能不可用
- C++ 核心引擎（core/）：自研
- Kotlin / Java 层：仅使用 Android framework API（android.*，随系统分发）

无统计、无推送、无广告 SDK。Manifest 中的 INTERNET 权限仅用于用户主动添加的
词库订阅链接下载，应用不存在其他联网场景。

测试期依赖（不打入 APK）
------------------------
1. JUnit 4
   - artifact: junit:junit:4.13.2
   - 许可证: Eclipse Public License 1.0（http://junit.org/license.html）
   - 用途: JVM 单元测试框架

构建期工具链（不打入 APK）
--------------------------
- Android Gradle Plugin 8.5.2 / Android SDK / NDK 27
  （Google Android Software Development Kit License）
- Gradle 8.9（Apache-2.0）
- Kotlin 2.0.20（Apache-2.0）
- Python 3（词典构建脚本 tools/dictbuilder，PSF License）

说明
----
以上为随包分发的第三方组件清单。各组件与模型文件按其上游许可证分发，若需
再分发，请自行核对相应条款。项目自身的许可证为 Apache License 2.0，
见 LICENSE 与 NOTICE。
