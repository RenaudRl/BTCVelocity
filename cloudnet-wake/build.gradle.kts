plugins {
    `java-library`
}

// Velocity plugin that wakes a stopped CloudNet task when a player asks for it (task 2.2). Kept out
// of btc-cloudnet-registry on purpose: the registry never moves a player (AD-047), this one does.
// Everything from CloudNet is provided at runtime by the wrapper, hence compileOnly; the tests mock
// the driver interfaces and so need the same artifacts on their own classpath.
val cloudnetVersion = "4.0.0-RC17"

dependencies {
    compileOnly(project(":velocity-api"))
    annotationProcessor(project(":velocity-api"))

    compileOnly("eu.cloudnetservice.cloudnet:driver-api:$cloudnetVersion")
    compileOnly("eu.cloudnetservice.cloudnet:wrapper-jvm-api:$cloudnetVersion")
    compileOnly(libs.jspecify)

    testImplementation(project(":velocity-api"))
    testImplementation("eu.cloudnetservice.cloudnet:driver-api:$cloudnetVersion")
    testImplementation(libs.mockito)
}

tasks {
    jar {
        archiveBaseName.set("btc-cloudnet-wake")
        manifest {
            attributes["Implementation-Title"] = "btc-cloudnet-wake"
            attributes["Implementation-Version"] = project.version
            attributes["CloudNet-Version"] = cloudnetVersion
        }
    }
}
