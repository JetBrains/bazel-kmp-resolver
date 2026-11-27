dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}

include(":kmp-coroutines")
include(":kmp-popular")
include(":kmp-sqliter")