#!/usr/bin/env bash
# GlassKey v1.4 新增 JVM 模块单元测试运行器（无需 Android SDK）
# 方案：生成独立 Kotlin/JVM Gradle 工程（仅含 4 个纯 Kotlin 新模块 + JUnit4 测试），
# 复用项目同款腾讯云/阿里云 Maven 镜像；宿主 `gradle test` 直跑。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJ=/tmp/gk-jvm-tests

mkdir -p "$PROJ/src/main/kotlin/com/glasskey/ime/voice" \
         "$PROJ/src/main/kotlin/com/glasskey/ime/pinyin" \
         "$PROJ/src/main/kotlin/com/glasskey/ime/hwr" \
         "$PROJ/src/test/kotlin/com/glasskey/ime/voice" \
         "$PROJ/src/test/kotlin/com/glasskey/ime/pinyin" \
         "$PROJ/src/test/kotlin/com/glasskey/ime/hwr"

cp "$ROOT"/app/src/main/java/com/glasskey/ime/voice/AudioLeveler.kt        "$PROJ/src/main/kotlin/com/glasskey/ime/voice/"
cp "$ROOT"/app/src/main/java/com/glasskey/ime/voice/VoicePostProcessor.kt  "$PROJ/src/main/kotlin/com/glasskey/ime/voice/"
cp "$ROOT"/app/src/main/java/com/glasskey/ime/pinyin/ContinuationResolver.kt "$PROJ/src/main/kotlin/com/glasskey/ime/pinyin/"
cp "$ROOT"/app/src/main/java/com/glasskey/ime/hwr/HwrModelContract.kt      "$PROJ/src/main/kotlin/com/glasskey/ime/hwr/"
cp "$ROOT"/app/src/test/java/com/glasskey/ime/voice/AudioLevelerTest.kt        "$PROJ/src/test/kotlin/com/glasskey/ime/voice/"
cp "$ROOT"/app/src/test/java/com/glasskey/ime/voice/VoicePostProcessorTest.kt  "$PROJ/src/test/kotlin/com/glasskey/ime/voice/"
cp "$ROOT"/app/src/test/java/com/glasskey/ime/pinyin/ContinuationResolverTest.kt "$PROJ/src/test/kotlin/com/glasskey/ime/pinyin/"
cp "$ROOT"/app/src/test/java/com/glasskey/ime/hwr/HwrModelContractTest.kt      "$PROJ/src/test/kotlin/com/glasskey/ime/hwr/"

cat > "$PROJ/settings.gradle.kts" <<'EOF'
pluginManagement {
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/central")
    }
}
dependencyResolutionManagement {
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://maven.aliyun.com/repository/central")
    }
}
rootProject.name = "gk-jvm-tests"
EOF

cat > "$PROJ/build.gradle.kts" <<'EOF'
plugins { kotlin("jvm") version "2.0.20" }
dependencies { testImplementation("junit:junit:4.13.2") }
tasks.test { useJUnit() }
EOF

cd "$PROJ" && gradle test "$@"
echo "测试报告：$PROJ/build/reports/tests/test/index.html"
