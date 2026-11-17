
plugins {
    kotlin("multiplatform")
    `resolve-test-data`
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    linuxArm64()
    linuxX64()

    sourceSets.commonMain.dependencies {
        implementation("co.touchlab:sqliter-driver:1.4.0")
    }
}
