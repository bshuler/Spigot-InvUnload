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

- [x] `git merge upstream/master --ff-only` on `main` — clean fast-forward,
      28 commits landed, no conflicts (expected, since the fork had zero
      unique commits to reconcile). Verified no `.github/workflows/*` came
      in with the merge (checked upstream's tree beforehand) — the push
      restriction on this repo's `gh` token doesn't apply here.
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
      resolved) in favor of `org.bstats:bstats-bukkit:3.2.1` as a normal
      shaded+relocated dependency.
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

| Target | api-version needed | Status |
|---|---|---|
| 26.2 (latest) | `26.2` | **Built.** Default target. Jar verified non-empty. |
| 1.21.11 (1.21.x) | `1.21` | **Built.** `-PpaperApiVersion=1.21.11-R0.1-SNAPSHOT`. |
| 1.20.1 | `1.20` | **Built.** `-PpaperApiVersion=1.20.1-R0.1-SNAPSHOT`. |
| 1.19.4 | `1.19` | **Built.** `-PpaperApiVersion=1.19.4-R0.1-SNAPSHOT`. |
| 1.18.2 | `1.18` | **Built.** `-PpaperApiVersion=1.18.2-R0.1-SNAPSHOT`. |

`plugin.yml`'s shipped `api-version` stays at the latest target; the
override mechanism above is how a maintainer reproduces/re-verifies any
older-version build without separate project files.

### 5. Verification

- [ ] `unzip -l build/libs/*.jar` for every version above — confirm
      real class/resource counts, not just `BUILD SUCCESSFUL`.
- [ ] Optional Paper smoke-boot in the session scratchpad only (never
      committed).

## Open problems / honest blockers

- (filled in as the build proceeds)

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
