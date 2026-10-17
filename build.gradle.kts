// GlassKey 根构建文件
// AGP 8.5.2 + Kotlin 2.0.20（经腾讯/阿里镜像解析）
// 注：镜像 POM 传递依赖不完整，必须用 buildscript 经典方式保证 JAXB 运行时与 AGP 同类路径
buildscript {
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.5.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.20")
        // AGP 8.5 在 JDK 17+ 下解析 SDK package.xml 需要 JAXB 运行时
        classpath("javax.xml.bind:jaxb-api:2.3.1")
        classpath("org.glassfish.jaxb:jaxb-runtime:2.3.8")
        classpath("javax.activation:javax.activation-api:1.2.0")
    }
}
