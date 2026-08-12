// TEMPORARY: mavenLocal() resolves the st.orm plugin from a local Storm build.
// Remove this pluginManagement block once 1.14.0 is on the Gradle Plugin Portal.
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}

rootProject.name = "storm-imdb-ktor-graalvm"
