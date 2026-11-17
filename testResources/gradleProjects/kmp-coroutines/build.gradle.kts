
plugins {
    kotlin("multiplatform")
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

    sourceSets.commonMain.dependencies {
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    }
}
