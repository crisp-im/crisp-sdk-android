import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jreleaser.model.Active
import org.jreleaser.model.Http
import org.jreleaser.model.api.deploy.maven.MavenCentralMavenDeployer
import org.jreleaser.model.api.deploy.maven.Nexus2MavenDeployer

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.jreleaser)
    `maven-publish`
    signing
}

val sdkGroupId = "im.crisp"
val sdkArtifactId = "crisp-sdk"
val sdkVersion = providers.gradleProperty("jreleaserVersion").get() +
        if (providers.gradleProperty("jreleaserSnapshot").orNull.toBoolean()) "-SNAPSHOT" else ""
val stagingDirectory = layout.buildDirectory.dir("staging-deploy")

group = sdkGroupId
version = sdkVersion

android {
    namespace = "im.crisp.sdk"
    // 36 at most, for React Native 0.85+ (see the React Native compatibility in gradle/libs.versions.toml)
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 24

        buildConfigField("String", "VERSION", "\"$sdkVersion\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    sourceSets {
        named("main") {
            // Bundles the generated Web client (see CLAUDE.md) into the SDK, to publish a single artifact
            kotlin.directories += "../client/src/main/java"
            assets.directories += "../client/src/main/assets"
            res.directories += "../client/src/main/res"
        }
    }
    testOptions {
        // The instrumented tests (e.g. tapping notifications) run as the app does
        targetSdk = 36
        // Android methods (e.g. android.util.Log) return default values instead of throwing
        unitTests.isReturnDefaultValues = true
        // Robolectric tests (e.g. notifications) read the SDK resources
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric sets up Android 16+ shared memory through JDK internals, closed by default
            it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
    publishing {
        // Not `withSourcesJar()`, which would disclose `internal` sources: see `publicSourcesJar`
        singleVariant("release")
    }
}

kotlin {
    compilerOptions {
        // React Native 0.85 builds with Kotlin 2.1.20: writes 2.1 metadata and only uses the 2.1 stdlib API
        languageVersion = KotlinVersion.KOTLIN_2_1
        apiVersion = KotlinVersion.KOTLIN_2_1
    }
}

dependencies {
    // TODO: maybe split SDK artifacts between w or w/o compose
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.browser)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.window)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.material)
    // Notifications, only if the app adds Firebase Cloud Messaging: not to force it on apps without notifications.
    // Never referenced outside CrispFirebase and CrispNotificationService, checked to be available at runtime
    compileOnly(libs.firebase.messaging)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Tests of Android components (notifications, activities) on the JVM, see `@RunWith(AndroidJUnit4::class)`
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // Taps the notifications in the shade, see CrispNotificationTapTest
    androidTestImplementation(libs.androidx.uiautomator)
}

// Documentation
// Generated from KDoc by Dokka, with `./gradlew :sdk:dokkaGeneratePublicationHtml` into `sdk/build/dokka/html`.
// Published to GitHub Pages by `.github/workflows/docs.yml`, and bundled as the `-javadoc.jar` for Maven Central.
dokka {
    moduleName.set("Crisp SDK Android")
    moduleVersion.set(sdkVersion)
    dokkaPublications.html {
        // Hides members inherited from Android parents (e.g. `Fragment`, `View`), only keeping declared or overridden ones
        suppressInheritedMembers.set(true)
    }
    dokkaSourceSets.configureEach {
        includes.from("Module.md")
        // Also the protected members meant to be overridden (e.g. of `CrispNotificationService`),
        // those only overridden from Android being hidden with `@suppress`
        documentedVisibilities(VisibilityModifier.Public, VisibilityModifier.Protected)
        // Only documents the public API (`api`, `model` and `ui`)
        perPackageOption {
            matchingRegex.set("""im\.crisp\.sdk\.internal(\..*)?""")
            suppress.set(true)
        }
        perPackageOption {
            matchingRegex.set("""im\.crisp\.client(\..*)?""")
            suppress.set(true)
        }
        // TODO: update to `master` branch when released
        //  Also consider a release tag instead of `master`, for line anchors to match each version
        sourceLink {
            localDirectory.set(file("src/main/java"))
            remoteUrl("https://github.com/crisp-im/crisp-sdk-android/tree/beta/sdk/src/main/java")
            remoteLineSuffix.set("#L")
        }
    }
}

// Dokka titles every package page "Package-level declarations", which no option changes: titled here instead.
// A package missing from it keeps its name as title, with a warning
val packageTitles = mapOf(
    "im.crisp.sdk.api" to "API",
    "im.crisp.sdk.api.controller" to "Crisp controllers",
    "im.crisp.sdk.api.controller.chatbox" to "Crisp Chatbox controller",
    "im.crisp.sdk.api.notification" to "Notifications",
    "im.crisp.sdk.model" to "Models",
    "im.crisp.sdk.ui" to "UI components",
)
tasks.dokkaGeneratePublicationHtml {
    val outputDir = outputDirectory
    val titles = packageTitles
    inputs.property("packageTitles", titles)
    doLast {
        val heading = Regex("""<h1 class="cover">(?:\s*<span><span>[^<]*</span></span>)+\s*</h1>""")
        val pageTitle = Regex("""<title>([^<]+)</title>""")
        outputDir.get().asFile.walk()
            .filter { it.name == "index.html" }
            .forEach { page ->
                val html = page.readText()
                if ("""data-page-type="package"""" !in html) return@forEach
                val packageName = pageTitle.find(html)?.groupValues?.get(1)
                val match = heading.find(html)
                if (packageName == null || match == null || "Package-level" !in match.value) {
                    logger.warn("Cannot rename the package page heading of ${page.parentFile.name}, its markup changed")
                    return@forEach
                }
                val title = titles[packageName] ?: packageName.also {
                    logger.warn("No title for package $it in packageTitles of sdk/build.gradle.kts")
                }
                page.writeText(html.replaceRange(match.range, """<h1 class="cover"><span><span>$title</span></span></h1>"""))
            }
    }
}

val dokkaJavadocJar by tasks.registering(Jar::class) {
    description = "Assembles the Dokka HTML documentation as the javadoc JAR for Maven Central."
    group = JavaBasePlugin.DOCUMENTATION_GROUP
    from(tasks.dokkaGeneratePublicationHtml.flatMap { it.outputDirectory })
    archiveClassifier.set("javadoc")
}

// Lets IDEs show the KDoc of the public API (`api`, `model` and `ui`), without disclosing `internal` sources
val publicSourcesJar by tasks.registering(Jar::class) {
    description = "Assembles the sources of the public API as the sources JAR for Maven Central."
    group = JavaBasePlugin.DOCUMENTATION_GROUP
    from("src/main/java") {
        include("im/crisp/sdk/api/**", "im/crisp/sdk/model/**", "im/crisp/sdk/ui/**")
    }
    archiveClassifier.set("sources")
}

// Declares `publicSourcesJar` as the sources variant in Gradle Module Metadata (`.module`), as `withSourcesJar()` does:
// Gradle and IDEs look sources up there, not by the `sources` classifier of the POM
val publicSourcesElements by configurations.registering {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.SOURCES))
    }
    outgoing.artifact(publicSourcesJar)
}

// Publish to Maven Central
// Usage
// To distribute:
//  set the `ORG_GRADLE_PROJECT_crispSdk*` env vars (signing key and Maven Central credentials), see DEVELOPING.md
//  update `jreleaserVersion` & `jreleaserSnapshot` in `gradle.properties` file
//  in project folder, run `mise run publish-sdk` (`./gradlew clean :sdk:publish && ./gradlew :sdk:jreleaserDeploy`)
//  for release, login to https://central.sonatype.com/publishing/deployments, check files, test and Drop or Publish
//  for snapshot, check files at https://central.sonatype.com/service/rest/repository/browse/maven-snapshots/im/crisp/crisp-sdk/
// To include, add this line to `build.gradle.kts` dependencies (with `mavenCentral()` in repositories):
// ```
//  implementation("im.crisp:crisp-sdk:<version>")
// ```
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = sdkGroupId
            artifactId = sdkArtifactId
            version = sdkVersion
            artifact(dokkaJavadocJar)
            // The `release` component is only created by AGP once the project is evaluated
            afterEvaluate {
                val release = components["release"] as AdhocComponentWithVariants
                // Also published in the POM with the `sources` classifier
                release.addVariantsFromConfiguration(publicSourcesElements.get()) {
                    mapToOptional()
                }
                from(release)
            }

            pom {
                name.set("$sdkGroupId:$sdkArtifactId")
                description.set(
                    "Chat with app users, integrate your favorite tools, and deliver a great customer experience.\n" +
                            "A demo project is included in the SCM URL, allowing to quickly test the Crisp Android SDK."
                )
                url.set("https://crisp.chat/")
                inceptionYear.set("2017")
                licenses {
                    license {
                        name.set("Crisp IM SAS License")
                        url.set("https://crisp.chat/terms/")
                    }
                }
                developers {
                    developer {
                        name.set("Baptiste Jamin")
                        email.set("baptiste@crisp.chat")
                        organizationUrl.set("https://crisp.chat/")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/crisp-im/crisp-sdk-android.git")
                    developerConnection.set("scm:git:ssh://github.com/crisp-im/crisp-sdk-android.git")
                    url.set("https://github.com/crisp-im/crisp-sdk-android/tree/master")
                }
            }
        }
    }
    repositories {
        maven {
            url = uri(stagingDirectory)
        }
    }
}

signing {
    // In-memory ASCII-armored key, only set when publishing, other builds don't need it
    val signingKey = providers.gradleProperty("crispSdkSigningKey").orNull
    if (signingKey != null) {
        useInMemoryPgpKeys(
            providers.gradleProperty("crispSdkSigningKeyId").orNull,
            signingKey,
            providers.gradleProperty("crispSdkSigningPassword").orNull,
        )
    }
    sign(publishing.publications["release"])
}

jreleaser {
    // Need to be uncommented to run `./gradlew jreleaserConfig`
    //gitRootSearch.set(true)
    project {
        name.set(sdkArtifactId)
        languages {
            java {
                groupId.set(sdkGroupId)
            }
        }
        version.set(sdkVersion)
    }
    // Waiting for a way to disable release, see https://github.com/jreleaser/jreleaser/issues/1742
    // Need to be uncommented to run `./gradlew jreleaserConfig`
    /*release {
        gitlab {
            //enabled.set(false)
            token.set("empty")
        }
    }*/
    deploy {
        maven {
            mavenCentral {
                register("release") {
                    active.set(Active.RELEASE)
                    // Use PUBLISH or FULL to publish automatically (not currently wanted, we want to check binaries...)
                    stage.set(MavenCentralMavenDeployer.Stage.UPLOAD)
                    url.set("https://central.sonatype.com/api/v1/publisher")
                    stagingRepository(stagingDirectory.get().asFile.path)
                    username.set(providers.gradleProperty("crispSdkMavenCentralUser"))
                    password.set(providers.gradleProperty("crispSdkMavenCentralToken"))
                    // sign and checksums are done by maven-publish and signing plugins,
                    // verifyPom is disabled because pomchecker does not recognize `aar` packaging and google maven repository
                    applyMavenCentralRules.set(false)
                    sourceJar.set(true)
                    javadocJar.set(true)
                }
            }
            nexus2 {
                register("snapshot") {
                    active.set(Active.SNAPSHOT)
                    snapshotSupported.set(true)
                    // comment to publish automatically (not currently wanted, we want to check binaries...)
                    endStage.set(Nexus2MavenDeployer.Stage.UPLOAD)
                    url.set("https://central.sonatype.com/api/v1/publisher")
                    snapshotUrl.set("https://central.sonatype.com/repository/maven-snapshots/")
                    stagingRepository(stagingDirectory.get().asFile.path)
                    username.set(providers.gradleProperty("crispSdkMavenCentralUser"))
                    password.set(providers.gradleProperty("crispSdkMavenCentralToken"))
                    authorization.set(Http.Authorization.BEARER)
                    // sign and checksums are done by maven-publish and signing plugins,
                    // verifyPom is disabled because pomchecker does not recognize `aar` packaging and google maven repository
                    applyMavenCentralRules.set(false)
                    sourceJar.set(true)
                    javadocJar.set(true)
                }
            }
        }
    }
}
