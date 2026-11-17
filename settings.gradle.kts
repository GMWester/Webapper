pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.mozilla.org/maven2/")
    }
}
rootProject.name = "WebApper"
// :template = the GeckoView (Firefox engine) app shell every generated app is made from.
// :app      = the WebApper installer that turns the template into a new, signed APK on the device.
include(":template", ":app")
