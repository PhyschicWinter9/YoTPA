plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "8.3.0"
    id("xyz.jpenilla.run-paper") version "2.3.1"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

object VersionConfig {
    const val PLUGIN_VERSION = "1.6.0"
}

group = "com.relaxlikes"
version = VersionConfig.PLUGIN_VERSION

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
    maven("https://oss.sonatype.org/content/groups/public/") {
        name = "sonatype"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("net.kyori:adventure-api:4.20.0")
    implementation("net.kyori:adventure-text-minimessage:4.20.0")
    implementation("org.bstats:bstats-bukkit:3.0.2")
}

tasks {
    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("1.21")
    }
    shadowJar {
        // This is the crucial part that was missing - proper relocation configuration
        relocate("org.bstats", "com.relaxlikes.yotpa.lib.bstats")

        // Set the archiveClassifier to empty to make this the default artifact
        archiveClassifier.set("")
    }
}

val targetJavaVersion = 21
kotlin {
    jvmToolchain(25)
    compilerOptions {
        // Emit Java 21 bytecode so the plugin runs on both 1.21.x (Java 21) and 26.1.x (Java 25) servers
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    // Keep Java compilation output at Java 21 for the same cross-version reason
    options.release.set(targetJavaVersion)
}

tasks.build {
    dependsOn("shadowJar")
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching(listOf("plugin.yml", "messages.yml")) {
        expand(props)
    }
}
