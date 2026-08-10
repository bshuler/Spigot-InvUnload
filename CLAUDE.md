# CLAUDE.md — Spigot-InvUnload

## What this is

InvUnload is a Bukkit/Spigot/Paper **server plugin** (not a client mod, not a
Fabric/Forge/NeoForge mod). It gives players two commands, `/unload` and
`/dump`, that walk a radius of nearby chests and stash inventory items into
them — `/unload` only tops up chests that already contain a matching item
type, `/dump` empties the inventory (minus hotbar) into any nearby chest
space regardless of contents. See `PLAN.md` for the modernization plan,
milestone status, and the honest platform/version support matrix.

## Provenance (binding)

This repo is a genuine GitHub fork of `JEFF-Media-GbR/Spigot-InvUnload`
(`git remote -v` shows `upstream`). Investigated before touching anything,
per the porting playbook:

- **Bert's fork had zero unique commits.** `git log upstream/master..HEAD`
  was empty — the fork was created at upstream's very first commit
  (`76fdc69 First commit`) and never advanced. There was nothing of Bert's
  to preserve; bringing the fork current is a plain fast-forward merge, not
  a rebase-and-reconcile.
- **Upstream is dead, on purpose.** `JEFF-Media-GbR/Spigot-InvUnload`'s last
  push was 2020-06-16. Its `README.md` says outright: *"This project has
  been moved here: `https://github.com/JEFF-Media-GbR/Spigot-InvUnloadPlus`"*.
  The author (mfnalex/JEFF Media) continued the same plugin concept as a
  new, actively-maintained repo (`InvUnloadPlus`, GPL-3.0, last push
  2024-10-27, now a much larger Maven project with CoreProtect/PlotSquared/
  WorldEdit/bStats/`com.jeff_media.updatechecker` integrations).
- **This task stays scoped to `Spigot-InvUnload`, not `InvUnloadPlus`.**
  The assignment is to modernize *this* forked repo, not to re-point it at a
  different upstream project — that would be a scope change Bert didn't ask
  for. `InvUnloadPlus` is recorded here as a known, license-compatible
  (GPL-3.0) successor worth a look if Bert ever wants the fuller feature set,
  but no code was copied from it and this port does not depend on it.
  JEFF Media's other signature libraries (JeffLib, CustomBlockData,
  MorePersistentDataTypes, SpigotUpdateChecker) are **not used anywhere in
  this specific plugin's history** — checked the full commit range, none of
  those coordinates ever appear here, so there was nothing to vendor or
  replace on that front.
- Merge performed on `main` (renamed from `master`, old default branch left
  in place untouched per repo policy). A pure `--ff-only` merge was tried
  first but correctly refused once `main` carried its own docs commit;
  fell back to a regular merge, which resolved with **zero conflicts**
  (expected, since the fork's own commit never touched a file upstream
  had). This brought in all 28 upstream commits (WorldGuard integration,
  ChestSort integration, config versioning/localization, the 0.1-alpha →
  Maven conversion, and the last release, `2.0.2`).

## Architecture

Single Bukkit `JavaPlugin` (`de.jeffclan.InvUnload.InvUnload`), command +
event driven, package `de.jeffclan.InvUnload`:

- `InvUnload` — plugin entry point and all logic: `onCommand` dispatches
  `/unload` and `/dump`; `unloadInventory`/`dumpInventory` do the actual
  chest-filling; `getNearbyChests` does the radius scan; `canUseChestHere`/
  `getWorldGuard` are the optional WorldGuard region-flag check.
- `ChestSpaceCombination` — small `Comparable` wrapper pairing a chest block
  with its free-slot count, used to sort candidate chests by free space
  before a `/dump`.
- `Messages` — loads the four user-facing message strings from `config.yml`
  with color-code translation and a `PREFIX`.
- `Utils` — one static helper, `isInteger(String)`.
- `UpdateChecker` — polled an external version-check URL and warned ops in
  chat/console if outdated. **Endpoint is dead** (see below).
- `Metrics` — the old pre-3.x bStats "classic" pattern: the entire bStats
  client vendored as one 668-line source file, unshaded, unrelocated,
  importing `org.json.simple.JSONArray/JSONObject` — a dependency the
  original `pom.xml` never actually declared. **Removed** (see below).

### Removed / replaced dependencies

| Old | Why | Replacement |
|---|---|---|
| Vendored classic `Metrics.java` (`org.bstats.bukkit` "relocate-yourself" pattern) + implicit, undeclared `org.json.simple` dependency | 668-line relic predating the current bStats Gradle/Maven artifact; its own `pom.xml` never declared the `org.json.simple` dependency it imports, so the upstream Maven build was already broken on this axis | **Removed outright**, no replacement. The modern `org.bstats:bstats-bukkit` API requires a plugin ID registered on bstats.org; this plugin was never registered under that model (the classic pattern didn't need one), and fabricating an ID with no real dashboard behind it would misattribute telemetry to nothing. A maintainer who wants metrics back should register a real ID at bstats.org and add `org.bstats:bstats-bukkit` + `new Metrics(this, id)` in `onEnable()` — left as a comment at the call site in `InvUnload.java`. |
| `UpdateChecker` polling `https://api.jeff-media.de/invunload/invunload-latest-version.txt` | Domain no longer resolves (`curl` fails outright, not even a 404 — DNS/connection failure) | Update-check code path left in place but the config default `check-for-updates` is now `false` and a startup log line explains why; safe to re-enable if the endpoint ever comes back, doesn't fail loudly since the whole call is already wrapped in try/catch |
| `de.jeffclan.JeffChestSort.JeffChestSortPlugin` compile-time import + a `system`-scoped Maven dependency on `${basedir}/lib/ChestSort.jar` | That jar was never committed (upstream's own `.gitignore` excludes `/lib/`) — the upstream Maven build has **never actually compiled** without a maintainer manually dropping a private copy of `ChestSort.jar` next to the project. Not a coordinate that rotted over time; it was never resolvable from a build machine at all. | Converted to full reflection: `trySort()` looks up the `ChestSort` plugin by name and invokes `sortInventory(Inventory)` via `Method.invoke`, silently doing nothing if the plugin or method isn't present. No compile-time dependency on ChestSort at all anymore — matches the softdepend-style `Class.forName` pattern already used for WorldGuard. |
| `com.sk89q.worldguard:worldguard-legacy:7.0.0-SNAPSHOT` / `com.sk89q.worldedit:worldedit-bukkit:7.0.0-SNAPSHOT` from the dead `oss.sonatype.org` snapshot repo | `worldguard-legacy` 404s outright at EngineHub's repo today (that artifact id doesn't exist post-7.0); the pinned snapshot repo is gone | `com.sk89q.worldguard:worldguard-bukkit:7.0.18` / `com.sk89q.worldedit:worldedit-bukkit:7.4.5`, both live releases from `https://maven.enginehub.org/repo/`, both `compileOnly` (WorldGuard integration is already fully soft/optional at runtime via `Class.forName`/`getPlugin`) |
| `Particle.SPELL_WITCH` / `Particle.SPELL` hardcoded as field initializers | Bukkit renamed these particle constants (~1.20.5 particle cleanup); a hardcoded `Particle.WITCH` reference only compiles against post-rename `paper-api` coordinates and fails outright against 1.18.2/1.19.4/1.20.1 — this broke the backward version walk (milestone 4) before it was fixed | `resolveParticle("WITCH", "SPELL_WITCH")` / `resolveParticle("EFFECT", "SPELL")` — a small helper doing a runtime `Particle.valueOf(String)` lookup with a new-name-then-old-name fallback, same runtime-lookup pattern the `EpicFurnaces` sibling already uses for its config-driven particle type. Found via an actual failing build during the backward walk, not assumed — see PLAN.md milestone 4 |

## Platforms

This is Bukkit-API software. "Platform" here means Bukkit-API server
implementations, not mod loaders — see `PLAN.md` milestone 3 for the full
reasoning (same conclusion as every other plugin in this program: Fabric/
Forge/NeoForge are not applicable, full rewrite territory, not a port).

See `PLAN.md` for the version matrix and per-version build status.

## Build

```bash
./gradlew build          # compiles, shades, produces build/libs/InvUnload-<version>.jar
./gradlew shadowJar       # same, explicit task name
./gradlew build -PpaperApiVersion=1.20.1-R0.1-SNAPSHOT   # override target version
```

- Gradle 9.x, Java 25 toolchain (auto-provisioned via the foojay resolver —
  do not install a system JDK for this). `paper-api:26.2.build.111-stable`'s
  Gradle module metadata requires JVM 25, same constraint the `EpicFurnaces`
  sibling hit — confirmed by an actual green build here too, not assumed.
  Older-version builds (milestone 4) may tolerate an older toolchain since
  their `paper-api` coordinates are plain POMs with no module metadata.
- Single unmodified source tree (`src/main/java/de/jeffclan/InvUnload/`),
  version matrix driven entirely by the `-PpaperApiVersion=` Gradle property
  — no per-version source sets, this plugin has no version-conditional code.
- Repositories needed beyond Maven Central + PaperMC: `maven.enginehub.org`
  (WorldGuard/WorldEdit).

## Porting notes for whoever touches this next

- `InvUnload.freeSlotsInChest`/`getNearbyChests` do a brute-force
  `x/y/z` triple loop over the whole radius cube (`(2r+1)^3` blocks) calling
  `getBlockAt` for every coordinate. This was already true upstream and is
  unrelated to modernization — flagged as a pre-existing performance
  characteristic, not something this pass changed.
- `plugin.yml`'s original `permissions` block has a **typo inherited from
  upstream**: the dump permission node is written `invunload:dump` (colon)
  instead of `invunload.dump` (dot) under the `permissions:` section, while
  the command's own `permission:` field correctly says `invunload.dump`.
  Left as-is / documented rather than silently "fixed", since changing a
  permission node's declared description key is a behavior-neutral
  cosmetic fix, not a functional bug (Bukkit doesn't require every granted
  permission to have a matching `permissions:` entry) — flagged in case Bert
  wants it corrected.
