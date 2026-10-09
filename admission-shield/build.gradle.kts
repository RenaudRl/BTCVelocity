plugins {
    `java-library`
}

// Velocity plugin that refuses a connection whose address is classed datacenter or Tor, judged
// against lists held on this proxy. No player address is ever sent anywhere: the only outbound
// traffic is the periodic download of the public lists themselves.
dependencies {
    compileOnly(project(":velocity-api"))
    annotationProcessor(project(":velocity-api"))

    // Optional at runtime (declared as an optional plugin dependency): it is only asked for the
    // real address of a Bedrock player. Same arrangement as platform-resolver.
    compileOnly(libs.floodgate.api) { isTransitive = false }

    testImplementation(project(":velocity-api"))
    compileOnly(libs.jspecify)
}

tasks {
    jar {
        archiveBaseName.set("btc-admission-shield")
        manifest {
            attributes["Implementation-Title"] = "btc-admission-shield"
            attributes["Implementation-Version"] = project.version
        }
    }
}
