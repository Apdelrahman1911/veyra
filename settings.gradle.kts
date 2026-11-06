pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Veyra"
include(":shared", ":composeApp")

// Source-pinned composite, never MavenLocal or a floating upstream branch.
val upstream = file("third_party/kmpYT")
check(upstream.resolve("settings.gradle.kts").isFile) {
    "Initialize the pinned dependency with git submodule update --init --recursive, then scripts/gradle.sh."
}
includeBuild(upstream) {
    dependencySubstitution {
        substitute(module("io.github.apdelrahman1911.kmpyt:kmpyt"))
            .using(project(":kmpyt"))
    }
}
