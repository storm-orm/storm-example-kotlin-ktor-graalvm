pluginManagement {
    repositories {
        // Storm 1.16.0 is resolved from the local Maven repository until it is on the plugin portal.
        mavenLocal()
        gradlePluginPortal()
    }
}

rootProject.name = "storm-imdb-ktor-graalvm"
