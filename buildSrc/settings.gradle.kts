pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        // 官方倉庫不可達時（國內網絡）的備用鏡像
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // 官方倉庫不可達時（國內網絡）的備用鏡像
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "buildSrc"
