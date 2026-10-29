# PLAN.md — Spigot-InvUnload modernization

## Goal

Bring this fork current with upstream, get it building and running on the
latest Paper API, then walk backward through older Minecraft versions as far
as practical, and give an honest answer on cross-platform (mod-loader)
support. See `CLAUDE.md` for architecture and fork provenance.

## Milestones

### 1. Docs + fork investigation + branch hygiene — DONE

- [x] Confirmed this is a real GitHub fork of `JEFF-Media-GbR/Spigot-InvUnload`
      (`upstream` remote present and fetched).
- [x] Inventoried divergence: `git log upstream/master..HEAD` is **empty** —
      Human's fork has zero unique commits, sitting at upstream's first
      commit. Nothing of Human's to preserve through the merge.
- [x] Inventoried upstream: 28 commits ahead, last pushed 2020-06-16.
      Upstream's own `README.md` says the project moved to
      `JEFF-Media-GbR/Spigot-InvUnloadPlus` (GPL-3.0, actively maintained,
      last push 2024-10-27). Documented in `CLAUDE.md`; **not** adopted as
      the new upstream target — out of scope for this task, recorded as a
      future option for Human.
- [x] Checked upstream's history for JeffLib/CustomBlockData/
      MorePersistentDataTypes/SpigotUpdateChecker — none appear anywhere in
      this plugin's lineage, so no vendoring/replacement needed on that
      front (unlike some other JEFF Media repos).
- [x] `CLAUDE.md` written.
- [x] `PLAN.md` written (this file).
- [x] Renamed local branch `master` → `main`, pushed, set as GitHub default
      branch. `master` (old default) left intact, not deleted.

### 2. Forward merge + modern build (latest Paper API) — DONE

- [x] `git merge upstream/master` on `main` — by the time this ran, `main`
      already carried the milestone-1 docs commit (this file + `CLAUDE.md`),
      so a pure `--ff-only` merge correctly refused ("Diverging branches
      can't be fast-forwarded"). Used a regular merge instead
      (`git merge upstream/master -m "..."`); it resolved via git's `ort`
      strategy with **zero conflicts**, as expected since the fork's own
      commit only touched files upstream never had. All 28 upstream commits
      landed. Verified no `.github/workflows/*` came in with the merge
      (checked upstream's tree beforehand) — the push restriction on this
      repo's `gh` token doesn't apply here.
- [x] Replaced the Maven layout (`pom.xml`, dead `oss.sonatype.org` /
      `hub.spigotmc.org` snapshot repos) with Gradle 9.x + wrapper +
      `com.gradleup.shadow` + `foojay-resolver-convention`, mirroring the
      `EpicFurnaces` sibling's proven setup. Source tree
      (`src/main/java/de/jeffclan/InvUnload/`, `src/main/resources/`)
      already matched Gradle's default layout — no file moves needed, only
      build tooling changed.
- [x] `paper-api` at the latest resolvable version — queried live from
      `fill.papermc.io/v3/projects/paper` and
      `repo.papermc.io/.../paper-api/maven-metadata.xml`: **26.2**
      (`26.2.build.111-stable`) at time of writing, same as the
      `EpicFurnaces` sibling found. Do not hardcode this without
      re-checking; Minecraft is calendar-versioned now.
- [x] Removed the vendored classic `Metrics.java` (668 lines, undeclared
      `org.json.simple` dependency the old `pom.xml` never actually
      resolved) **outright, with no replacement**. The modern
      `org.bstats:bstats-bukkit` API requires a plugin ID registered on
      bstats.org; this plugin was never registered under that model, and
      fabricating an ID with no real dashboard behind it would misattribute
      telemetry to nothing. (First draft of this work actually added
      `bstats-bukkit` with a placeholder ID — caught and reverted before
      commit; see `CLAUDE.md`'s dependency table for the full reasoning and
      how to re-add it correctly with a real ID.)
- [x] Converted the `ChestSort` integration (`trySort()` in `InvUnload.java`)
      from a hard compile-time import of `de.jeffclan.JeffChestSort.
      JeffChestSortPlugin` + a Maven `system`-scope dependency on a
      never-committed `lib/ChestSort.jar`, to full reflection
      (`Bukkit.getPluginManager().getPlugin("ChestSort")` +
      `Method.invoke`). The old dependency was never actually resolvable
      from a clean checkout — this isn't coordinate rot, the upstream
      Maven build has never compiled standalone.
- [x] Re-pointed WorldGuard/WorldEdit at live coordinates:
      `com.sk89q.worldguard:worldguard-bukkit:7.0.18` / `com.sk89q.worldedit:
      worldedit-bukkit:7.4.5` from `https://maven.enginehub.org/repo/`,
      both `compileOnly` (the integration is already fully soft/optional at
      runtime). The old `worldguard-legacy` artifact 404s outright at
      EngineHub today — confirmed live, not assumed.
- [x] Disabled the dead `UpdateChecker` HTTP endpoint
      (`api.jeff-media.de` no longer resolves — confirmed via `curl`, not a
      404 but a full connection failure) by defaulting
      `check-for-updates: false` in `config.yml` and logging why at
      `onEnable()`. Code path left intact rather than deleted, in case the
      endpoint or a successor ever comes back.
- [x] Fixed the known Bukkit `Particle` enum rename: `SPELL_WITCH` → `WITCH`,
      `SPELL` → `EFFECT` (found via compiler error against `paper-api:26.2`,
      not assumed from memory — same rename `EpicFurnaces` already
      documented for `SPELL_WITCH`).
- [x] Green build (`./gradlew build`), jar contents verified with
      `unzip -l` (see milestone 5). Committed + pushed.

### 3. Cross-platform assessment — DONE

**Conclusion: Fabric/NeoForge/Forge are not applicable, same as every other
plugin in this program.**

This is Bukkit-API software: it runs against a stable, server-implementation
-maintained API (`org.bukkit.*`) that stays source-compatible across MC
versions — that's the entire premise of `plugin.yml`'s `api-version` gate
and the single-jar-many-versions model in milestone 4. Fabric/Forge/NeoForge
mods compile directly against Minecraft's own (Mojang-mapped or obfuscated)
classes via Mixins, with no stable API layer, rebuilt per MC version. Porting
this plugin onto a mod loader would mean rewriting 100% of its block/
inventory/event-handling code from scratch against completely different
internals — a rewrite, not a port. Doing that "partially" to check a box
would produce a mod that silently doesn't work, which is exactly what this
program's instructions say never to do.

| Server implementation | Status | Notes |
|---|---|---|
| Paper | Supported (primary target) | Built and verified against this |
| Purpur | Expected-compatible | Purpur is a Paper fork with a superset API; nothing here uses anything Purpur-incompatible |
| Folia | **Not supported** (`folia-supported` deliberately left unset in `plugin.yml`) | Three concrete blockers found by static analysis, none safely fixable within this pass — see "Folia compatibility" below |
| Spigot | Expected-compatible | No Paper-only API calls used anywhere in this plugin |
| CraftBukkit | Expected-compatible (same reasoning) | Not a realistic deployment target in practice |

### 4. Backward version walk — DONE

Paper publishes `paper-api` for older MC versions using the classic
`X.Y.Z-R0.1-SNAPSHOT` coordinate from `repo.papermc.io/repository/
maven-public/`. Confirmed live via `maven-metadata.xml`:
`1.18.2-R0.1-SNAPSHOT`, `1.19.4-R0.1-SNAPSHOT`, `1.20.1-R0.1-SNAPSHOT`,
`1.21.11-R0.1-SNAPSHOT` all present (`1.21.11` as the "1.21.x"
representative — newest release in that version group per
`fill.papermc.io/v3/projects/paper`).

No per-version source branching exists (single source set, compiles against
whichever `paper-api` is on the classpath), so each older target was tested
via the `paperApiVersion` Gradle property, e.g.:

```bash
./gradlew clean shadowJar -PpaperApiVersion=1.18.2-R0.1-SNAPSHOT
```

**First attempt at this walk actually failed** for 1.20.1/1.19.4/1.18.2:
`private Particle particleType = Particle.WITCH;` (the hardcoded field
initializer from milestone 2's Particle-rename fix) only compiles against
`paper-api` coordinates *after* the ~1.20.5 particle rename — against older
coordinates `WITCH`/`EFFECT` don't exist yet and javac fails with
`cannot find symbol`. This is a real backward-compatibility break, not a
tooling problem, and it's exactly the kind of thing "build against every
version and see what actually happens" is supposed to catch rather than
assume. Fixed by replacing the two hardcoded enum-constant field
initializers with a small `resolveParticle(String...)` helper that does a
runtime `Particle.valueOf(name)` lookup, trying the modern name first and
falling back to the pre-rename name — the same `Particle.valueOf(String)`
runtime-lookup pattern the `EpicFurnaces` sibling already uses for its
config-driven particle type, just with a fixed two-name fallback list
instead of a config value. `Particle.valueOf(String)` is a stable method
signature across every paper-api version tested, so this keeps the source
tree unmodified-across-versions while preserving the exact same runtime
particle choice on every target. See `CLAUDE.md`.

| Target | api-version needed | Status |
|---|---|---|
| 26.2 (latest) | `26.2` | **Built.** Default committed target. Jar: 13 files, 35102 bytes, verified with `unzip -l`. |
| 1.21.11 (1.21.x) | `1.21` | **Built.** `-PpaperApiVersion=1.21.11-R0.1-SNAPSHOT`. Jar: 13 files, 35102 bytes. |
| 1.20.1 | `1.20` | **Built** (after the `resolveParticle` fix above — failed to compile before it). `-PpaperApiVersion=1.20.1-R0.1-SNAPSHOT`. Jar: 13 files, 35102 bytes. |
| 1.19.4 | `1.19` | **Built** (same fix required). `-PpaperApiVersion=1.19.4-R0.1-SNAPSHOT`. Jar: 13 files, 35102 bytes. |
| 1.18.2 | `1.18` | **Built** (same fix required). `-PpaperApiVersion=1.18.2-R0.1-SNAPSHOT`. Jar: 13 files, 35102 bytes. |

All five targets produce byte-identical-sized jars (same 13 entries: 6
`.class` files, `config.yml`, `plugin.yml`, `META-INF/MANIFEST.MF`, 4
directory entries) — expected, since there is genuinely zero
version-conditional code in this plugin once `resolveParticle` is in place.

`plugin.yml`'s shipped `api-version` stays at the latest target; the
override mechanism above is how a maintainer reproduces/re-verifies any
older-version build without separate project files.

### 5. Verification

- [x] `unzip -l build/libs/*.jar` for every version above — confirmed real
      class/resource counts (13 files, 35102 bytes each), not just
      `BUILD SUCCESSFUL`. Re-ran the 26.2 build once more afterward
      (`./gradlew clean build`) to leave the working tree in its
      committed-default state.
- [ ] Optional Paper smoke-boot in the session scratchpad only (never
      committed) — not performed this session; noted as optional in the
      original task brief, left for a future pass if Human wants runtime
      (not just compile-time) confirmation.

### 6. Test coverage (Human's directive: 100%) — DONE

JUnit 5 + MockBukkit + Mockito, `jacoco` Gradle plugin wired as
`./gradlew test jacocoTestReport jacocoTestCoverageVerification` (the latter
enforced at `minimum = 1.00` for both LINE and BRANCH, and `check` depends on
it — a regression fails the build, not just the report). MockBukkit is
pinned to one fixed target: whatever `paper-api` version `build.gradle.kts`
resolves by default (26.2 at time of writing) — the same single-source-tree/
runtime-lookup approach from milestone 4 means the test suite doesn't need
its own version matrix.

- [x] 67 tests across 6 test classes (`InvUnloadTest`, `UtilsTest`,
      `ChestSpaceCombinationTest`, `MessagesTest`, `UpdateCheckerTest`, plus
      two `FakeChestSort*Plugin` fixtures with no `@Test` methods of their
      own). All assert real behavior (return values, message contents,
      inventory/chest state, mock invocation counts) — none merely
      construct-without-throwing.
- [x] **Final coverage: 100% instructions, 100% lines, 100% branches, 100%
      methods, 100% classes** across every class JaCoCo evaluates (6
      classes: `InvUnload`, `Utils`, `ChestSpaceCombination`, `Messages`,
      `UpdateChecker`, `TestableInvUnload`'s production-side siblings are
      test-only and not counted). Verified via
      `./gradlew clean test jacocoTestReport jacocoTestCoverageVerification check`
      — green, not just eyeballed from the HTML report.
- [x] **One exclusion, documented per the brief's "never silently" rule:**
      `WorldGuardHook.class` is excluded from coverage measurement
      (`jacocoExcludes` in `build.gradle.kts`). Reason: its "WorldGuard is
      present on the classpath and its region flag denies access" branch
      needs an actual running WorldGuard platform (`WorldGuardPlugin`,
      region managers, flag registries) to construct — not something a unit
      test can fake without either shading in a huge chunk of WorldGuard's
      own runtime or writing a hand-rolled fake so large it would just be
      testing the fake, not the integration. The class's own Javadoc
      documents this rationale at the source. Its "WorldGuard absent"
      default-return-true path (the only path actually reachable from this
      test classpath, since WorldGuard is `compileOnly`) is exercised
      indirectly via `InvUnload`'s own tests (e.g.
      `onEnable_useWorldguardTrue_hooksAttemptWithoutThrowingSinceWorldGuardAbsent`)
      and via `TestableInvUnload.denyChestAccess`, which stands in for the
      excluded "denied" branch at the `InvUnload` call-site level so
      `getNearbyChests`'s own branch on the boolean *result* is still fully
      covered — only `WorldGuardHook`'s internal WorldGuard-present logic
      itself is excluded.
- [x] **Real bugs found and fixed while writing tests** (all with the
      failing scenario reasoned through first, not guessed):
  1. **Infinite loop in `dumpInventory`'s overflow-handling `while` loop
     (the most severe finding of this pass).** The loop
     (`while (unstorable.size() > 0 && chestInventories.size() > 0)`)
     never removed the chest it had just tried from `chestInventories`
     inside the loop body — only the very first candidate chest was ever
     removed, before the loop started. If that first chest could not fully
     absorb the overflow (a completely full chest, or one that only ever
     partially empties on each attempt), `chestInventories.get(0)` kept
     referring to the exact same chest forever: `unstorable.size()` never
     reached 0 and `chestInventories.size()` never changed, so neither half
     of the `while` condition ever became false. This is a genuine
     server-main-thread hang, reachable by any player running `/dump` near
     two or more full/near-full chests — not a theoretical edge case, a
     realistic one. Found by tracing why JaCoCo reported this line's
     branch coverage as stuck at 1-of-4 no matter what test scenarios were
     added, not by reading the code and guessing. Fixed by adding
     `chestInventories.remove(0);` at the end of the loop body, so the
     candidate list always shrinks by exactly one per iteration regardless
     of outcome.
  2. `Utils.isInteger(String)` used to also catch `NullPointerException`
     alongside `NumberFormatException`. `Integer.parseInt(null)` throws
     `NumberFormatException`, not `NullPointerException`, confirmed with a
     throwaway repro against this project's own Java 25 toolchain — the
     `NullPointerException` catch clause was unreachable dead code, removed
     rather than excluded.
  3. `UpdateChecker.fetchLatestVersionLine()` had no connect/read timeout on
     its `HttpURLConnection` — a slow/unresponsive endpoint could hang
     indefinitely. This matters because `checkForUpdate()` runs either from
     a repeating scheduled task or synchronously inside `onEnable()`
     (`check-for-updates: on-startup`), where an indefinite hang would stall
     server startup entirely. Fixed with a 5-second connect and read
     timeout.
  4. Two dead "is this an AIR-type stack" checks (in `isItemStackEmpty` and
     `freeSlotsInChest`) that ran *after* an `amount == 0` check — Bukkit/
     vanilla normalize any `Material.AIR` `ItemStack` to `getAmount() == 0`
     regardless of the constructor's requested amount, confirmed with a
     throwaway debug assertion (`new ItemStack(Material.AIR, 1).getAmount()
     == 0`), so the AIR-type branch could never actually be reached
     differently from the amount check already ahead of it. Removed as
     confirmed-dead code, not excluded.
  5. `Utils` had an implicit public no-arg constructor despite being a
     pure static-utility class never meant to be instantiated — JaCoCo
     always reported it as an uncovered method with nothing to legitimately
     call it. Given an explicit private constructor that throws
     `UnsupportedOperationException`, which is itself now honestly tested
     via reflection (the only way to reach a private constructor) rather
     than excluded.
- [x] Gotchas hit while building this suite, worth flagging for sibling
      plugins using the same MockBukkit/Mockito stack:
  - `ChestSpaceCombination.compareTo` + `Collections.reverseOrder()` in
    `dumpInventory` means candidate chests are tried **most-free-space-
    first**. Tests that need a specific chest tried first must rig its
    mocked `getContents()`/free-slot count deliberately higher, regardless
    of how "realistic" that free-slot count is for the scenario.
  - Calling `player.getInventory().addItem(...)` **twice** with the same
    material and a combined amount under the max stack size silently
    **merges** both calls into one stack in one slot — a test meaning to
    exercise "two separate items processed one after another" must use
    `setItem(slotIndex, ...)` into two distinct slots instead, or the
    per-item loop under test only ever sees one merged `ItemStack`.
  - Mockito cannot spy (or mock-as-partial) `InvUnload` itself: its
    `worldGuard` field's declared type (`com.sk89q.worldguard.WorldGuard`)
    is `compileOnly` and deliberately absent from the test runtime
    classpath (see the `WorldGuardHook` exclusion above), and Mockito's
    inline mock maker needs to instrument the *entire* class hierarchy,
    including declared field types, to create a spy — byte-buddy fails
    with `NoClassDefFoundError` reaching into that field. Any test that
    needs to verify "was method X called on this specific instance"
    without a spy needs its own tracking seam instead (this suite added an
    `unloadInventoryCallCount` counter and a `denyChestAccess` flag to the
    test-only `TestableInvUnload` subclass for exactly this reason).
  - JaCoCo's branch/line coverage for a `try` block only credits the
    "completed successfully" path if a test actually lets the whole `try`
    block finish without an exception escaping — a test that only ever
    exercises the *catch* path (however many times) will never mark the
    try block's own success-continuation instructions as covered, even
    though some of those instructions technically execute before the
    exception is thrown. `UpdateCheckerTest` needed a dedicated local-
    HTTP-server test that lets `checkForUpdate()` succeed end-to-end, in
    addition to the pre-existing "endpoint unreachable" failure test.

### Folia compatibility — verdict: NOT SUPPORTED

Static analysis pass (no Folia server available to smoke-test against;
conclusions are from reading the code against Folia's documented
regionized-threading model, not from an actual run). `folia-supported` is
**deliberately left unset** in `plugin.yml` rather than set to `true`,
because none of the three blockers below is something this pass could
safely fix:

1. **Legacy global scheduler for the update checker.** `onEnable()` calls
   `Bukkit.getScheduler().scheduleSyncRepeatingTask(this, ..., 0L,
   updateCheckInterval * 20)` — the plain `BukkitScheduler` API with no
   location/entity context. Folia's guidance is to avoid the legacy
   `BukkitScheduler` entirely in favor of
   `Bukkit.getGlobalRegionScheduler()`/`getAsyncScheduler()`/
   `getRegionScheduler()`/`entity.getScheduler()`, since the legacy API
   assumes a single global tick loop that Folia's regionized model doesn't
   have. The task body itself (`updateChecker.checkForUpdate()`) touches no
   per-region state at all — it's pure network I/O plus logging — so this
   specific blocker genuinely is the "safely fixable" kind (swap to
   `getAsyncScheduler().runAtFixedRate(...)`, which also happens to be a
   more correct place to put blocking network I/O on any platform, Folia
   or not). **Not fixed in this pass** to avoid introducing untested async
   scheduling code right after landing a 100%-enforced coverage bar, and
   because it doesn't change the overall verdict below by itself — flagged
   for whoever does take on the other two blockers.
2. **`getNearbyChests`'s radius-cube block scan can cross a region
   boundary.** The scan reads every block in a `(2r+1)^3` cube centered on
   the command-issuing player's own location (`x/y/z` triple loop calling
   `getBlockAt` directly), with `r` configurable up to `max-chest-radius`
   (default 20). A player-issued command dispatches on the region that
   owns the player, but nothing about Folia's region boundaries is aligned
   to a plugin's configured chest-search radius — a player standing within
   `max-chest-radius` blocks of a region edge can have some of those cube
   coordinates fall inside a *different* region than the one the command
   thread owns. Folia's whole threading model exists specifically to make
   that kind of cross-region access unsafe (it's not merely discouraged;
   Folia's own server implementation is built to catch and reject exactly
   this pattern). This is the core, load-bearing behavior of the plugin
   (a radius scan around the player) — making it provably region-safe
   would mean re-dispatching per-chunk/per-region ownership checks rather
   than one flat triple loop, which is a real re-architecture, not a
   one-line fix. **This is the reason `folia-supported` stays unset even
   though blocker 1 above is cheap to fix.**
3. **`unloadInventory`'s `DoubleChest` handling reads both chest halves
   directly** (`doubleChest.getLeftSide()`/`getRightSide()` → each side's
   own `Block`) once a double chest is found. A double chest that happens
   to straddle a region boundary (rare, but by construction two adjacent
   blocks can legitimately fall in different regions right at an edge)
   has the same cross-region-access shape as blocker 2, just for a single
   pair of blocks instead of a whole cube. Same root cause, much smaller
   blast radius — not independently fixed since fixing blocker 2 properly
   would need to account for this case anyway.

No mutable static shared state exists anywhere in this plugin (checked all
three `static` fields: one is `private static final int
currentConfigVersion`, an immutable constant; the rest are pure static
*methods* with no backing static field) — that specific Folia risk category
is a non-issue here, unlike the scheduler/region-access ones above.

## Open problems / honest blockers

- **ChestSort's original build was never actually resolvable.** Upstream's
  `pom.xml` depended on a `system`-scoped `lib/ChestSort.jar` that upstream's
  own `.gitignore` excludes from the repo — the Maven build has never
  compiled from a clean checkout, on any commit, going back to upstream's
  first commit. This wasn't discovered as a regression; it's how the
  project always was. Converted to reflection (see `CLAUDE.md`), removing
  the problem rather than working around it.
- **Folia is not supported** — see the dedicated "Folia compatibility"
  section above (milestone 6) for the full three-blocker analysis.
- **`scheduleSyncRepeatingTask` is itself a deprecated API** (superseded by
  `BukkitScheduler.runTaskTimer`/`BukkitRunnable`) — this is the source of
  the `BUILD SUCCESSFUL` build's "Some input files use or override a
  deprecated API" note on every version target. Confirmed by `grep`, not
  left as an unexplained warning. Not fixed, since it's inside the
  already-dead update-checker code path and touching scheduler semantics
  there for a feature that's off by default wasn't judged worth the risk
  this pass — flagged for whoever re-enables update checking.
- **bStats metrics removed, not replaced.** See `CLAUDE.md`'s dependency
  table — no telemetry at all now, by design, rather than a fabricated
  bstats.org plugin ID with no real dashboard behind it.
- **The `invunload:dump` (colon) permission-node typo is inherited from
  upstream and deliberately left unfixed** — see `CLAUDE.md`'s porting
  notes. Cosmetic (the command's own `permission:` field already says
  `invunload.dump` correctly), flagged in case Human wants it corrected.
- **The update-check endpoint (`api.jeff-media.de`) is permanently dead**
  (`curl` gets a full connection failure, not even a 404) unless a new
  endpoint is ever configured — `check-for-updates` defaults to `false`
  now specifically because of this.

## Repository / git notes

- Default branch `main` (renamed from `master`, GitHub default branch
  updated). `master` (old default) left in place, untouched, not deleted.
  `upstream` remote points at `JEFF-Media-GbR/Spigot-InvUnload` and was
  never pushed to.
- No `.github/workflows/*` came in from the upstream merge — checked the
  upstream tree before merging; the push-scope restriction on this repo's
  `gh` token was not triggered.
- Commits authored as the repo's configured git identity, signed via the
  1Password SSH agent. If signing ever fails with no human at the keyboard,
  the prepared commit message is appended to the session scratchpad's
  `Spigot-InvUnload-commit-msg.txt` instead of being force-committed
  unsigned.

## Coverage in context (measured 2026-08-13)

Read from the JaCoCo XML report, not from whether the gate passes:

- **Analysed surface:** 6 of 7 compiled classes (86%).
- **Line coverage of that surface:** 100.0% (319 lines analysed).
- Classes outside that surface are excluded by the documented exclusion list. They
  are not covered by any test and are not runtime-verified.

A passing `check` means "no regression inside the analysed surface" — it does not
mean the whole codebase is tested to that percentage.

## Tier 2: headless Paper boot test (added 2026-08-13)

MockBukkit fakes the plugin manager and never reads the packaged jar, so the
unit suite cannot tell you whether *this jar* is a plugin a real server will
load. `paperBootTest` does: it drops the built jar into a throwaway server's
`plugins/`, boots a real headless Paper server, drives its console, and shuts
it down.

```bash
./gradlew paperBootTest -PpaperServerJar=/path/to/paper-26.2-111.jar
```

What it asserts, all six of them against the live server:

1. the jar is a loadable plugin at all — `plugin.yml` parses, `main:` names a
   class that is actually in the jar, and this server accepts its
   `api-version`;
2. `onEnable()` completes without throwing (no `Error occurred while enabling`,
   no `Could not load plugin`);
3. every expected command is REGISTERED in the live command map — typing it
   does not come back `Unknown or incomplete command`;
4. none of those commands throws an unhandled exception when invoked;
5. the plugin appears in the server's own `plugins` listing;
6. `onDisable()` runs and the server exits 0 after `stop`.

### Verified, not assumed

Run against Paper `paper-26.2-111.jar` (SHA-256 `3ec81e3ea50cc6090b94aab024491846a202702e8a874308a5d7510f6b3aa012`,
from <https://fill.papermc.io/v3/projects/paper>), which reports
`Implementing API version 26.2.build.111-stable` — an exact match for this
repo's `paperApiVersion`:

```
paperBootTest: InvUnload loaded, enabled, 2 expected command(s) registered (2 from plugin.yml, 0 registered at runtime), and shut down cleanly on a real Paper server.
```

The full console transcript is written to `build/paper-boot/paper-boot-test.log`
on every run, pass or fail, and it was read rather than trusting
`BUILD SUCCESSFUL` — that habit is what caught the harness defects listed
in `minecraft_plugins/PLAN.md`.


### Opt-in, and a skip is not a pass

The task is deliberately **not** wired into `check`, and the ~60 MB server jar
is never committed: without `-PpaperServerJar=` (or `PAPER_SERVER_JAR=`) the
task prints `paperBootTest SKIPPED (this is a skip, not a pass)` and explains
where to get a jar. A fresh offline clone still builds.

### What Tier 2 does *not* cover

- Commands declared under `commands:` in `plugin.yml` are registered by Bukkit
  *from the yml*, before the plugin gets a vote — for those, check 3 proves the
  yml parsed and the server accepted it, not that the plugin wired anything up.
  It earns its keep on commands registered programmatically at runtime.
- Commands are invoked from the **console** with no arguments. A "players only"
  or usage reply is a pass: the assertion is "the server knows this command and
  running it did not throw", not "the command did its job".
- Commands are sent lowercase, the only spelling the server registers.
- No player ever joins. No gameplay behaviour, event handler, GUI or
  persistence path is exercised. That is Tier 3 work and is not attempted.
- One server, one Minecraft version. This says nothing about older API targets.
