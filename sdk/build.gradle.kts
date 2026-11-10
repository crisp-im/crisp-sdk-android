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
    compileSdk {
        version = release(37)
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
        // Android methods (e.g. android.util.Log) return default values instead of throwing
        unitTests.isReturnDefaultValues = true
    }
    publishing {
        // Not `withSourcesJar()`, which would disclose `internal` sources: see `publicSourcesJar`
        singleVariant("release")
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
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.window)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.ui.tooling)
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
