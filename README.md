# InvUnload

InvUnload is a Bukkit/Spigot/Paper server plugin that stashes your
inventory into nearby chests with one command:

- **`/unload [radius]`** — tops up chests that already contain a matching
  item type (keeps your chest organization intact).
- **`/dump [radius]`** — empties your inventory (minus hotbar) into any
  nearby chest space, regardless of contents.

Soft-depends on WorldGuard (respects region flags when present) and
ChestSort.

## Provenance

This repo is a genuine GitHub fork of
[JEFF-Media-GbR/Spigot-InvUnload](https://github.com/JEFF-Media-GbR/Spigot-InvUnload).
Upstream is intentionally dead — the author continued the concept as
[InvUnloadPlus](https://github.com/JEFF-Media-GbR/Spigot-InvUnloadPlus)
(GPL-3.0, actively maintained, much larger feature set). This fork stays
scoped to the original plugin: all 28 upstream commits (WorldGuard and
ChestSort integrations, config versioning/localization, release 2.0.2)
were merged in, and modernization continued from there. No code was taken
from InvUnloadPlus; it's noted here as the license-compatible successor if
you want the fuller feature set.

## Modernization work

- Merged the fork fully current with upstream (zero conflicts), default
  branch renamed to `main`.
- Rebuilt on modern Gradle (9.x wrapper, Java toolchains), `api-version`
  raised to current.
- Particle compatibility fix: the effect names used by the chest
  highlight differ on 1.20.1-and-older servers; `resolveParticle` handles
  both eras.
- JUnit unit-test suite added: the chest-scan/fill logic, ChestSort
  integration (including a missing-method fake), messages, update checker,
  and the chest-space comparator.
- Full history and per-milestone status: `PLAN.md`; architecture notes:
  `CLAUDE.md`.

## Supported Paper versions

One codebase, no version branches. Default build targets the newest stable
Paper API (currently **26.2**); the same source compiles cleanly against
older API lines:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./gradlew build                                             # 26.2 (default)
./gradlew build -PpaperApiVersion=1.21.11-R0.1-SNAPSHOT
./gradlew build -PpaperApiVersion=1.20.1-R0.1-SNAPSHOT
./gradlew build -PpaperApiVersion=1.19.4-R0.1-SNAPSHOT
./gradlew build -PpaperApiVersion=1.18.2-R0.1-SNAPSHOT
```

All five targets are verified builds. The jar lands in `build/libs/`.

## Testing

1. **Unit tests** — `./gradlew check` (JUnit 5, see suite under
   `src/test/java`).
2. **Headless Paper boot smoke test** (opt-in — needs a real Paper server
   jar):

   ```sh
   ./gradlew paperBootTest -PpaperServerJar=/path/to/paper-26.2-111.jar
   ```

   Boots a real headless Paper server with the packaged jar in `plugins/`
   and asserts: the jar loads, `onEnable` doesn't throw, `/unload` and
   `/dump` are registered in the live command map and don't throw when
   invoked, the plugin shows in `plugins`, and `onDisable` exits cleanly.
   Without a server jar the task reports
   `SKIPPED (this is a skip, not a pass)`. Transcript:
   `build/paper-boot/paper-boot-test.log`.
