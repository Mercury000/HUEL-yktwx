pluginManagement {
    repositories { gradlePluginPortal(); google(); mavenCentral() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri("https://maven.aliyun.com/repository/public") } }
            filter { includeGroup("com.alipay.sdk") }
        }
        google(); mavenCentral()
    }
}
plugins { id("org.gradle.toolchains.foojay-resolver-convention").version("1.0.0") }
rootProject.name = "CaiXiaoTong"
include(":app")
