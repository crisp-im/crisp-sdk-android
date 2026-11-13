// The test app with the pre-3.x SDK, installed as `app` (same `applicationId`),
// so that installing `app` over it tests the migration of its sessions
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "im.crisp.catalog.old"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Same as `app`, to be updated by it
        applicationId = "im.crisp.catalog"
        minSdk = 24
        // As the samples of SDK 2.x, not to enforce edge-to-edge on its ChatActivity
        targetSdk = 36
        // Not above `app`, so that installing it is an update
        versionCode = 1
        versionName = "1.0-old"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("im.crisp:crisp-sdk:2.0.25")
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
