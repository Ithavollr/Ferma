import org.gradle.kotlin.dsl.register
import xyz.jpenilla.runpaper.task.RunServer

plugins {
    id("java-library")
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.14"
    id("xyz.jpenilla.run-paper") version "3.0.2"
    id("com.gradleup.shadow") version "9.4.1"
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    maven("https://repo.codemc.org/repository/maven-public") {
        name = "CodeMC"
    }
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "PaperMC"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    paperweight.paperDevBundle("1.21.4-R0.1-SNAPSHOT")
    implementation("xyz.jpenilla:reflection-remapper:0.1.2")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    runServer {
        minecraftVersion("1.21.4")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    shadowJar {
        archiveClassifier.set("all")
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
    }

    build {
        dependsOn(test)
        dependsOn(shadowJar)
    }

    runServer {
        dependsOn(shadowJar)
    }
}

// Test Paper run & immediately shut down, for github actions
tasks.register<RunServer>("runServerTest") {
    dependsOn(tasks.shadowJar)
    // Accept a Minecraft version via -PmcVersion=1.21.5, default to 1.21.4
    val mcVersion = project.findProperty("mcVersion") as String? ?: "1.21.4"
    minecraftVersion(mcVersion)
    downloadPlugins {
        github("Ifiht", "AutoStop", "v1.2.0", "AutoStop-1.2.0.jar")
    }
    pluginJars.from(tasks.shadowJar)
}
// Start a local test server for login & manual testing
tasks.register<RunServer>("runServerInteractive_1-21-4") {
    dependsOn(tasks.shadowJar)
    minecraftVersion("1.21.4")
    downloadPlugins {
        hangar("Multiverse-Core", "5.6.1")
        hangar("Chunky", "1.4.40")
        modrinth("squaremap", "1.3.4")
        modrinth("simple-fly", "0.0.1")
    }
    pluginJars.from(tasks.shadowJar)
    systemProperty("net.kyori.adventure.text.warnWhenLegacyFormattingDetected", "false")
}