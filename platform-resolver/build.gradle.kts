plugins {
    `java-library`
}

// Velocity plugin that answers a question the proxy core cannot ask. The bridge lives in the core,
// Floodgate lives in a plugin, and a plugin's classes are loaded by a child classloader the core
// cannot see — measured on the bench, 17/09. A plugin sees both sides, so the resolution belongs
// here and the core only reads what this installs.
dependencies {
    compileOnly(project(":velocity-api"))
    annotationProcessor(project(":velocity-api"))

    // The whole reason this module exists: here, compiling against the real Floodgate type works,
    // because a Velocity plugin can legitimately depend on another plugin.
    compileOnly(libs.floodgate.api) { isTransitive = false }

    testImplementation(project(":velocity-api"))
    compileOnly(libs.jspecify)
}

tasks {
    jar {
        archiveBaseName.set("btc-platform-resolver")
        manifest {
            attributes["Implementation-Title"] = "btc-platform-resolver"
            attributes["Implementation-Version"] = project.version
        }
    }
}
