pluginManagement {
    repositories { mavenCentral(); google(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral(); google() }
}
rootProject.name = "Veyra"
include(":shared", ":composeApp", ":nativeComponents")

when (file("gradle/kmpyt.mode").readText().trim()) {
    "source" -> {
        val source = file("third_party/kmpYT")
        check(source.resolve("source-tree.json").isFile && source.resolve("kmpyt/build.gradle.kts").isFile) {
            "The checked-in kmpYT snapshot is missing."
        }
        includeBuild(source) {
            dependencySubstitution {
                substitute(module("io.github.apdelrahman1911.kmpyt:kmpyt")).using(project(":kmpyt"))
            }
        }
    }
    "maven" -> Unit
    else -> error("gradle/kmpyt.mode must be source or maven")
}
