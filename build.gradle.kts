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
    maven("https://repo.aikar.co/content/groups/aikar/") {
        name = "aikar"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    paperweight.paperDevBundle("1.21.4-R0.1-SNAPSHOT")
    implementation("xyz.jpenilla:reflection-remapper:0.1.2")
    implementation("co.aikar:acf-paper:0.5.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

// Single source of truth for which default packs ship: the resources directory itself.
// PackLoader reads this index to extract them, so adding or removing a pack directory
// needs no code change and cannot silently drop a shipped pack.
val packsDir = layout.projectDirectory.dir("src/main/resources/packs")
val generatePackIndex by tasks.registering {
    val outputFile = layout.buildDirectory.file("generated/packs/packs/index.txt")
    inputs.dir(packsDir).withPropertyName("packs")
    outputs.file(outputFile).withPropertyName("index")
    doLast {
        val ids = packsDir.asFile.listFiles { f: File -> f.isDirectory && File(f, "pack.yml").isFile }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
        if (ids.isEmpty()) {
            throw GradleException("No packs found in $packsDir - every build must ship at least one default pack")
        }
        outputFile.get().asFile.apply { parentFile.mkdirs() }.writeText(ids.joinToString("\n", postfix = "\n"))
        logger.lifecycle("Ferma pack index: ${ids.size} pack(s) - ${ids.joinToString(", ")}")
    }
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated/packs"))
}

tasks {
    runServer {
        minecraftVersion("1.21.4")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        dependsOn(generatePackIndex)
        val props = mapOf("version" to version)
        // expand() properties aren't tracked as inputs; without this a version bump leaves stale output
        inputs.property("version", version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    shadowJar {
        archiveClassifier.set("all")
        relocate("co.aikar.commands", "org.evlis.firma.acf")
        relocate("co.aikar.locales", "org.evlis.firma.locales")
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
    val eulaFile = layout.projectDirectory.file("run/eula.txt").asFile
    doFirst {
        eulaFile.apply { parentFile.mkdirs() }.writeText("eula=true\n")
    }
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
        modrinth("terra", "6.6.1-BETA-bukkit")
    }
    pluginJars.from(tasks.shadowJar)
    systemProperty("net.kyori.adventure.text.warnWhenLegacyFormattingDetected", "false")
}