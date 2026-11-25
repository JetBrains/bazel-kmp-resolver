plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    `resolve-test-data`
}

kotlin {
    jvm()
    wasmJs()
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    linuxArm64()
    linuxX64()

    android {
        namespace = "kmp-popular"
        compileSdk = 37
    }

    sourceSets.commonMain.dependencies {
        implementation("io.ktor:ktor-client-core:3.6.1")
        implementation("io.insert-koin:koin-core:4.2.2")
        implementation("com.squareup.okio:okio:3.18.2")
        implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2")
        implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
        implementation("co.touchlab:kermit:2.2.0")
        implementation("com.russhwolf:multiplatform-settings:1.3.0")
    }
}
