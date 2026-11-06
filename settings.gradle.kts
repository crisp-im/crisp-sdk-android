pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

/**
 * Reads the value of the [service] item from the macOS Keychain,
 * absent if not on macOS or if the item is missing.
 */
fun keychainValue(service: String): Provider<String> =
    if (System.getProperty("os.name").startsWith("Mac")) {
        providers.exec {
            commandLine("security", "find-generic-password", "-s", service, "-w")
            isIgnoreExitValue = true
        }.standardOutput.asText.map { it.trim() }.filter { it.isNotEmpty() }
    } else {
        providers.provider { null }
    }

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Release testing, only queried for the SDK (used by `app` when depending on a published version, built by hand),
        // and only declared with the Maven Central credentials from the `crisp-sdk-maven-central-*` macOS Keychain items
        // (see DEVELOPING.md)
        val mavenCentralUser = keychainValue("crisp-sdk-maven-central-user").orNull
        val mavenCentralToken = keychainValue("crisp-sdk-maven-central-token").orNull
        if (mavenCentralUser != null && mavenCentralToken != null) {
            maven {
                name = "centralManualTesting"
                url = uri("https://central.sonatype.com/api/v1/publisher/deployments/download/")
                credentials(HttpHeaderCredentials::class) {
                    val userToken = java.util.Base64.getEncoder()
                        .encodeToString("$mavenCentralUser:$mavenCentralToken".toByteArray())
                    name = "Authorization"
                    value = "Bearer $userToken"
                }
                authentication {
                    create<HttpHeaderAuthentication>("header")
                }
                content {
                    includeModule("im.crisp", "crisp-sdk")
                }
            }
        }
        // Snapshots
        maven {
            name = "Central Portal Snapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            content {
                includeModule("im.crisp", "crisp-sdk")
            }
        }
    }
}

rootProject.name = "DemoApp"
include(":app")
include(":sdk")
// `client` is not a module: its generated sources, assets and res are bundled into `sdk` (see `sdk/build.gradle.kts`)
