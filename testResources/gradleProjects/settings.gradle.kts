dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

include(":kmp-coroutines")
include(":kmp-popular")
include(":kmp-sqliter")