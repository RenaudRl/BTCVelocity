plugins {
    `java-library`
}

// Velocity plugin that takes the resource packs off a player entering a backend that does not
// run CraftEngine. The CE pack reassigns vanilla block states (cut copper, oak leaves, saplings...)
// to custom blocks; only a CE backend remaps the real ones, so anywhere else they render wrong.
dependencies {
    compileOnly(project(":velocity-api"))
    annotationProcessor(project(":velocity-api"))

    testImplementation(project(":velocity-api"))
    compileOnly(libs.jspecify)
}

tasks {
    jar {
        archiveBaseName.set("btc-pack-policy")
        manifest {
            attributes["Implementation-Title"] = "btc-pack-policy"
            attributes["Implementation-Version"] = project.version
        }
    }
}
