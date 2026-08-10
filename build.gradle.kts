import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "de.jeffclan"
version = providers.gradleProperty("pluginVersion").get()

// Java 25: the latest resolvable paper-api (26.2.build.111-stable, matching
// Minecraft's current calendar-versioned release) publishes Gradle module
// metadata requiring JVM 25 - same constraint the EpicFurnaces sibling hit.
// The foojay-resolver-convention plugin (declared in settings.gradle.kts)
// auto-provisions this JDK into Gradle's own toolchain cache; it does not
// touch the system/Homebrew JDK 21 install. See PLAN.md/CLAUDE.md.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    // WorldGuard / WorldEdit - the plugin's old worldguard-legacy/oss.sonatype.org
    // snapshot coordinates are dead; these are the live current coordinates.
    maven("https://maven.enginehub.org/repo/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${providers.gradleProperty("paperApiVersion").get()}")

    // Optional/soft WorldGuard integration (guarded by Class.forName/
    // getPlugin checks at runtime, off by default via config.yml's
    // use-worldguard: false) - compileOnly only, never shaded.
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.18")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.5")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("InvUnload")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    filesMatching("plugin.yml") {
        expand(props)
    }
}
