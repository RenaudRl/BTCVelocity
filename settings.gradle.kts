@file:Suppress("UnstableApiUsage")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        // API Floodgate, en compileOnly seulement : rien n'en est embarque dans le jar.
        // Restreint a ce groupe pour qu'un incident sur ce depot ne puisse pas fournir
        // une dependance du coeur du proxy.
        maven("https://repo.opencollab.dev/main/") {
            content {
                includeGroup("org.geysermc.floodgate")
            }
        }
    }
}

pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "btc-velocity"

sequenceOf(
    "api",
    "native",
    "proxy",
    "cloudnet-registry",
    "arrival-router",
    "platform-resolver",
).forEach {
    val project = ":velocity-$it"
    include(project)
    project(project).projectDir = file(it)
}

