// Injects the BTC Studio static Maven repo as a publishing target into any Gradle build,
// without editing that build's sources.
//
// Why an init script: some forks derive their version from the git working tree (BlueMap's
// gitVersion() appends "-dirty" as soon as a file changes). Adding a publishing repo by
// patching such a build makes it publish "2.7.8-dirty" instead of "2.7.8" — the act of
// wiring it up corrupts the coordinate. BlueMap's api/ is also a pristine upstream
// submodule we do not want to diverge.
//
// Usage (the repo path defaults to <this script's dir>/repo, so no property is needed):
//   gradlew <task> --init-script D:\GitHub\BTCVelocity\btc-publish.init.gradle.kts
//
// Override with -PbtcRepoDir=<path> if the repo ever moves away from this script.
//
// Forks that are ours to edit (packetevents, craft-engine, BetterHud, BetterModel) declare
// the btcRepo repository directly instead — they have no git-derived version to protect.

import org.gradle.api.publish.PublishingExtension

// Resolved from this script's own location: the init script sits next to the repo it feeds.
val scriptDir = gradle.startParameter.allInitScripts
    .firstOrNull { it.name == "btc-publish.init.gradle.kts" }
    ?.parentFile

val btcRepoDir: String = gradle.startParameter.projectProperties["btcRepoDir"]
    ?: scriptDir?.resolve("repo")?.absolutePath
    ?: error("btc-publish.init.gradle.kts: cannot locate the BTC repo; pass -PbtcRepoDir=<path>")

gradle.rootProject {
    allprojects {
        pluginManager.withPlugin("maven-publish") {
            extensions.configure<PublishingExtension>("publishing") {
                repositories {
                    maven {
                        name = "btcRepo"
                        url = uri(btcRepoDir)
                    }
                }
            }
        }
    }
}
