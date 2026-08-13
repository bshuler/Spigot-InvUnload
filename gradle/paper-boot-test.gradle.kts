// helpers-version: 3
//
// Tier 2 "boot smoke test" - start a REAL, headless Paper server with this
// plugin's built jar dropped into plugins/, and assert the things only a real
// server can tell you:
//
//   1. the jar is a loadable plugin at all - plugin.yml parses, `main:` names
//      a class that exists in the jar, api-version is accepted by this server
//   2. onEnable() completes without throwing
//   3. every expected command is actually REGISTERED in the live command map
//      (typing it does not come back "Unknown or incomplete command")
//   4. no expected command throws an unhandled exception when invoked
//   5. the plugin appears in the server's own `plugins` listing
//   6. onDisable() completes and the server shuts down with exit status 0
//
// None of that is reachable from MockBukkit, which fakes the plugin manager
// and never reads the packaged jar. This is the layer that catches shade/
// relocation mistakes, a `main:` left pointing at a renamed class, a command
// registered programmatically at runtime that quietly stopped registering, and
// a NoClassDefFoundError from a dependency that was compileOnly when it should
// have been implementation.
//
// HONEST SCOPE, so nobody reads more into a green run than is there:
//
//   * Commands listed under `commands:` in plugin.yml are registered by Bukkit
//     itself, from the yml, before the plugin gets a vote. For those, check 3
//     is close to tautological - it proves the yml parsed and the server
//     accepted it, not that the plugin wired anything up. The check earns its
//     keep on commands registered PROGRAMMATICALLY at runtime (ACF, Brigadier,
//     manual CommandMap work), which plugin.yml never mentions. List those in
//     the build script:
//
//         extra["paperBootExpectedCommands"] = listOf("wand", "wands")
//
//     and they are asserted alongside the declared ones.
//   * Commands are invoked from the CONSOLE with no arguments. Most will
//     answer "this command is for players only" or print usage - that is a
//     pass here. The assertion is "the server knows this command" and "running
//     it did not throw", not "the command did its job".
//   * Commands are sent in LOWERCASE, because that is the only spelling the
//     server actually registers: Bukkit lowercases a plugin.yml command label,
//     while Paper's Brigadier console dispatch is case-sensitive. So this task
//     cannot tell you whether the capitalised spelling in someone's plugin.yml
//     works - it does not, for anyone, ever. See the GOTCHA at the send loop.
//   * A plugin with a hard `depend:` on something not in plugins/ will not
//     enable at all. Those dependencies have to be supplied - see
//     paperBootExtraPlugins below.
//   * No player ever joins. Nothing gameplay-facing is exercised.
//
// ---------------------------------------------------------------------------
// This task is OPT-IN and is deliberately NOT wired into `check`.
// ---------------------------------------------------------------------------
// It needs a ~60MB Paper server jar, which is not committed anywhere in this
// repo and never should be. Point the task at one explicitly:
//
//   ./gradlew paperBootTest -PpaperServerJar=/path/to/paper-26.2-111.jar
//   PAPER_SERVER_JAR=/path/to/paper-26.2-111.jar ./gradlew paperBootTest
//
// Get the jar from https://fill.papermc.io/v3/projects/paper (the v2 API at
// api.papermc.io is sunset). The build the rest of this repo is compiled
// against is named by the `paperApiVersion` property - keeping the server jar
// on the same build is the point of the exercise.
//
// Without that property the task SKIPS with a message rather than failing, so
// a plain `./gradlew build` on a fresh clone still works offline. A skip is
// not a pass, and the skip message says so.

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.yaml:snakeyaml:2.5") }
}

val paperServerJarPath: Provider<String> =
    providers.gradleProperty("paperServerJar")
        .orElse(providers.environmentVariable("PAPER_SERVER_JAR"))

// Paper 26.2 declares a minimum of Java 25. Overridable for the day that moves.
val paperJavaVersion: Provider<String> =
    providers.gradleProperty("paperBootJavaVersion").orElse("25")

// The server binds a real socket. Use a high, unlikely port rather than 25565
// so a boot test never collides with something the developer is running.
val paperBootPort: Provider<String> =
    providers.gradleProperty("paperBootPort").orElse("25599")

val paperBootTimeoutSeconds: Provider<String> =
    providers.gradleProperty("paperBootTimeoutSeconds").orElse("240")

// shadowJar when the plugin shades dependencies, plain jar when it has none to
// shade. Resolved lazily: this script may be applied before the shadow plugin.
val pluginJarTask = provider {
    tasks.findByName("shadowJar") ?: tasks.getByName("jar")
}

// GOTCHA: resolve this OUTSIDE tasks.register {}. Inside the configuration
// block the receiver is the Task, and Task has its own (near-empty) extension
// container, so `extensions.getByType<JavaToolchainService>()` in there fails
// with "Extension of type 'JavaToolchainService' does not exist" rather than
// falling through to the project.
val paperToolchains: JavaToolchainService = project.extensions.getByType(JavaToolchainService::class.java)

// GOTCHA, and it cost a bogus green run to find: read these LAZILY. A plain
//     val x = project.extra.properties["paperBootExpectedCommands"]
// here is evaluated the moment `apply(from = ...)` runs, so an
//     extra["paperBootExpectedCommands"] = listOf(...)
// written anywhere BELOW the apply line in build.gradle.kts is invisible - the
// task then reports "0 expected commands" and passes without checking
// anything. Wrapping in provider {} defers the read to execution time, so the
// two lines can appear in either order.

// Set from the build script when the plugin registers commands at runtime
// instead of (or as well as) declaring them in plugin.yml.
val extraExpectedCommands: Provider<List<String>> = provider {
    (project.extra.properties["paperBootExpectedCommands"] as? Iterable<*>)
        ?.map { it.toString() }.orEmpty()
}

// A plugin with a hard `depend:` on something else will not enable unless that
// something else is in plugins/ too. Supply those jars at invocation time -
// they are third-party binaries and have no business being committed here:
//
//   ./gradlew paperBootTest -PpaperServerJar=... -PpaperBootExtraPlugins=/path/Vault.jar
//
// Comma-separated for more than one.
val extraPluginJars: Provider<List<String>> =
    providers.gradleProperty("paperBootExtraPlugins")
        .map { spec -> spec.split(",").map { it.trim() }.filter { it.isNotEmpty() } }
        .orElse(emptyList())

tasks.register("paperBootTest") {
    group = "verification"
    description = "Boots a real headless Paper server with this plugin installed (needs -PpaperServerJar)."
    dependsOn(pluginJarTask)

    val serverJar = paperServerJarPath
    val javaVersion = paperJavaVersion
    val port = paperBootPort
    val timeoutSeconds = paperBootTimeoutSeconds
    val toolchains = paperToolchains
    val serverHome = project.layout.buildDirectory.dir("paper-boot").get().asFile
    val projectName = project.name
    val expectedExtra = extraExpectedCommands
    val extraPlugins = extraPluginJars

    onlyIf {
        val present = serverJar.isPresent && File(serverJar.get()).isFile
        if (!present) {
            logger.lifecycle(
                "paperBootTest SKIPPED (this is a skip, not a pass): no Paper server jar.\n" +
                    "  Provide one with -PpaperServerJar=/path/to/paper-<mc>-<build>.jar\n" +
                    "  or the PAPER_SERVER_JAR environment variable. Downloads:\n" +
                    "  https://fill.papermc.io/v3/projects/paper"
            )
        }
        present
    }

    doLast {
        val paperJar = File(serverJar.get())
        val builtJar = (pluginJarTask.get() as org.gradle.jvm.tasks.Jar).archiveFile.get().asFile
        require(builtJar.isFile) { "plugin jar was not built: $builtJar" }

        // --- read the PACKAGED plugin.yml, not the source one -------------
        // The packaged copy is what the server will read: ${version} expanded
        // by processResources, and relocations (if any) already applied.
        val manifest = java.util.zip.ZipFile(builtJar).use { zip ->
            val entry = zip.getEntry("plugin.yml")
                ?: zip.getEntry("paper-plugin.yml")
                ?: error("no plugin.yml inside $builtJar - this jar is not a Bukkit plugin")
            zip.getInputStream(entry).use { input ->
                @Suppress("UNCHECKED_CAST")
                org.yaml.snakeyaml.Yaml().load<Map<String, Any?>>(input) as Map<String, Any?>
            }
        }
        val pluginName = manifest["name"]?.toString()
            ?: error("packaged plugin.yml has no `name:`")
        val mainClass = manifest["main"]?.toString()
            ?: error("packaged plugin.yml has no `main:`")
        @Suppress("UNCHECKED_CAST")
        val declaredCommands = (manifest["commands"] as? Map<String, Any?>)?.keys
            ?.map { it.toString() }.orEmpty()
        val expectedCommands = (declaredCommands + expectedExtra.get()).distinct()

        // `main:` must name a class that survived shading/relocation. Checking
        // this here turns an obscure "Could not load plugin" into a clear
        // message, and it costs nothing.
        java.util.zip.ZipFile(builtJar).use { zip ->
            val path = mainClass.replace('.', '/') + ".class"
            requireNotNull(zip.getEntry(path)) {
                "plugin.yml `main: $mainClass` but $path is not in $builtJar " +
                    "(renamed class, or a relocation pattern that caught the plugin's own package)"
            }
        }

        // --- prepare the server home --------------------------------------
        // world/, libraries/, cache/ and versions/ are kept between runs: the
        // first run pays for Paper's vanilla-jar patch step and terrain gen,
        // later runs boot in a few seconds. plugins/ is always wiped so the
        // plugin also exercises its own first-run config generation.
        serverHome.mkdirs()
        val pluginsDir = File(serverHome, "plugins")
        pluginsDir.deleteRecursively()
        pluginsDir.mkdirs()
        builtJar.copyTo(File(pluginsDir, builtJar.name))
        extraPlugins.get().forEach { path ->
            val dep = File(path)
            require(dep.isFile) { "paperBootExtraPlugins names a jar that does not exist: $dep" }
            dep.copyTo(File(pluginsDir, dep.name))
        }

        File(serverHome, "eula.txt").writeText("eula=true\n")
        File(serverHome, "server.properties").writeText(
            """
            # Generated by paperBootTest - a disposable server, not a real one.
            online-mode=false
            server-port=${port.get()}
            level-type=minecraft\:flat
            level-name=world
            spawn-protection=0
            max-players=1
            view-distance=3
            simulation-distance=3
            sync-chunk-writes=false
            enable-jmx-monitoring=false
            enable-status=false
            motd=paperBootTest $projectName
            """.trimIndent() + "\n"
        )

        val launcher = toolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(javaVersion.get().toInt()))
        }.get()

        val logFile = File(serverHome, "paper-boot-test.log")
        val process = ProcessBuilder(
            launcher.executablePath.asFile.absolutePath,
            "-Xms512M", "-Xmx1G", "-XX:+UseG1GC",
            "-Dcom.mojang.eula.agree=true",
            "-jar", paperJar.absolutePath,
            "--nogui"
        ).directory(serverHome).redirectErrorStream(true).start()

        // GOTCHA: the Paper console is ANSI-coloured even when redirected to a
        // pipe. Every one of these lines arrives wrapped in escape sequences,
        // so string matching has to happen on stripped text or it silently
        // never matches - which is a vacuous pass, the worst kind.
        // Anchored on the ESC byte, written as \u001B so it stays visible in a
        // diff. A pattern of just \[[0-9;]*[a-zA-Z] would also eat the "[H" out of
        // Brigadier's "<--[HERE]" marker - the exact text the command check reads.
        val ansi = Regex("\u001B\\[[0-9;]*[a-zA-Z]")
        val console = java.util.Collections.synchronizedList(mutableListOf<String>())
        val booted = java.util.concurrent.CountDownLatch(1)
        val reader = Thread {
            process.inputStream.bufferedReader().forEachLine { raw ->
                val line = ansi.replace(raw, "")
                console.add(line)
                // Paper prints this once the server is accepting commands.
                if (line.contains("""Done (""") && line.contains("For help, type")) booted.countDown()
            }
        }
        reader.isDaemon = true
        reader.start()

        fun snapshot() = synchronized(console) { console.toList() }
        fun dump(): String = snapshot().takeLast(60).joinToString("\n").prependIndent("    ")
        fun writeLog() = logFile.writeText(snapshot().joinToString("\n") + "\n")

        val timeout = timeoutSeconds.get().toLong()
        val stdin = process.outputStream.bufferedWriter()
        fun send(command: String) {
            stdin.write(command); stdin.write("\n"); stdin.flush()
        }

        val failures = mutableListOf<String>()
        try {
            if (!booted.await(timeout, java.util.concurrent.TimeUnit.SECONDS)) {
                writeLog()
                val why = if (process.isAlive) "did not finish booting within ${timeout}s"
                else "died during boot (exit ${process.exitValue()})"
                throw GradleException("paperBootTest: the server $why.\n  log: $logFile\n${dump()}")
            }

            val bootLog = snapshot()

            // 1/2. loaded and enabled
            if (bootLog.none { it.contains("Enabling $pluginName") }) {
                failures += "the server never logged \"Enabling $pluginName\" - the plugin did not enable"
            }
            bootLog.filter {
                it.contains("Could not load 'plugins/") ||
                    it.contains("Error occurred while enabling") ||
                    it.contains("Could not load plugin '")
            }.forEach { failures += "load/enable error on the console: ${it.trim()}" }

            // 3/4. every expected command is registered, and survives running
            val marker = "paperboottest-no-such-command"
            val commandPhaseStart = console.size
            send(marker)
            // GOTCHA: send the LOWERCASE label, and mean it. Bukkit registers a
            // plugin.yml command under `label.toLowerCase()`, but Paper's
            // Brigadier console dispatch is case-SENSITIVE - so a plugin.yml
            // that declares `EpicFurnaces:` produces a command the console can
            // only run as `epicfurnaces`, and sending the declared spelling
            // comes back "Unknown or incomplete command". Verified directly on
            // Paper 26.2-111: `epicfurnaces` and the alias `ef` both printed the
            // plugin's help, `EpicFurnaces` was rejected. Comparing the declared
            // spelling would therefore report a false "never registered" for
            // every plugin whose plugin.yml uses capitals.
            expectedCommands.forEach { send(it.lowercase()) }
            send("plugins")
            // The console is asynchronous; give the server a moment to answer
            // all of it before reading the transcript back.
            Thread.sleep(5000)
            val commandLog = snapshot().drop(commandPhaseStart)

            // GOTCHA: on this Paper, an unrecognised command does NOT produce
            // one "Unknown command: x" line. It produces two: a message line
            // ("Unknown or incomplete command. See below for error") and then
            // a bare echo of the offending input with a caret marker
            //     paperboottest-no-such-command<--[HERE]
            // Matching a single line for both the phrase and the command name
            // can never succeed, and the check passes for every plugin
            // forever. Parse the pair instead.
            val rejected = mutableSetOf<String>()
            commandLog.forEachIndexed { i, line ->
                if (!line.contains("Unknown or incomplete command") && !line.contains("Unknown command")) return@forEachIndexed
                val echo = (0..2).mapNotNull { commandLog.getOrNull(i + it) }
                    .firstOrNull { it.contains("<--[HERE]") } ?: return@forEachIndexed
                rejected += echo.substringBefore("<--[HERE]")
                    .substringAfterLast("]: ")
                    .trim()
                    .lowercase()
            }

            // Self-test of the detector above, using a command that certainly
            // does not exist. If this fails, every command assertion in this
            // run is meaningless, so say that rather than reporting a pass.
            if (marker !in rejected) {
                failures += "the unknown-command detector is broken: it did not flag the deliberately " +
                    "bogus \"$marker\", so no command assertion in this run means anything. " +
                    "Paper's console wording probably changed - see the log and update the parser"
            } else {
                expectedCommands.forEach { command ->
                    if (command.lowercase() in rejected) {
                        failures += "command \"$command\" is expected but the live server does not " +
                            "know it - never registered"
                    }
                }
            }
            commandLog.filter { it.contains("Unhandled exception executing command") }
                .forEach { failures += "an expected command threw: ${it.trim()}" }

            // 5. the server's own listing, read from the `plugins` output
            // rather than from anywhere else in the log that happens to
            // mention the name.
            val listingStart = commandLog.indexOfFirst { it.contains("Server Plugins") }
            if (listingStart < 0) {
                failures += "the `plugins` command produced no \"Server Plugins\" listing"
            } else if (commandLog.drop(listingStart).take(40).none { it.contains(pluginName) }) {
                failures += "\"plugins\" did not list $pluginName"
            }

            // 6. clean shutdown
            send("stop")
            if (!process.waitFor(timeout, java.util.concurrent.TimeUnit.SECONDS)) {
                failures += "the server did not shut down within ${timeout}s of `stop`"
            } else if (process.exitValue() != 0) {
                failures += "the server exited ${process.exitValue()} after `stop`, expected 0"
            }
            val fullLog = snapshot()
            if (fullLog.none { it.contains("Disabling $pluginName") }) {
                failures += "the server never logged \"Disabling $pluginName\" - onDisable() did not run"
            }
        } finally {
            runCatching { stdin.close() }
            if (process.isAlive) process.destroyForcibly()
            process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            reader.join(5000)
            writeLog()
        }

        if (failures.isNotEmpty()) {
            throw GradleException(
                "paperBootTest: $pluginName failed ${failures.size} check(s) on a real Paper server.\n" +
                    failures.joinToString("\n") { "  - $it" } +
                    "\n  full console: $logFile"
            )
        }

        logger.lifecycle(
            "paperBootTest: $pluginName loaded, enabled, " +
                "${expectedCommands.size} expected command(s) registered " +
                "(${declaredCommands.size} from plugin.yml, ${expectedExtra.get().size} registered at runtime), " +
                "and shut down cleanly on a real Paper server."
        )
    }
}
