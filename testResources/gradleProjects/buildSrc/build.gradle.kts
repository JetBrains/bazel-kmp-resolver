plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
}

dependencies {
    implementation(kotlin("gradle-plugin", "2.4.21"))
    implementation("com.android.tools.build:gradle:9.4.1")
}