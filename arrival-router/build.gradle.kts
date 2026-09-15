plugins {
    `java-library`
}

// Velocity plugin that answers the two moments no business channel can: a player who has just
// logged in, and a player their backend just dropped. Pure Velocity API on purpose — it knows
// nothing about CloudNet, so it works against any registered server, dynamic or configured.
dependencies {
    compileOnly(project(":velocity-api"))
    annotationProcessor(project(":velocity-api"))

    testImplementation(project(":velocity-api"))
    compileOnly(libs.jspecify)
}

tasks {
    jar {
        archiveBaseName.set("btc-arrival-router")
        manifest {
            attributes["Implementation-Title"] = "btc-arrival-router"
            attributes["Implementation-Version"] = project.version
        }
    }
}
