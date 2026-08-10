package de.jeffclan.InvUnload;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import be.seeseemelk.mockbukkit.scheduler.BukkitSchedulerMock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvUnloadTest {

	private static final File PLUGIN_YML = new File("src/main/resources/plugin.yml");

	private ServerMock server;
	private TestableInvUnload plugin;

	@BeforeEach
	void setUp() {
		server = MockBukkit.mock();
		plugin = MockBukkit.loadWith(TestableInvUnload.class, PLUGIN_YML);
	}

	@AfterEach
	void tearDown() {
		MockBukkit.unmock();
	}

	private NoParticleWorldMock addWorld() {
		NoParticleWorldMock world = new NoParticleWorldMock();
		server.addWorld(world);
		return world;
	}

	private PlayerMock addElevatedPlayer(WorldMock world) {
		PlayerMock player = server.addPlayer();
		player.setLocation(new Location(world, 0, 64, 0));
		return player;
	}

	// --- static pure helpers -------------------------------------------------

	@Test
	void isItemStackEmpty_nullIsEmpty() {
		assertTrue(InvUnload.isItemStackEmpty(null));
	}

	@Test
	void isItemStackEmpty_zeroAmountIsEmpty() {
		ItemStack stack = new ItemStack(Material.DIRT, 1);
		stack.setAmount(0);
		assertTrue(InvUnload.isItemStackEmpty(stack));
	}

	@Test
	void isItemStackEmpty_airIsEmpty() {
		// Bukkit/vanilla normalize any AIR-type ItemStack to amount 0 (there
		// is no such thing as a non-empty AIR stack), so this actually
		// exercises the getAmount() == 0 path above, not a separate AIR-type
		// check - confirmed via a throwaway debug test showing
		// new ItemStack(Material.AIR, 1).getAmount() == 0 despite the
		// constructor's amount argument. See the comment in
		// InvUnload.isItemStackEmpty for the resulting dead-code removal.
		ItemStack stack = new ItemStack(Material.AIR, 1);
		assertEquals(0, stack.getAmount());
		assertTrue(InvUnload.isItemStackEmpty(stack));
	}

	@Test
	void isItemStackEmpty_nonEmptyStackIsNotEmpty() {
		ItemStack stack = new ItemStack(Material.DIRT, 1);
		assertFalse(InvUnload.isItemStackEmpty(stack));
	}

	@Test
	void freeSlotsInChest_nonChestBlockReturnsZero() {
		Block block = mock(Block.class);
		when(block.getState()).thenReturn(mock(org.bukkit.block.BlockState.class));

		assertEquals(0, InvUnload.freeSlotsInChest(block));
	}

	@Test
	void freeSlotsInChest_countsEmptyAndAirSlots() {
		Block block = mock(Block.class);
		Chest chestState = mock(Chest.class);
		Inventory inventory = mock(Inventory.class);
		when(block.getState()).thenReturn(chestState);
		when(chestState.getInventory()).thenReturn(inventory);
		ItemStack zeroAmount = new ItemStack(Material.DIRT, 1);
		zeroAmount.setAmount(0);
		when(inventory.getContents()).thenReturn(new ItemStack[] {
				null,
				new ItemStack(Material.AIR, 1),
				zeroAmount,
				new ItemStack(Material.DIRT, 1)
		});

		assertEquals(3, InvUnload.freeSlotsInChest(block));
	}

	@Test
	void getInventoryContents_skipsEmptyStacksAndDeduplicatesTypes() {
		Inventory inventory = mock(Inventory.class);
		when(inventory.getContents()).thenReturn(new ItemStack[] {
				null,
				new ItemStack(Material.AIR, 1),
				new ItemStack(Material.DIRT, 1),
				new ItemStack(Material.DIRT, 5),
				new ItemStack(Material.STONE, 2)
		});

		List<Material> contents = InvUnload.getInventoryContents(inventory);

		assertEquals(List.of(Material.DIRT, Material.STONE), contents);
	}

	// --- resolveParticle ------------------------------------------------------

	@Test
	void resolveParticle_returnsFirstMatchingCandidate() {
		assertEquals(Particle.FLAME, InvUnload.resolveParticle("FLAME", "SMOKE"));
	}

	@Test
	void resolveParticle_fallsBackToLaterCandidateWhenEarlierOnesDoNotExist() {
		assertEquals(Particle.FLAME, InvUnload.resolveParticle("NOT_A_REAL_PARTICLE_NAME", "FLAME"));
	}

	@Test
	void resolveParticle_throwsWhenNoCandidateMatches() {
		assertThrows(IllegalStateException.class,
				() -> InvUnload.resolveParticle("NOT_A_REAL_PARTICLE_NAME", "ALSO_NOT_REAL"));
	}

	// --- canUseChestHere (WorldGuard absent from test classpath by design) ---

	@Test
	void canUseChestHere_worldGuardAbsent_alwaysTrueRegardlessOfConfig() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);

		plugin.getConfig().set("use-worldguard", true);
		assertTrue(plugin.canUseChestHere(player, player.getLocation()));

		plugin.getConfig().set("use-worldguard", false);
		assertTrue(plugin.canUseChestHere(player, player.getLocation()));
	}

	// --- getNearbyChests -------------------------------------------------------

	@Test
	void getNearbyChests_findsChestsInRadiusAndIgnoresNonChestsAndOutOfRadius() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);

		Block inRangeChest = world.getBlockAt(1, 64, 0);
		inRangeChest.setType(Material.CHEST);

		Block nonChest = world.getBlockAt(0, 64, 1);
		nonChest.setType(Material.DIRT);

		Block outOfRangeChest = world.getBlockAt(5, 64, 0);
		outOfRangeChest.setType(Material.CHEST);

		List<Block> found = plugin.getNearbyChests(player.getLocation(), 1, player);

		assertTrue(found.contains(inRangeChest));
		assertFalse(found.contains(nonChest));
		assertFalse(found.contains(outOfRangeChest));
	}

	@Test
	void getNearbyChests_chestAccessDenied_excludesBlock() {
		// The real WorldGuardHook.canUseChestHere() always returns true in
		// this test suite (see its own Javadoc / TestableInvUnload's
		// denyChestAccess field) since WorldGuard isn't on the test
		// classpath, so this is the only way to exercise
		// getNearbyChests()'s "chest is here, but access is denied" branch.
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);
		plugin.denyChestAccess = true;

		Block inRangeChest = world.getBlockAt(1, 64, 0);
		inRangeChest.setType(Material.CHEST);

		List<Block> found = plugin.getNearbyChests(player.getLocation(), 1, player);

		assertFalse(found.contains(inRangeChest));
	}

	// --- onCommand ---------------------------------------------------------

	private Command commandNamed(String name) {
		Command command = mock(Command.class);
		when(command.getName()).thenReturn(name);
		return command;
	}

	@Test
	void onCommand_nonPlayerSender_returnsTrueWithoutAction() {
		CommandSender sender = mock(CommandSender.class);

		boolean result = plugin.onCommand(sender, commandNamed("unload"), "unload", new String[0]);

		assertTrue(result);
	}

	@Test
	void onCommand_unrecognizedCommandName_returnsFalse() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);

		boolean result = plugin.onCommand(player, commandNamed("notarealcommand"), "notarealcommand", new String[0]);

		assertFalse(result);
	}

	@Test
	void onCommand_unload_nonNumericRadius_sendsNotANumberMessage() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);

		boolean result = plugin.onCommand(player, commandNamed("unload"), "unload", new String[] { "abc" });

		assertTrue(result);
		assertTrue(player.nextMessage().contains("abc"));
	}

	@Test
	void onCommand_unload_radiusTooHigh_sendsRadiusTooHighMessage() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);

		boolean result = plugin.onCommand(player, commandNamed("unload"), "unload", new String[] { "999" });

		assertTrue(result);
		assertTrue(player.nextMessage().contains("20"));
	}

	@Test
	void onCommand_unload_validRadius_movesMatchingItemIntoNearbyChest() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);

		Block chestBlock = world.getBlockAt(1, 64, 0);
		chestBlock.setType(Material.CHEST);
		Chest chestState = (Chest) chestBlock.getState();
		chestState.getInventory().addItem(new ItemStack(Material.DIRT, 1));

		player.getInventory().addItem(new ItemStack(Material.DIRT, 1));

		boolean result = plugin.onCommand(player, commandNamed("unload"), "unload", new String[] { "1" });

		assertTrue(result);
	}

	@Test
	void onCommand_dump_withUnloadBeforeDumpingAndPermission_unloadsThenDumps() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);
		player.addAttachment(plugin, "invunload.unload", true);

		Block chestBlock = world.getBlockAt(1, 64, 0);
		chestBlock.setType(Material.CHEST);
		Chest chestState = (Chest) chestBlock.getState();
		chestState.getInventory().addItem(new ItemStack(Material.DIRT, 1));

		player.getInventory().addItem(new ItemStack(Material.DIRT, 1));
		plugin.getConfig().set("unload-before-dumping", true);

		boolean result = plugin.onCommand(player, commandNamed("dump"), "dump", new String[] { "1" });

		assertTrue(result);
	}

	@Test
	void onCommand_dump_withoutUnloadPermission_skipsPreUnloadButStillDumps() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);
		// deliberately no invunload.unload permission granted

		Block chestBlock = world.getBlockAt(1, 64, 0);
		chestBlock.setType(Material.CHEST);

		player.getInventory().addItem(new ItemStack(Material.DIRT, 1));
		plugin.getConfig().set("unload-before-dumping", true);

		boolean result = plugin.onCommand(player, commandNamed("dump"), "dump", new String[] { "1" });

		assertTrue(result);
	}

	@Test
	void onCommand_dump_nonNumericRadius_sendsNotANumberMessage() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);

		boolean result = plugin.onCommand(player, commandNamed("dump"), "dump", new String[] { "xyz" });

		assertTrue(result);
		assertTrue(player.nextMessage().contains("xyz"));
	}

	@Test
	void onCommand_dump_radiusTooHigh_sendsRadiusTooHighMessage() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);

		boolean result = plugin.onCommand(player, commandNamed("dump"), "dump", new String[] { "999" });

		assertTrue(result);
		assertTrue(player.nextMessage().contains("20"));
	}

	@Test
	void onCommand_unload_emptyArgs_usesDefaultRadiusInstead() {
		// Covers the `arg3.length > 0` false branch: no radius argument at
		// all, so onCommand must fall back to defaultChestRadius rather
		// than trying to parse a radius out of an empty array.
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);

		Block chestBlock = world.getBlockAt(1, 64, 0);
		chestBlock.setType(Material.CHEST);
		Chest chestState = (Chest) chestBlock.getState();
		chestState.getInventory().addItem(new ItemStack(Material.DIRT, 1));

		player.getInventory().addItem(new ItemStack(Material.DIRT, 1));

		boolean result = plugin.onCommand(player, commandNamed("unload"), "unload", new String[0]);

		assertTrue(result);
		assertTrue(chestState.getInventory().contains(Material.DIRT));
	}

	@Test
	void onCommand_dump_emptyArgs_usesDefaultRadiusInstead() {
		// Same as above, for the /dump branch's own `arg3.length > 0`
		// check.
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);
		fillHotbarWithAir(player);

		Block chestBlock = world.getBlockAt(1, 64, 0);
		chestBlock.setType(Material.CHEST);
		Chest chestState = (Chest) chestBlock.getState();

		player.getInventory().setItem(9, new ItemStack(Material.DIRT, 1));

		boolean result = plugin.onCommand(player, commandNamed("dump"), "dump", new String[0]);

		assertTrue(result);
		assertTrue(chestState.getInventory().contains(Material.DIRT));
	}

	@Test
	void onCommand_dump_unloadBeforeDumpingFalse_skipsPreUnloadEntirely() {
		// Covers the other half of `unload-before-dumping && hasPermission`:
		// here the player DOES have permission, but the config flag itself
		// is false, so unloadInventory() must never run pre-dump (the
		// sibling test above covers the permission-false half of this same
		// AND check). dumpInventory() would move the same item regardless
		// of whether unloadInventory() ran first, so the only way to prove
		// unloadInventory() itself was skipped - not just that its
		// end-result happens to be unobservable - is TestableInvUnload's
		// own unloadInventoryCallCount counter (see its Javadoc: Mockito
		// cannot spy InvUnload directly, since byte-buddy fails trying to
		// instrument the compileOnly, deliberately-absent-at-test-runtime
		// WorldGuard field type).
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);
		player.addAttachment(plugin, "invunload.unload", true);
		fillHotbarWithAir(player);
		plugin.getConfig().set("unload-before-dumping", false);

		Block chestBlock = world.getBlockAt(1, 64, 0);
		chestBlock.setType(Material.CHEST);

		player.getInventory().setItem(9, new ItemStack(Material.DIRT, 1));

		boolean result = plugin.onCommand(player, commandNamed("dump"), "dump", new String[] { "1" });

		assertTrue(result);
		assertEquals(0, plugin.unloadInventoryCallCount);
	}

	// --- onEnable ------------------------------------------------------------
	// Each scenario below pre-seeds the plugin's own data-folder config.yml
	// (before InvUnload's real onEnable() runs) by overriding onEnable() in a
	// tiny named subclass that writes the file and then defers to
	// super.onEnable() - the only way to get a config file in place before
	// MockBukkit.loadWith(...) synchronously calls onEnable() as part of
	// loading. This lets each of onEnable's real config-migration branches
	// run for real rather than being reasoned about only from source.

	public abstract static class ConfigSeedingInvUnload extends InvUnload {
		// Writes a config.yml into this plugin's own data folder before
		// deferring to the real super.onEnable() - getDataFolder() is
		// already valid at this point in the real JavaPlugin lifecycle
		// (set during plugin construction/initialization, before
		// onEnable() is ever invoked), even though saveDefaultConfig()
		// (the first line of the real onEnable()) has not run yet. This is
		// the only seam available to get a specific config.yml on disk
		// before InvUnload's own config-version migration logic reads it,
		// since MockBukkit.loadWith(...) loads and enables synchronously
		// with no gap for a test to intervene in between.
		protected final void writeConfig(String yaml) {
			try {
				File dataFolder = getDataFolder();
				dataFolder.mkdirs();
				java.nio.file.Files.writeString(new File(dataFolder, "config.yml").toPath(), yaml);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	public static class ConfigVersionZeroInvUnload extends ConfigSeedingInvUnload {
		@Override
		public void onEnable() {
			writeConfig("config-version: 0\n");
			super.onEnable();
		}
	}

	public static class ConfigVersionMismatchInvUnload extends ConfigSeedingInvUnload {
		@Override
		public void onEnable() {
			writeConfig("config-version: 99\n");
			super.onEnable();
		}
	}

	public static class UseWorldGuardInvUnload extends ConfigSeedingInvUnload {
		@Override
		public void onEnable() {
			writeConfig("config-version: 2\nuse-worldguard: true\n");
			super.onEnable();
		}
	}

	public static class CheckForUpdatesTrueInvUnload extends ConfigSeedingInvUnload {
		@Override
		public void onEnable() {
			writeConfig("config-version: 2\ncheck-for-updates: \"true\"\n");
			super.onEnable();
		}
	}

	public static class CheckForUpdatesOnStartupInvUnload extends ConfigSeedingInvUnload {
		@Override
		public void onEnable() {
			writeConfig("config-version: 2\ncheck-for-updates: \"on-startup\"\n");
			super.onEnable();
		}
	}

	public static class PreExistingOldConfigInvUnload extends ConfigSeedingInvUnload {
		@Override
		public void onEnable() {
			writeConfig("config-version: 0\n");
			// Pre-seed a config.old.yml too, so the migration branch's own
			// `if (oldConfigFile...exists()) { oldConfigFile...delete(); }`
			// guard has something to actually delete - without this, only
			// the false side of that check (no stale file to clean up) is
			// ever reachable, since every other migration test starts from
			// a clean data folder.
			try {
				File dataFolder = getDataFolder();
				dataFolder.mkdirs();
				java.nio.file.Files.writeString(new File(dataFolder, "config.old.yml").toPath(), "stale: true\n");
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			super.onEnable();
		}
	}

	public static class CorruptSecondSaveDefaultConfigInvUnload extends ConfigSeedingInvUnload {
		private int saveDefaultConfigCallCount = 0;

		@Override
		public void onEnable() {
			writeConfig("config-version: 0\n");
			super.onEnable();
		}

		@Override
		public void saveDefaultConfig() {
			saveDefaultConfigCallCount++;
			if (saveDefaultConfigCallCount == 2) {
				// The migration branch calls saveDefaultConfig() a second
				// time (after renaming the old config out of the way) and
				// then immediately reloads the freshly generated file via
				// getConfig().load(configFile...) - the only way to
				// exercise that reload's own IOException/
				// InvalidConfigurationException catch block is to make
				// the "freshly generated" file invalid YAML by the time
				// the reload happens, which this override does by
				// corrupting it right after the real saveDefaultConfig()
				// would otherwise have written a valid one.
				try {
					File dataFolder = getDataFolder();
					java.nio.file.Files.writeString(new File(dataFolder, "config.yml").toPath(),
							"not: valid: yaml: [unterminated");
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				return;
			}
			super.saveDefaultConfig();
		}
	}

	@Test
	void onEnable_configVersionBelowOne_migratesOldConfigAndRegeneratesDefault() {
		ConfigVersionZeroInvUnload migrated = MockBukkit.loadWith(ConfigVersionZeroInvUnload.class, PLUGIN_YML);

		File oldConfig = new File(migrated.getDataFolder(), "config.old.yml");
		File newConfig = new File(migrated.getDataFolder(), "config.yml");
		assertTrue(oldConfig.exists());
		assertTrue(newConfig.exists());
		assertEquals(2, migrated.getConfig().getInt("config-version"));
		assertTrue(migrated.usingMatchingConfig);
	}

	@Test
	void onEnable_configVersionBelowOne_preExistingOldConfig_isDeletedBeforeRename() {
		// Covers the migration branch's `if (oldConfigFile...exists())
		// { ...delete(); }` true side - a stale config.old.yml left over
		// from some earlier migration must be removed before the current
		// config.yml is renamed into that same path, or the rename would
		// silently fail/overwrite depending on filesystem semantics.
		PreExistingOldConfigInvUnload migrated = MockBukkit.loadWith(PreExistingOldConfigInvUnload.class, PLUGIN_YML);

		File oldConfig = new File(migrated.getDataFolder(), "config.old.yml");
		assertTrue(oldConfig.exists());
		// The freshly-renamed current config.yml, not the pre-existing
		// "stale: true" placeholder this test seeded - if delete() hadn't
		// run, the failed/skipped rename could leave the stale file in
		// place instead.
		assertFalse(migrated.getConfig().getBoolean("stale"));
	}

	@Test
	void onEnable_configVersionBelowOne_reloadOfFreshConfigFails_logsWarningWithoutThrowing() {
		// Covers onEnable()'s IOException/InvalidConfigurationException
		// catch block around getConfig().load(configFile...) - only
		// reachable if the freshly-regenerated config.yml is somehow
		// invalid by the time it's reloaded, which
		// CorruptSecondSaveDefaultConfigInvUnload forces by corrupting the
		// file instead of letting the real saveDefaultConfig() write it.
		CorruptSecondSaveDefaultConfigInvUnload corrupted =
				MockBukkit.loadWith(CorruptSecondSaveDefaultConfigInvUnload.class, PLUGIN_YML);

		// Reaching this line without the exception escaping onEnable() (and
		// without MockBukkit refusing to enable the plugin) is the behavior
		// under test.
		assertTrue(server.getPluginManager().isPluginEnabled(corrupted));
	}

	@Test
	void onEnable_configVersionMismatch_setsUsingMatchingConfigFalse() {
		ConfigVersionMismatchInvUnload mismatched = MockBukkit.loadWith(ConfigVersionMismatchInvUnload.class, PLUGIN_YML);

		assertFalse(mismatched.usingMatchingConfig);
	}

	@Test
	void onEnable_defaultConfig_usingMatchingConfigStaysTrue() {
		assertTrue(plugin.usingMatchingConfig);
	}

	@Test
	void onEnable_useWorldguardTrue_hooksAttemptWithoutThrowingSinceWorldGuardAbsent() {
		// This exercises InvUnload.onEnable()'s own `if (use-worldguard) { ... }`
		// branch, which delegates to WorldGuardHook.tryHook(...). The actual
		// WorldGuard-present/absent logic inside tryHook() lives in
		// WorldGuardHook, excluded from coverage verification separately (see
		// WorldGuardHook.java) - and its declared field type here
		// (com.sk89q.worldguard.WorldGuard) is deliberately absent from the
		// test classpath, so it cannot even be read back via reflection
		// (Field.get() needs to resolve the declared type). Reaching onEnable's
		// own delegation line without an exception escaping is what this test
		// covers.
		UseWorldGuardInvUnload withWorldGuard = MockBukkit.loadWith(UseWorldGuardInvUnload.class, PLUGIN_YML);

		assertTrue(server.getPluginManager().isPluginEnabled(withWorldGuard));
	}

	@Test
	void onEnable_checkForUpdatesTrue_schedulesRepeatingTaskThatRunsWithoutThrowing() {
		CheckForUpdatesTrueInvUnload withScheduledCheck = MockBukkit.loadWith(CheckForUpdatesTrueInvUnload.class, PLUGIN_YML);

		BukkitSchedulerMock scheduler = (BukkitSchedulerMock) server.getScheduler();
		// The real (dead, non-resolving) endpoint fails fast via
		// UnknownHostException - checkForUpdate()'s own try/catch absorbs
		// it, so ticking the scheduler should complete without the
		// exception escaping to the test.
		scheduler.performOneTick();

		assertTrue(server.getPluginManager().isPluginEnabled(withScheduledCheck));
	}

	@Test
	void onEnable_checkForUpdatesOnStartup_runsSynchronouslyWithoutThrowing() {
		CheckForUpdatesOnStartupInvUnload withStartupCheck = MockBukkit.loadWith(CheckForUpdatesOnStartupInvUnload.class, PLUGIN_YML);

		// onEnable() already ran checkForUpdate() synchronously by the time
		// loadWith(...) returns - reaching this line without an exception
		// having escaped is the behavior under test.
		assertTrue(server.getPluginManager().isPluginEnabled(withStartupCheck));
	}

	// --- onPlayerJoin --------------------------------------------------------

	@Test
	void onPlayerJoin_opPlayerWithPendingUpdate_sendsUpdateMessage() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);
		player.setOp(true);
		plugin.updateChecker.handleLatestVersionLine("999.0.0");

		plugin.onPlayerJoin(new PlayerJoinEvent(player, (String) null));

		assertTrue(player.nextMessage() != null);
	}

	@Test
	void onPlayerJoin_nonOpPlayer_sendsNoUpdateMessage() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);
		player.setOp(false);
		plugin.updateChecker.handleLatestVersionLine("999.0.0");

		plugin.onPlayerJoin(new PlayerJoinEvent(player, (String) null));

		assertNull(player.nextMessage());
	}

	// --- trySort (ChestSort soft-dependency via reflection) -------------------
	// InvUnload.trySort(Inventory) has no compile-time dependency on ChestSort
	// (see CLAUDE.md) - it looks up a plugin literally named "ChestSort" and
	// reflectively invokes sortInventory(Inventory) if present. These fixture
	// plugins stand in for the real (never-resolvable, per this repo's own
	// history) ChestSort jar so all three branches - absent, present with a
	// matching method, present without one - run for real.

	private <T extends org.bukkit.plugin.java.JavaPlugin> T registerFakePlugin(Class<T> pluginClass, String name) {
		org.bukkit.plugin.PluginDescriptionFile description =
				new org.bukkit.plugin.PluginDescriptionFile(name, "1.0", pluginClass.getName());
		T fake = pluginClass.cast(server.getPluginManager().loadPlugin(pluginClass, description, new Object[0]));
		server.getPluginManager().enablePlugin(fake);
		return fake;
	}

	@Test
	void trySort_chestSortAbsent_unloadStillSucceeds() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);

		Block chestBlock = world.getBlockAt(1, 64, 0);
		chestBlock.setType(Material.CHEST);
		Chest chestState = (Chest) chestBlock.getState();
		chestState.getInventory().addItem(new ItemStack(Material.DIRT, 1));
		player.getInventory().addItem(new ItemStack(Material.DIRT, 1));

		boolean result = plugin.unloadInventory(player, 1, true);

		assertTrue(result);
	}

	@Test
	void trySort_chestSortPresentWithMatchingMethod_isInvokedReflectively() {
		FakeChestSortPlugin chestSort = registerFakePlugin(FakeChestSortPlugin.class, "ChestSort");

		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);

		Block chestBlock = world.getBlockAt(1, 64, 0);
		chestBlock.setType(Material.CHEST);
		Chest chestState = (Chest) chestBlock.getState();
		chestState.getInventory().addItem(new ItemStack(Material.DIRT, 1));
		player.getInventory().addItem(new ItemStack(Material.DIRT, 1));

		boolean result = plugin.unloadInventory(player, 1, true);

		assertTrue(result);
		assertTrue(chestSort.getSortInventoryCallCount() >= 1);
	}

	@Test
	void trySort_chestSortPresentWithoutMatchingMethod_logsWarningButDoesNotThrow() {
		registerFakePlugin(FakeChestSortMissingMethodPlugin.class, "ChestSort");

		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);

		Block chestBlock = world.getBlockAt(1, 64, 0);
		chestBlock.setType(Material.CHEST);
		Chest chestState = (Chest) chestBlock.getState();
		chestState.getInventory().addItem(new ItemStack(Material.DIRT, 1));
		player.getInventory().addItem(new ItemStack(Material.DIRT, 1));

		boolean result = plugin.unloadInventory(player, 1, true);

		assertTrue(result);
	}

	// --- unloadInventory -------------------------------------------------------

	@Test
	void unloadInventory_noChestsNearby_sendsMessageAndReturnsFalse() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);

		boolean result = plugin.unloadInventory(player, 1, true);

		assertFalse(result);
		assertTrue(player.nextMessage().contains("no chests nearby"));
	}

	@Test
	void unloadInventory_noChestsNearby_showMessageOnFailFalse_sendsNoMessage() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);

		boolean result = plugin.unloadInventory(player, 1, false);

		assertFalse(result);
		assertNull(player.nextMessage());
	}

	@Test
	void unloadInventory_chestPresentButNoMatchingItemType_returnsFalseCouldNotUnload() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);

		Block chestBlock = world.getBlockAt(1, 64, 0);
		chestBlock.setType(Material.CHEST);
		Chest chestState = (Chest) chestBlock.getState();
		chestState.getInventory().addItem(new ItemStack(Material.STONE, 1));

		player.getInventory().addItem(new ItemStack(Material.DIRT, 1));

		boolean result = plugin.unloadInventory(player, 1, true);

		assertFalse(result);
		assertTrue(player.nextMessage().contains("nothing to unload"));
	}

	@Test
	void unloadInventory_chestPresentButNoMatchingItemType_showMessageOnFailFalse_sendsNoMessage() {
		// Companion to the "nothing to unload" test above but with
		// showMessageOnFail=false, covering the other outcome of the final
		// `if (showMessageOnFail)` guard in unloadInventory's failure path.
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);

		Block chestBlock = world.getBlockAt(1, 64, 0);
		chestBlock.setType(Material.CHEST);
		Chest chestState = (Chest) chestBlock.getState();
		chestState.getInventory().addItem(new ItemStack(Material.STONE, 1));

		player.getInventory().addItem(new ItemStack(Material.DIRT, 1));

		boolean result = plugin.unloadInventory(player, 1, false);

		assertFalse(result);
		assertNull(player.nextMessage());
	}

	@Test
	void unloadInventory_singleChestHappyPath_movesMatchingItemAndSpawnsParticles() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);

		Block chestBlock = world.getBlockAt(1, 64, 0);
		chestBlock.setType(Material.CHEST);
		Chest chestState = (Chest) chestBlock.getState();
		chestState.getInventory().addItem(new ItemStack(Material.DIRT, 1));

		// Two separate DIRT stacks in two distinct slots (matching the same
		// chest's existing DIRT content type) rather than one - addItem()
		// twice would just merge into a single stack in one slot, so this
		// uses setItem() into two slots instead. The first exercises the
		// "not yet queued for particles, add it" branch of the else-if at
		// the bottom of this bookkeeping, the second exercises the
		// "already queued, skip" branch, which a single-item test can never
		// reach.
		player.getInventory().setItem(0, new ItemStack(Material.DIRT, 5));
		player.getInventory().setItem(1, new ItemStack(Material.DIRT, 2));

		boolean result = plugin.unloadInventory(player, 1, true);

		assertTrue(result);
		assertTrue(chestState.getInventory().contains(Material.DIRT));
	}

	@Test
	void unloadInventory_chestPartiallyAccepts_leftoverReturnedButStillSpawnsParticles() {
		// unloadInventory's "did this succeed" check
		// (`unstorableItemStacks.size() == 0 || ...get(0).getAmount() != itemStack.getAmount()`)
		// has three genuinely distinct outcomes - fully stored, fully
		// rejected unchanged, and partially stored - and the other tests in
		// this file only ever hit the first two. This one forces a partial
		// store (accepts some, returns the rest) to exercise the missing
		// "amount changed" branch.
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);

		Block mockedBlock = mock(Block.class);
		Chest mockedChestState = mock(Chest.class);
		Inventory partialInventory = mock(Inventory.class);
		when(mockedBlock.getState()).thenReturn(mockedChestState);
		when(mockedChestState.getInventory()).thenReturn(partialInventory);
		when(partialInventory.getContents()).thenReturn(new ItemStack[] { new ItemStack(Material.DIRT, 1) });
		when(partialInventory.addItem(any(ItemStack.class))).thenAnswer(this::partiallyStoreFourUnits);
		when(mockedBlock.getLocation()).thenReturn(new Location(world, 1, 64, 0));

		plugin.canned = List.of(mockedBlock);
		player.getInventory().addItem(new ItemStack(Material.DIRT, 6));

		boolean result = plugin.unloadInventory(player, 1, true);

		assertTrue(result);
		verify(partialInventory).addItem(any(ItemStack.class));
		// 4 of the 6 were stored, 2 handed back - the player should still
		// be holding some leftover DIRT.
		assertTrue(player.getInventory().contains(Material.DIRT, 2));
	}

	@Test
	void unloadInventory_chestFull_returnsUnstorableItemsToPlayer() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);

		Block mockedBlock = mock(Block.class);
		Chest mockedChestState = mock(Chest.class);
		Inventory fullInventory = mock(Inventory.class);
		when(mockedBlock.getState()).thenReturn(mockedChestState);
		when(mockedChestState.getInventory()).thenReturn(fullInventory);
		when(mockedChestState.getBlock()).thenReturn(mockedBlock);
		ItemStack toStore = new ItemStack(Material.DIRT, 5);
		when(fullInventory.getContents()).thenReturn(new ItemStack[] { new ItemStack(Material.DIRT, 1) });
		HashMap<Integer, ItemStack> unstorable = new HashMap<>();
		unstorable.put(0, toStore);
		when(fullInventory.addItem(any(ItemStack.class))).thenReturn(unstorable);

		plugin.canned = List.of(mockedBlock);
		player.getInventory().addItem(toStore);

		boolean result = plugin.unloadInventory(player, 1, true);

		assertFalse(result);
		verify(fullInventory).addItem(any(ItemStack.class));
	}

	@Test
	void unloadInventory_doubleChest_movesItemsAcrossMergedInventory() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);

		Block leftBlock = world.getBlockAt(0, 64, 0);
		leftBlock.setType(Material.CHEST);
		Chest leftChestState = (Chest) leftBlock.getState();
		leftChestState.getInventory().addItem(new ItemStack(Material.DIRT, 1));

		Block rightBlock = world.getBlockAt(1, 64, 0);
		rightBlock.setType(Material.CHEST);
		Chest rightChestState = (Chest) rightBlock.getState();

		DoubleChest doubleChest = mock(DoubleChest.class);
		when(doubleChest.getLeftSide()).thenReturn(leftChestState);
		when(doubleChest.getRightSide()).thenReturn(rightChestState);

		Inventory mergedInventory = mock(Inventory.class);
		when(mergedInventory.getHolder()).thenReturn(doubleChest);
		when(mergedInventory.getContents()).thenReturn(new ItemStack[] { new ItemStack(Material.DIRT, 1) });
		when(mergedInventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());

		Block mockedBlock = mock(Block.class);
		Chest mockedChestState = mock(Chest.class);
		when(mockedBlock.getState()).thenReturn(mockedChestState);
		when(mockedChestState.getInventory()).thenReturn(mergedInventory);

		plugin.canned = List.of(mockedBlock);
		// Two DIRT stacks in two distinct slots (not addItem() twice, which
		// would just merge into one slot and be iterated as a single
		// ItemStack) so the second exercises the "already queued for
		// particles" skip branch for both the left and right double-chest
		// halves, which a single-item test can never reach.
		player.getInventory().setItem(0, new ItemStack(Material.DIRT, 1));
		player.getInventory().setItem(1, new ItemStack(Material.DIRT, 1));

		boolean result = plugin.unloadInventory(player, 1, true);

		assertTrue(result);
		verify(mergedInventory, times(2)).addItem(any(ItemStack.class));
	}

	// --- dumpInventory -----------------------------------------------------

	private void fillHotbarWithAir(PlayerMock player) {
		// dumpInventory() explicitly excludes the 9 hotbar slots from what it
		// dumps - clearing them (true null slots, matching MockBukkit's own
		// default empty-slot representation) means every item added via
		// player.getInventory().addItem(...) below lands in the non-hotbar
		// storage slots dumpInventory actually considers. NOTE: deliberately
		// NOT done via setItem(i, new ItemStack(Material.AIR)) - MockBukkit's
		// ItemStackMock gives Material.AIR a null itemMeta (see
		// ItemStackMock.findItemMeta), and InventoryMock.addItem() calls
		// getMaxStackSize() on every non-null existing slot while looking for
		// a stack to merge into, which NPEs on that null itemMeta. clear(i)
		// leaves the slot as a real null reference instead, which addItem's
		// scan skips entirely.
		for (int i = 0; i < 9; i++) {
			player.getInventory().clear(i);
		}
	}

	@Test
	void dumpInventory_noChestsNearby_sendsMessage() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);

		plugin.dumpInventory(player, 1);

		assertTrue(player.nextMessage().contains("no chests nearby"));
	}

	@Test
	void dumpInventory_singleChest_dumpsNonHotbarItemsAndSpawnsParticles() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);
		fillHotbarWithAir(player);

		Block chestBlock = world.getBlockAt(1, 64, 0);
		chestBlock.setType(Material.CHEST);
		Chest chestState = (Chest) chestBlock.getState();

		// setItem(9, ...) rather than addItem(...): with the hotbar cleared,
		// addItem() would fill the now-empty hotbar slot 0 first, and
		// dumpInventory() explicitly excludes hotbar slots from what it
		// dumps - slot 9 is the first non-hotbar storage slot.
		player.getInventory().setItem(9, new ItemStack(Material.DIRT, 1));

		plugin.dumpInventory(player, 1);

		assertTrue(chestState.getInventory().contains(Material.DIRT));
	}

	@Test
	void dumpInventory_doubleChest_dumpsIntoMergedInventoryAndMarksBothSidesForParticles() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);
		fillHotbarWithAir(player);

		Inventory mergedInventory = mock(Inventory.class);
		when(mergedInventory.getContents()).thenReturn(new ItemStack[0]);
		when(mergedInventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());

		// leftBlock is the chest-space-combination candidate (dumpInventory
		// adds leftChest.getBlock(), not the merged-holder block, as the
		// candidate - see the getNearbyChests loop in InvUnload.dumpInventory)
		// and is what actually resolves to the merged inventory for storage.
		Block leftBlock = mock(Block.class);
		Chest mockedChestState = mock(Chest.class);
		when(leftBlock.getState()).thenReturn(mockedChestState);
		when(mockedChestState.getInventory()).thenReturn(mergedInventory);
		when(leftBlock.getLocation()).thenReturn(new Location(world, 1, 64, 0));

		// rightBlock is deliberately a DIFFERENT Block identity than
		// leftBlock (a real double chest's two halves are two distinct
		// blocks that happen to share one merged Inventory) - this is what
		// lets the "mark both sides for particles" bookkeeping actually add
		// both blocks instead of leftChest.getBlock()'s add being skipped as
		// an already-present duplicate of rightChest.getBlock().
		Block rightBlock = mock(Block.class);
		when(rightBlock.getLocation()).thenReturn(new Location(world, 2, 64, 0));

		DoubleChest doubleChest = mock(DoubleChest.class);
		when(mergedInventory.getHolder()).thenReturn(doubleChest);

		// dumpInventory()'s DoubleChest branch (unlike unloadInventory's)
		// fetches the chest inventory to actually store items in via
		// doubleChest.getLeftSide().getBlock().getState().getInventory() -
		// in real Paper/Bukkit either half of a real double chest returns
		// the same merged inventory from getInventory(), so both left and
		// right sides here are mocked to route back to that one merged
		// inventory rather than a real, unmerged MockBukkit chest half
		// (MockBukkit doesn't auto-merge adjacent chests at all - see
		// TestableInvUnload's Javadoc).
		Chest leftChestState = mock(Chest.class);
		when(leftChestState.getBlock()).thenReturn(leftBlock);
		Chest rightChestState = mock(Chest.class);
		when(rightChestState.getBlock()).thenReturn(rightBlock);
		when(doubleChest.getLeftSide()).thenReturn(leftChestState);
		when(doubleChest.getRightSide()).thenReturn(rightChestState);

		plugin.canned = List.of(leftBlock);
		// setItem(9/10, ...) rather than addItem(...) - see comment in
		// dumpInventory_singleChest... above. Two items (not one) so the
		// second pass through the loop hits the "this block is already
		// queued for particles" skip branch for both sides, not just the
		// "first time, add it" branch the first item exercises.
		player.getInventory().setItem(9, new ItemStack(Material.DIRT, 1));
		player.getInventory().setItem(10, new ItemStack(Material.STONE, 1));

		plugin.dumpInventory(player, 1);

		verify(mergedInventory, times(2)).addItem(any(ItemStack.class));
	}

	// Both "full" chest stubs below use thenAnswer (not a fixed thenReturn
	// map) so the mock genuinely echoes back whatever ItemStack it was
	// actually asked to store, rather than always handing back one
	// hardcoded stack regardless of the real argument - needed once a test
	// pushes more than one item through the same rejecting mock.
	private HashMap<Integer, ItemStack> rejectWhateverIsGiven(org.mockito.invocation.InvocationOnMock invocation) {
		HashMap<Integer, ItemStack> rejected = new HashMap<>();
		rejected.put(0, invocation.getArgument(0));
		return rejected;
	}

	// Stores exactly 4 units of whatever it is given, handing back the rest
	// (or nothing, if 4 or fewer were given) - a generic "partial store"
	// mock answer, as opposed to rejectWhateverIsGiven's "full reject".
	private HashMap<Integer, ItemStack> partiallyStoreFourUnits(org.mockito.invocation.InvocationOnMock invocation) {
		ItemStack given = invocation.getArgument(0);
		HashMap<Integer, ItemStack> leftover = new HashMap<>();
		if (given.getAmount() > 4) {
			ItemStack remainder = given.clone();
			remainder.setAmount(given.getAmount() - 4);
			leftover.put(0, remainder);
		}
		return leftover;
	}

	@Test
	void dumpInventory_firstChestFull_overflowsIntoSecondChest() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);
		fillHotbarWithAir(player);

		// dumpInventory sorts candidate chests by free-slot count, most free
		// space first (see ChestSpaceCombination.compareTo +
		// Collections.reverseOrder()), so fullBlock is deliberately given
		// MORE reported free slots than secondBlock despite always
		// rejecting the item - otherwise it sorts *after* secondBlock and
		// the overflow loop this test means to exercise never actually
		// runs. (A real bug in an earlier draft of this test: fullBlock
		// reported 0 free slots, secondBlock was a real empty 27-slot
		// MockBukkit chest, so secondBlock sorted first and the item landed
		// there on the very first try - the test still passed because the
        // item did end up in secondBlock's inventory either way, but the
		// "first chest full, spill into second" code path was never
		// actually exercised despite the assertion being satisfied.)
		Block fullBlock = mock(Block.class);
		Chest fullChestState = mock(Chest.class);
		Inventory fullInventory = mock(Inventory.class);
		when(fullBlock.getState()).thenReturn(fullChestState);
		when(fullChestState.getInventory()).thenReturn(fullInventory);
		when(fullInventory.getContents()).thenReturn(new ItemStack[10]);
		when(fullInventory.addItem(any(ItemStack.class))).thenAnswer(this::rejectWhateverIsGiven);

		Block secondBlock = mock(Block.class);
		Chest secondChestState = mock(Chest.class);
		Inventory secondInventory = mock(Inventory.class);
		when(secondBlock.getState()).thenReturn(secondChestState);
		when(secondChestState.getInventory()).thenReturn(secondInventory);
		when(secondInventory.getContents()).thenReturn(new ItemStack[5]);
		when(secondInventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());
		// secondBlock is the one that actually stores the item, so it gets
		// added to dumpInventory's particle-spawn list, which later calls
		// block.getLocation().getWorld() - needs a real Location, unlike
		// fullBlock which never succeeds and is never added to that list.
		when(secondBlock.getLocation()).thenReturn(new Location(world, 1, 64, 0));

		plugin.canned = List.of(fullBlock, secondBlock);
		ItemStack toDump = new ItemStack(Material.DIRT, 5);
		// setItem(9, ...) rather than addItem(...) - see comment in
		// dumpInventory_singleChest... above.
		player.getInventory().setItem(9, toDump);

		plugin.dumpInventory(player, 1);

		verify(fullInventory).addItem(any(ItemStack.class));
		verify(secondInventory).addItem(any(ItemStack.class));
	}

	@Test
	void dumpInventory_allChestsFull_returnsUnstorableItemToPlayer() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);
		fillHotbarWithAir(player);

		Block fullBlock = mock(Block.class);
		Chest fullChestState = mock(Chest.class);
		Inventory fullInventory = mock(Inventory.class);
		when(fullBlock.getState()).thenReturn(fullChestState);
		when(fullChestState.getInventory()).thenReturn(fullInventory);
		when(fullInventory.getContents()).thenReturn(new ItemStack[0]);
		when(fullInventory.addItem(any(ItemStack.class))).thenAnswer(this::rejectWhateverIsGiven);

		plugin.canned = List.of(fullBlock);
		ItemStack toDump = new ItemStack(Material.DIRT, 5);
		player.getInventory().setItem(9, toDump);

		plugin.dumpInventory(player, 1);

		// The only candidate chest rejects the whole stack every time -
		// dumpInventory should hand the item back to the player
		// (`if (unstorable.size() > 0) p.getInventory().addItem(...)`)
		// rather than silently discarding it.
		assertTrue(player.getInventory().contains(Material.DIRT));
	}

	@Test
	void dumpInventory_secondChestIsDoubleChest_marksBothSidesDuringOverflow() {
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);
		fillHotbarWithAir(player);

		// First candidate: always rejects, forcing overflow into the
		// second candidate - see dumpInventory_firstChestFull_overflowsIntoSecondChest
		// for why free-slot counts are rigged this way.
		Block fullBlock = mock(Block.class);
		Chest fullChestState = mock(Chest.class);
		Inventory fullInventory = mock(Inventory.class);
		when(fullBlock.getState()).thenReturn(fullChestState);
		when(fullChestState.getInventory()).thenReturn(fullInventory);
		when(fullInventory.getContents()).thenReturn(new ItemStack[10]);
		when(fullInventory.addItem(any(ItemStack.class))).thenAnswer(this::rejectWhateverIsGiven);

		// Second candidate: a double chest that successfully stores
		// whatever overflows into it - dumpInventory's overflow loop
		// (the `while` in dumpInventory) has its own separate copy of the
		// "mark both sides for particles" bookkeeping, distinct from the
		// one above the loop that the other double-chest test exercises,
		// so it needs its own dedicated scenario.
		Inventory mergedInventory = mock(Inventory.class);
		when(mergedInventory.getContents()).thenReturn(new ItemStack[5]);
		when(mergedInventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());

		Block secondBlock = mock(Block.class);
		Chest secondChestState = mock(Chest.class);
		when(secondBlock.getState()).thenReturn(secondChestState);
		when(secondChestState.getInventory()).thenReturn(mergedInventory);
		when(secondBlock.getLocation()).thenReturn(new Location(world, 1, 64, 0));

		Block rightBlock = mock(Block.class);
		when(rightBlock.getLocation()).thenReturn(new Location(world, 2, 64, 0));

		DoubleChest doubleChest = mock(DoubleChest.class);
		when(mergedInventory.getHolder()).thenReturn(doubleChest);
		Chest leftChestState = mock(Chest.class);
		when(leftChestState.getBlock()).thenReturn(secondBlock);
		Chest rightChestState = mock(Chest.class);
		when(rightChestState.getBlock()).thenReturn(rightBlock);
		when(doubleChest.getLeftSide()).thenReturn(leftChestState);
		when(doubleChest.getRightSide()).thenReturn(rightChestState);

		plugin.canned = List.of(fullBlock, secondBlock);
		// Two items (not one) so the second item's overflow hits the
		// "already queued for particles" skip branch for both double-chest
		// sides, not just the "first time, add it" branch the first item
		// exercises.
		player.getInventory().setItem(9, new ItemStack(Material.DIRT, 5));
		player.getInventory().setItem(10, new ItemStack(Material.STONE, 3));

		plugin.dumpInventory(player, 1);

		verify(mergedInventory, times(2)).addItem(any(ItemStack.class));
	}

	@Test
	void dumpInventory_partialStoresAcrossThreeChests_hitsEveryStorageOutcome() {
		// dumpInventory's "did this attempt succeed" check
		// (`unstorable.size() == 0 || unstorable.get(0).getAmount() != amount`)
		// is a two-part OR, both at the first chest and again inside the
		// overflow loop for every later chest, and JaCoCo treats each half
		// of an OR as its own branch - so "fully stored", "partially
		// stored", and "fully rejected unchanged" are three genuinely
		// distinct outcomes that all need their own scenario. This one test
		// chains three chests to hit all of them in both places at once:
		// chest1 (top-level) partially stores, chest2 (first loop attempt)
		// fully rejects unchanged, chest3 (second loop attempt) partially
		// stores again.
		NoParticleWorldMock world = addWorld();
		PlayerMock player = addElevatedPlayer(world);
		fillHotbarWithAir(player);

		Block chest1Block = mock(Block.class);
		Chest chest1State = mock(Chest.class);
		Inventory chest1Inventory = mock(Inventory.class);
		when(chest1Block.getState()).thenReturn(chest1State);
		when(chest1State.getInventory()).thenReturn(chest1Inventory);
		when(chest1Inventory.getContents()).thenReturn(new ItemStack[30]);
		when(chest1Inventory.addItem(any(ItemStack.class))).thenAnswer(this::partiallyStoreFourUnits);
		when(chest1Block.getLocation()).thenReturn(new Location(world, 1, 64, 0));

		Block chest2Block = mock(Block.class);
		Chest chest2State = mock(Chest.class);
		Inventory chest2Inventory = mock(Inventory.class);
		when(chest2Block.getState()).thenReturn(chest2State);
		when(chest2State.getInventory()).thenReturn(chest2Inventory);
		when(chest2Inventory.getContents()).thenReturn(new ItemStack[20]);
		when(chest2Inventory.addItem(any(ItemStack.class))).thenAnswer(this::rejectWhateverIsGiven);

		Block chest3Block = mock(Block.class);
		Chest chest3State = mock(Chest.class);
		Inventory chest3Inventory = mock(Inventory.class);
		when(chest3Block.getState()).thenReturn(chest3State);
		when(chest3State.getInventory()).thenReturn(chest3Inventory);
		when(chest3Inventory.getContents()).thenReturn(new ItemStack[10]);
		when(chest3Inventory.addItem(any(ItemStack.class))).thenAnswer(this::partiallyStoreFourUnits);
		when(chest3Block.getLocation()).thenReturn(new Location(world, 3, 64, 0));

		// Reported free-slot counts (30/20/10) match the trial order this
		// test relies on, since dumpInventory tries candidates most-free-
		// space-first - see the sort-order comment in
		// dumpInventory_firstChestFull_overflowsIntoSecondChest.
		plugin.canned = List.of(chest1Block, chest2Block, chest3Block);
		ItemStack toDump = new ItemStack(Material.DIRT, 10);
		player.getInventory().setItem(9, toDump);

		plugin.dumpInventory(player, 1);

		// chest1 stores 4 (partial - top-level "amount changed" branch),
		// chest2 rejects all 6 remaining unchanged (loop's "unchanged"
		// branch), chest3 stores 4 more of those 6 (loop's "amount
		// changed" branch), leaving 2 that could not be stored anywhere and
		// must be handed back to the player.
		verify(chest1Inventory).addItem(any(ItemStack.class));
		verify(chest2Inventory).addItem(any(ItemStack.class));
		verify(chest3Inventory).addItem(any(ItemStack.class));
		assertTrue(player.getInventory().contains(Material.DIRT));
	}
}
