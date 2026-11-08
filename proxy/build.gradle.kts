import com.github.jengelman.gradle.plugins.shadow.transformers.Log4j2PluginsCacheFileTransformer

plugins {
    application
    id("btcvelocity-publish")
    id("velocity-init-manifest")
    alias(libs.plugins.shadow)
}

application {
    mainClass.set("com.velocitypowered.proxy.Velocity")
    applicationDefaultJvmArgs += listOf("-Dvelocity.packet-decode-logging=true")
}

tasks {
    jar {
        manifest {
            attributes["Implementation-Title"] = "Velocity"
            attributes["Implementation-Vendor"] = "Velocity Contributors"
            attributes["Multi-Release"] = "true"
        }
    }

    shadowJar {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        mergeServiceFiles()

        filesMatching("META-INF/org/apache/logging/log4j/core/config/plugins/**") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }

        transform(Log4j2PluginsCacheFileTransformer::class.java)

        // No fastutil exclusions: 8.5.19 needs ObjectArraySet for Object2IntLinkedOpenCustomHashMap.keySet()
        // (AvailableCommandsPacket.encode), same as upstream #1860. Excluding it broke tab completion.

        // Exclude Checker Framework annotations
        exclude("org/checkerframework/checker/**")

        relocate("org.bstats", "com.velocitypowered.proxy.bstats")

    }

    runShadow {
        workingDir = file("run").also(File::mkdirs)
        standardInput = System.`in`
        jvmArgs("-Dvelocity.packet-decode-logging=true")
    }
    named<JavaExec>("run") {
        workingDir = file("run").also(File::mkdirs)
        standardInput = System.`in` // Doesn't work?
    }

    // Tests against a real Redis (Docker) run only with -PredisIntegration.
    test {
        useJUnitPlatform {
            if (!project.hasProperty("redisIntegration")) {
                excludeTags("redis-integration")
            }
        }
        // -PredisImage=valkey/valkey:8.1.1-alpine: the image the stop test starts again.
        project.findProperty("redisImage")?.let { systemProperty("btc.redis.image", it) }
        // -PredisContainer=name -PredisPort=56380: where the tests find (and restart) the server.
        project.findProperty("redisContainer")?.let { systemProperty("btc.redis.container", it) }
        project.findProperty("redisPort")?.let { systemProperty("btc.redis.port", it) }
    }

    withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(
            listOf(
                "-Alog4j.graalvm.groupId=${project.group}",
                "-Alog4j.graalvm.artifactId=${project.name}"
            )
        )
    }
}

dependencies {
    implementation(project(":velocity-api"))
    implementation(project(":velocity-native"))

    implementation(libs.bundles.log4j)
    implementation(libs.kyori.ansi)
    implementation(libs.netty.codec)
    implementation(libs.netty.codec.haproxy)
    implementation(libs.netty.codec.http)
    implementation(libs.netty.handler)
    implementation(libs.netty.transport.native.epoll)
    implementation(variantOf(libs.netty.transport.native.epoll) { classifier("linux-x86_64") })
    implementation(variantOf(libs.netty.transport.native.epoll) { classifier("linux-aarch_64") })
    implementation(libs.netty.transport.native.iouring)
    implementation(variantOf(libs.netty.transport.native.iouring) { classifier("linux-x86_64") })
    implementation(variantOf(libs.netty.transport.native.iouring) { classifier("linux-aarch_64") })
    implementation(libs.netty.transport.native.kqueue)
    implementation(variantOf(libs.netty.transport.native.kqueue) { classifier("osx-x86_64") })
    implementation(variantOf(libs.netty.transport.native.kqueue) { classifier("osx-aarch_64") })

    implementation(libs.lettuce.core)
    implementation(libs.jopt)
    implementation(libs.terminalconsoleappender)
    implementation(libs.jline.terminal)
    implementation(libs.jline.reader)
    runtimeOnly(libs.jline.terminal.jni)
    runtimeOnly(libs.jline.terminal.ffm)
    runtimeOnly(libs.disruptor)
    implementation(libs.fastutil)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(platform(libs.adventure.bom))
    implementation(libs.adventure.text.serializer.json.legacy.impl)
    implementation(libs.completablefutures)
    implementation(libs.nightconfig)
    implementation(libs.bstats)
    implementation(libs.lmbda)
    implementation(libs.asm)
    implementation(libs.bundles.flare)
    compileOnly(libs.spotbugs.annotations)
    compileOnly(libs.auto.service.annotations)
    // Floodgate resout la plateforme d'une session. compileOnly : le proxy fonctionne sans,
    // et le dit au demarrage plutot que de deviner. Sans transitives : un seul jar tiers sur
    // le classpath de compilation, et rien de Geyser n'entre dans le build.
    compileOnly(libs.floodgate.api) { isTransitive = false }
    testImplementation(libs.mockito)

    annotationProcessor(libs.auto.service)
    annotationProcessor(libs.log4j.core)
}
