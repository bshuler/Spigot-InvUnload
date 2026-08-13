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

    // MockBukkit moved Maven group from `com.github.seeseemelk` to
    // `org.mockbukkit.mockbukkit` and now publishes a 26.x-targeting
    // artifact. This repo sat on the old `MockBukkit-v1.21:3.133.2` long
    // after five sibling plugin repos had moved, which meant its tests
    // asserted 1.21 API semantics for a plugin whose packaged jar targets
    // up to 26.2 - exactly the version drift the tests exist to catch.
    // Now aligned with the siblings. The move also renamed every test import
    // from `be.seeseemelk.mockbukkit.*` to `org.mockbukkit.mockbukkit.*`
    // (WorldMock and UnimplementedOperationException additionally moved into
    // `.world` / `.exception` subpackages).
    //
    // Unlike the old `com.github.seeseemelk` artifact, this one does NOT bring
    // paper-api transitively - without the explicit dependency below the test
    // sources fail to compile on `import org.bukkit.plugin.java.JavaPlugin`.
    // It is pinned to the exact Paper build MockBukkit captured its bundled
    // registry-data snapshot from, NOT to -PpaperApiVersion: a newer paper-api
    // on the test classpath compiles fine and then fails at test RUNTIME with
    // InternalDataLoadException. So the tests validate one fixed API target
    // (26.1.2) while the packaged jar is built against whatever
    // -PpaperApiVersion asks for - see CLAUDE.md.
    testImplementation("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.1.2:4.115.0")
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

// Tier 2: boots a real headless Paper server with the packaged jar installed.
// Opt-in (needs -PpaperServerJar=...), deliberately not wired into `check`.
// See gradle/paper-boot-test.gradle.kts and PLAN.md.
apply(from = "gradle/paper-boot-test.gradle.kts")
