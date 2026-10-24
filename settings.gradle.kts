// 网络探测：构建启动时测试能否直连 Google Maven（国内网络不可达时自动切镜像）
// 可用环境变量 HA1_MIRROR 或系统属性 ha1.mirror 强制指定：on=镜像优先 / off=官方优先
fun googleReachable(): Boolean = runCatching {
    java.net.Socket().use {
        it.connect(java.net.InetSocketAddress("dl.google.com", 443), 2000)
    }
}.isSuccess

val mirrorFirst: Boolean = when (System.getenv("HA1_MIRROR") ?: System.getProperty("ha1.mirror")) {
    "on", "true" -> true
    "off", "false" -> false
    else -> !googleReachable()
}

pluginManagement {
    // 官方仓库（可达时）或国内镜像（不可达时），镜像优先可避免逐个依赖等待官方仓库超时
    repositories {
        if (mirrorFirst) {
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            gradlePluginPortal()
            google()
            mavenCentral()
        } else {
            gradlePluginPortal()
            google()
            mavenCentral()
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (mirrorFirst) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            google()
            mavenCentral()
            maven { url = uri("https://jitpack.io/") }
        } else {
            google()
            mavenCentral()
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://jitpack.io/") }
        }
    }
}
println("Han1meViewer: mirrorFirst=$mirrorFirst (probe dl.google.com:443, 可用 HA1_MIRROR=on/off 覆盖)")
rootProject.name = "Han1meViewer"
include(":app", ":yenaly_libs")
