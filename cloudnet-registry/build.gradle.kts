plugins {
    `java-library`
}

// Velocity plugin loaded only when the proxy runs as a CloudNet service: it registers and forgets
// backend services (AD-047). Everything from CloudNet is provided at runtime by the wrapper, hence
// compileOnly. No shadow: the jar has no runtime dependency of its own.
val cloudnetVersion = "4.0.0-RC17"

dependencies {
    compileOnly(project(":velocity-api"))
    annotationProcessor(project(":velocity-api"))

    compileOnly("eu.cloudnetservice.cloudnet:driver-api:$cloudnetVersion")
    compileOnly("eu.cloudnetservice.cloudnet:wrapper-jvm-api:$cloudnetVersion")
    compileOnly(libs.jspecify)
}

tasks {
    jar {
        archiveBaseName.set("btc-cloudnet-registry")
        manifest {
            attributes["Implementation-Title"] = "btc-cloudnet-registry"
            attributes["Implementation-Version"] = project.version
            attributes["CloudNet-Version"] = cloudnetVersion
        }
    }
}
