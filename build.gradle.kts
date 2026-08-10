import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    java
    jacoco
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
    // Deliberately NOT added to any test-* configuration: the WorldGuard
    // "classes entirely absent" branch (the realistic default, softdepend-
    // not-installed deployment) needs WorldGuard off the test classpath to
    // be exercised at all. See WorldGuardHook.java / PLAN.md.

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // MockBukkit-v1.21 (3.133.2) is the newest MockBukkit artifact
    // published (checked live against Maven Central, not assumed) - it
    // targets paper-api 1.21.1-R0.1-SNAPSHOT, which it also pulls in
    // transitively as a compile-scope dependency, so it's enough on its
    // own to compile and run the test source set against real Bukkit API
    // types. Tests run against this one fixed target regardless of the
    // -PpaperApiVersion property used for the packaged jar - see CLAUDE.md.
    testImplementation("com.github.seeseemelk:MockBukkit-v1.21:3.133.2")
    // Used only for the DoubleChest branches in dumpInventory/
    // unloadInventory: org.bukkit.block.DoubleChest has no MockBukkit
    // equivalent (MockBukkit doesn't merge adjacent chests), so those
    // branches are exercised with a real DoubleChest mocked via Mockito
    // instead, wrapping two genuine MockBukkit chests as its two sides.
    testImplementation("org.mockito:mockito-core:5.18.0")
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

// JaCoCo 0.8.13 is the newest published version (checked live against
// Maven Central) - the default Gradle-bundled version is older and does
// not fully understand Java 25 class files.
jacoco {
    toolVersion = "0.8.13"
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// WorldGuardHook is excluded here, not silently: it exists specifically to
// isolate the live WorldGuard region-query calls (RegionContainer /
// ProtectionQuery), which need a running WorldGuard platform that cannot be
// built in a unit test. See WorldGuardHook.java and PLAN.md for the full
// reasoning.
val jacocoExcludes = listOf("de/jeffclan/InvUnload/WorldGuardHook.class")

tasks.jacocoTestReport {
    classDirectories.setFrom(
        files(classDirectories.files.map { fileTree(it) { exclude(jacocoExcludes) } })
    )
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    classDirectories.setFrom(
        files(classDirectories.files.map { fileTree(it) { exclude(jacocoExcludes) } })
    )
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "1.00".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "1.00".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
