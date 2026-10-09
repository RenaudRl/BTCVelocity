plugins {
    `java-library`
}

// Velocity plugin that owns the proxy's stance on the client a player runs (Sentinel volet 14):
// it observes the brand and the plugin channels the client declares, tells the honest minimap
// mods what the server allows (Xaero codes, VoxelMap-Updated settings packet), and can refuse the
// clients that declare themselves cheat clients. Pure Velocity API on purpose: SnakeYAML, Gson and
// Adventure all come through it, so the jar carries no runtime dependency of its own.
dependencies {
    compileOnly(project(":velocity-api"))
    annotationProcessor(project(":velocity-api"))

    testImplementation(project(":velocity-api"))
    compileOnly(libs.jspecify)
}

tasks {
    jar {
        archiveBaseName.set("btc-client-policy")
        manifest {
            attributes["Implementation-Title"] = "btc-client-policy"
            attributes["Implementation-Version"] = project.version
        }
    }
}
