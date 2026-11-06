// Top-level build file where you can add configuration options common to all sub-projects/modules.
// Fixes java.lang.NoSuchMethodError: 'void org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream.putArchiveEntry(org.apache.commons.compress.archivers.zip.ZipArchiveEntry)'
// when deploying with JReleaser, surely added since 1.26, see https://stackoverflow.com/questions/78638699/bootjar-task-fails-with-gradle-8-5-and-spring-boot-3-3-0-caused-by-spring-boot#answer-78698356
buildscript {
    configurations.configureEach {
        resolutionStrategy {
            force("org.apache.commons:commons-compress:1.27.1")
        }
    }
}
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}