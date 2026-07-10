plugins {
    java
    `maven-publish`
}

extensions.configure<JavaPluginExtension> {
    withSourcesJar()
}

extensions.configure<PublishingExtension> {
    publications {
        create<MavenPublication>("maven") {
            // Unified BTC Studio Maven coordinates: dev.btc.velocity:api
            // (mirrors dev.btc.core:api so both APIs live in a single repo)
            groupId = "dev.btc.velocity"
            artifactId = "api"

            from(components["java"])
            pom {
                name.set("BTC Velocity API")
                description.set("Custom high-performance Velocity proxy API for the Born To Craft Minecraft network")
                url.set("https://borntocraftstudio.net")
                licenses {
                    license {
                        name.set("GNU General Public License v3.0")
                        url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                    }
                }
                developers {
                    developer {
                        id.set("btc-studio")
                        name.set("BTC Studio")
                        url.set("https://borntocraftstudio.net")
                    }
                }
                scm {
                    url.set("https://github.com/RenaudRl/BTCVelocity")
                    connection.set("scm:git:https://github.com/RenaudRl/BTCVelocity.git")
                }
            }
        }
    }

    repositories {
        // Unified static BTC Studio Maven repo, committed under <root>/repo/ and
        // uploaded as-is to https://borntocraftstudio.net/repo/ .
        // Hosts both dev.btc.core:api and dev.btc.velocity:api side by side.
        maven {
            name = "btcRepo"
            url = uri(rootProject.layout.projectDirectory.dir("repo"))
        }
    }
}