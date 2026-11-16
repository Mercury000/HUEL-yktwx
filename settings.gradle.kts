import java.util.Properties

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
        exclusiveContent {
            forRepository { maven {
            name = "MiuixSnapshots"
            url = uri("https://maven.pkg.github.com/compose-miuix-ui/miuix")
            mavenContent { snapshotsOnly() }
            val local = Properties().apply {
                settingsDir.resolve("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
            }
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                    ?: local.getProperty("github.actor", "token")
                password = providers.environmentVariable("MIUIX_PACKAGES_TOKEN").orNull
                    ?: providers.environmentVariable("GITHUB_TOKEN").orNull
                    ?: providers.environmentVariable("GH_TOKEN").orNull
                    ?: local.getProperty("github.token")
            }
            } }
            filter { includeGroup("top.yukonga.miuix.kmp") }
        }
    }
}
plugins { id("org.gradle.toolchains.foojay-resolver-convention").version("1.0.0") }
rootProject.name = "CaiXiaoTong"
include(":app")
include(":benchmark")
