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
      Bert's fork has zero unique commits, sitting at upstream's first
      commit. Nothing of Bert's to preserve through the merge.
- [x] Inventoried upstream: 28 commits ahead, last pushed 2020-06-16.
      Upstream's own `README.md` says the project moved to
      `JEFF-Media-GbR/Spigot-InvUnloadPlus` (GPL-3.0, actively maintained,
      last push 2024-10-27). Documented in `CLAUDE.md`; **not** adopted as
      the new upstream target — out of scope for this task, recorded as a
      future option for Bert.
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
| Folia | Untested | Uses `Bukkit.getScheduler().scheduleSyncRepeatingTask` (global scheduler) in `onEnable()` for the update checker — Folia's region-threaded model would need this on a region/global scheduler instead. Not smoke-tested, flagged as an open problem |
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
      original task brief, left for a future pass if Bert wants runtime
      (not just compile-time) confirmation.

## Open problems / honest blockers

- **ChestSort's original build was never actually resolvable.** Upstream's
  `pom.xml` depended on a `system`-scoped `lib/ChestSort.jar` that upstream's
  own `.gitignore` excludes from the repo — the Maven build has never
  compiled from a clean checkout, on any commit, going back to upstream's
  first commit. This wasn't discovered as a regression; it's how the
  project always was. Converted to reflection (see `CLAUDE.md`), removing
  the problem rather than working around it.
- **Folia untested.** The update-checker's repeating task uses
  `Bukkit.getScheduler().scheduleSyncRepeatingTask` (global scheduler) —
  Folia's region-threaded scheduler model may not like a plugin scheduling
  global-scope repeating tasks this way. Not smoke-tested on an actual
  Folia server this session. Low real-world impact since
  `check-for-updates` now defaults to `false` and the endpoint is dead
  anyway, but flagged honestly rather than assumed fine.
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
  `invunload.dump` correctly), flagged in case Bert wants it corrected.
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
- Commits authored as `Bert Shuler <BertShuler@proton.me>`, signed via the
  1Password SSH agent. If signing ever fails with no human at the keyboard,
  the prepared commit message is appended to the session scratchpad's
  `Spigot-InvUnload-commit-msg.txt` instead of being force-committed
  unsigned.
