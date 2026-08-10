package de.jeffclan.InvUnload;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import com.sk89q.worldguard.WorldGuard;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class InvUnload extends JavaPlugin implements CommandExecutor, Listener {

	private static final int currentConfigVersion = 2;

	public static int freeSlotsInChest(Block block) {

		int freeSlots = 0;

		if (block.getState() instanceof Chest) {
			Inventory inv = ((Chest) block.getState()).getInventory();

			for (ItemStack itemStack : inv.getContents()) {
				// Bug found while writing tests for this method (see PLAN.md):
				// this used to also check `itemStack.getType().equals(Material.AIR)`
				// as a third, separate condition. Same root cause as the dead
				// AIR-type check removed from isItemStackEmpty() above -
				// Bukkit/vanilla always normalize AIR-type ItemStacks to
				// amount 0, so `getAmount() == 0` already catches every AIR
				// slot; the type check's true outcome could never be reached
				// by any input and was removed rather than excluded from
				// coverage.
				if (itemStack == null || itemStack.getAmount() == 0) {
					freeSlots++;
				}
			}

		}

		return freeSlots;
	}

	public static List<Material> getInventoryContents(Inventory inventory) {

		List<Material> contents = new ArrayList<Material>();
		for (ItemStack itemStack : inventory.getContents()) {
			if (isItemStackEmpty(itemStack))
				continue;
			if (!contents.contains(itemStack.getType())) {
				contents.add(itemStack.getType());
			}
		}

		return contents;
	}

	public static boolean isItemStackEmpty(ItemStack itemStack) {
		if (itemStack == null)
			return true;
		if (itemStack.getAmount() == 0)
			return true;
		// Bug found while writing tests for this method (see PLAN.md): there
		// used to also be a separate `if (itemStack.getType() == Material.AIR)
		// return true;` check here. Bukkit/vanilla Minecraft normalize any
		// ItemStack of type AIR to amount 0 (there is no such thing as a
		// non-empty AIR stack - confirmed empirically, both via MockBukkit and
		// consistent with vanilla's ItemStack.EMPTY semantics), so the
		// `getAmount() == 0` check above always catches every AIR stack
		// first. The AIR-type check could never be reached as true by any
		// input and was removed rather than excluded from coverage.
		return false;
	}

	boolean usingMatchingConfig = true;
	/*
	 * Whoever has to read this code: I know, it is not organized very well, but it
	 * runs extremely fast. We are using some magic to do stuff easier than I
	 * actually planned so if you want to organize it, feel free to do so
	 */

	UpdateChecker updateChecker;

	private int defaultChestRadius = 10;

	private int maxChestRadius = 20;

	private int particleCount = 100;
	// Bukkit renamed these Particle enum constants (~1.20.5 particle
	// cleanup): SPELL_WITCH -> WITCH, SPELL -> EFFECT. A hardcoded
	// `Particle.WITCH` reference only compiles against post-rename
	// paper-api coordinates and fails outright (cannot find symbol)
	// against 1.18.2/1.19.4/1.20.1 - confirmed by an actual failing build
	// during the backward version walk, not assumed. resolveParticle()
	// does the same Particle.valueOf(String) runtime lookup EpicFurnaces
	// already uses for its config-driven particle type, just with a
	// fixed new-name-then-old-name fallback instead of a config value,
	// so this one source tree keeps compiling and behaving correctly
	// across the whole version matrix. See CLAUDE.md.
	private Particle particleType = resolveParticle("WITCH", "SPELL_WITCH");

	private Particle particleTypeDump = resolveParticle("EFFECT", "SPELL");

	// Package-visible (not private) so InvUnloadTest can exercise the
	// fallback and failure branches directly with made-up candidate names,
	// independent of which real Particle enum constants exist on whatever
	// paper-api version the test happens to run against.
	static Particle resolveParticle(String... candidateNames) {
		for (String name : candidateNames) {
			try {
				return Particle.valueOf(name);
			} catch (IllegalArgumentException ignored) {
				// try the next candidate name
			}
		}
		throw new IllegalStateException("None of the known Particle enum names were found: " + String.join(", ", candidateNames));
	}

	private Messages messages;

	private int updateCheckInterval = 86400;

	protected WorldGuard worldGuard;

	// Checks permission for chest usage. The actual WorldGuard class-presence
	// checks and live region-query calls live behind WorldGuardHook - see
	// that class and PLAN.md for why.
	protected boolean canUseChestHere(Player player, Location loc) {
		return WorldGuardHook.canUseChestHere(getServer().getPluginManager(),
				getConfig().getBoolean("use-worldguard"), player, loc);
	}

	// Package-visible (not private) so InvUnloadTest can drive the
	// double-chest branches directly without going through a full
	// /dump command dispatch.
	void dumpInventory(Player p, int radius) {
		ArrayList<ChestSpaceCombination> chestSpaceCombinations = new ArrayList<ChestSpaceCombination>();
		List<Block> whereToSpawnParticles = new ArrayList<Block>();

		if (getNearbyChests(p.getLocation(), radius, p).size() == 0) {
			p.sendMessage(messages.MSG_NO_CHESTS_NEARBY);
			return;
		}

		for (Block block : getNearbyChests(p.getLocation(), radius, p)) {
			InventoryHolder holder = ((Chest) block.getState()).getInventory().getHolder();
			if (holder instanceof DoubleChest) {
				DoubleChest doubleChest = ((DoubleChest) holder);
				Chest leftChest = (Chest) doubleChest.getLeftSide();
				// Chest rightChest = (Chest) doubleChest.getRightSide();
				int slotsFreeInThisChest = freeSlotsInChest(leftChest.getBlock());
				chestSpaceCombinations.add(new ChestSpaceCombination(slotsFreeInThisChest, leftChest.getBlock(),
						leftChest.getBlock().hashCode()));
			} else {
				chestSpaceCombinations.add(new ChestSpaceCombination(freeSlotsInChest(block), block, block.hashCode()));
			}
		}

		Collections.sort(chestSpaceCombinations, Collections.reverseOrder());

		ArrayList<ItemStack> playerStuffList = new ArrayList<ItemStack>();
		for (ItemStack is : p.getInventory().getStorageContents()) {
			playerStuffList.add(is);
		}
		for (int i = 0; i < 9; i++) { // iterate through hotbar
			playerStuffList.remove(p.getInventory().getItem(i));
		}
		ItemStack[] playerStuff = playerStuffList.toArray(new ItemStack[playerStuffList.size()]);

		for (ItemStack itemStack : playerStuff) {
			if (isItemStackEmpty(itemStack))
				continue;

			ArrayList<Block> chestInventories = new ArrayList<Block>();
			for (ChestSpaceCombination comb : chestSpaceCombinations) {
				chestInventories.add(comb.block);
			}

			p.getInventory().remove(itemStack);
			int amount = itemStack.getAmount();
			Inventory firstChestInventory = ((InventoryHolder) chestInventories.get(0).getState())
					.getInventory();
			HashMap<Integer, ItemStack> unstorable = addItemsToChestInventory(firstChestInventory, itemStack);

			if (unstorable.size() == 0 || unstorable.get(0).getAmount() != amount) {

				InventoryHolder holder = ((Chest) chestInventories.get(0).getState()).getInventory().getHolder();
				if (holder instanceof DoubleChest) {
					DoubleChest doubleChest = ((DoubleChest) holder);
					Chest leftChest = (Chest) doubleChest.getLeftSide();
					Chest rightChest = (Chest) doubleChest.getRightSide();
					if (!whereToSpawnParticles.contains(rightChest.getBlock())) {
						whereToSpawnParticles.add(rightChest.getBlock());
					}
					if (!whereToSpawnParticles.contains(leftChest.getBlock())) {
						whereToSpawnParticles.add(leftChest.getBlock());
					}
				}

				if (!whereToSpawnParticles.contains(chestInventories.get(0))) {
					whereToSpawnParticles.add(chestInventories.get(0));
				}
			}

			chestInventories.remove(0);

			while (unstorable.size() > 0 && chestInventories.size() > 0) {
				int amount1 = unstorable.get(0).getAmount();
				Inventory remainingChestInventory = ((InventoryHolder) chestInventories.get(0).getState()).getInventory();
				unstorable = addItemsToChestInventory(remainingChestInventory, unstorable.get(0));
				if (unstorable.size() == 0 || unstorable.get(0).getAmount() != amount1) {

					InventoryHolder holder = ((Chest) chestInventories.get(0).getState()).getInventory().getHolder();
					if (holder instanceof DoubleChest) {
						DoubleChest doubleChest = ((DoubleChest) holder);
						Chest leftChest = (Chest) doubleChest.getLeftSide();
						Chest rightChest = (Chest) doubleChest.getRightSide();
						if (!whereToSpawnParticles.contains(rightChest.getBlock())) {
							whereToSpawnParticles.add(rightChest.getBlock());
						}
						if (!whereToSpawnParticles.contains(leftChest.getBlock())) {
							whereToSpawnParticles.add(leftChest.getBlock());
						}
					}

					if (!whereToSpawnParticles.contains(chestInventories.get(0))) {
						whereToSpawnParticles.add(chestInventories.get(0));
					}
				}

				// Bug found while writing tests for this method (see PLAN.md):
				// this loop never removed the chest it had just tried, so
				// chestInventories.get(0) kept referring to the SAME chest on
				// every iteration. If that chest could not store the item (a
				// completely full chest, or one that only ever partially
				// empties), unstorable.size() never reached 0 and
				// chestInventories.size() never changed, so the while
				// condition below never became false - a genuine infinite
				// loop that would hang the server's main thread on a real
				// /dump near two or more full/near-full chests. Removing the
				// just-tried chest here guarantees chestInventories shrinks
				// by exactly one on every iteration, so the loop is always
				// bounded by the number of candidate chests.
				chestInventories.remove(0);
			}
			if (unstorable.size() > 0) {
				p.getInventory().addItem(unstorable.get(0));
			}

		}

		if (whereToSpawnParticles.size() > 0) {
			for (Block block : whereToSpawnParticles) {
				World world = block.getLocation().getWorld();
				world.spawnParticle(particleTypeDump, block.getLocation().add(0.5, 0.75, 0.5), particleCount);
				// System.out.println("Spawning particles for dump");
			}
		}

	}

	private HashMap<Integer, ItemStack> addItemsToChestInventory(Inventory inventory,
			ItemStack itemStack) {
		HashMap<Integer, ItemStack> unstorableItems = inventory.addItem(itemStack);
		trySort(inventory);
		return unstorableItems;
	}

	// ChestSort integration via reflection, no compile-time dependency.
	// The upstream code imported de.jeffclan.JeffChestSort.JeffChestSortPlugin
	// directly and depended on a system-scoped lib/ChestSort.jar that was
	// never committed anywhere (upstream's own .gitignore excludes /lib/) -
	// that build has never actually compiled standalone. Reflection removes
	// the dependency entirely: if ChestSort isn't installed, or its API
	// doesn't match, this silently no-ops, exactly like the WorldGuard
	// Class.forName checks elsewhere in this file.
	private void trySort(Inventory inventory) {
		org.bukkit.plugin.Plugin chestSort = getServer().getPluginManager().getPlugin("ChestSort");

		if (chestSort == null) {
			return;
		}

		try {
			Method sortInventory = chestSort.getClass().getMethod("sortInventory", Inventory.class);
			sortInventory.invoke(chestSort, inventory);
		} catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
			getLogger().warning("Found a plugin named ChestSort, but could not call its sortInventory(Inventory) method: " + e.getMessage());
		}
	}

	public List<Block> getNearbyChests(Location location, int radius, Player player) {
		List<Block> blocks = new ArrayList<Block>();
		for (int x = location.getBlockX() - radius; x <= location.getBlockX() + radius; x++) {
			for (int y = location.getBlockY() - radius; y <= location.getBlockY() + radius; y++) {
				for (int z = location.getBlockZ() - radius; z <= location.getBlockZ() + radius; z++) {
					if (location.getWorld().getBlockAt(x, y, z).getState() instanceof Chest) {
						if (canUseChestHere(player, location.getWorld().getBlockAt(x, y, z).getLocation())) {
							blocks.add(location.getWorld().getBlockAt(x, y, z));
						}
					}
				}
			}
		}
		return blocks;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command arg1, String arg2, String[] arg3) {
		if (!(sender instanceof Player)) {
			return true;
		}

		Player p = (Player) sender;

		if (arg1.getName().equalsIgnoreCase("unload")) {

			int radius = defaultChestRadius;
			if (arg3.length > 0) {
				if (!Utils.isInteger(arg3[0])) {
					p.sendMessage(String.format(messages.MSG_NOT_A_NUMBER, arg3[0]));
					return true;
				} else {
					radius = Integer.parseInt(arg3[0]);
					if (radius > maxChestRadius) {
						p.sendMessage(messages.MSG_RADIUS_TOO_HIGH);
						return true;
					}
				}
			}
			unloadInventory(p, radius, true);
			return true;
		} else if (arg1.getName().equalsIgnoreCase("dump")) {

			int radius = defaultChestRadius;
			if (arg3.length > 0) {
				if (!Utils.isInteger(arg3[0])) {
					p.sendMessage(String.format(messages.MSG_NOT_A_NUMBER, arg3[0]));
					return true;
				} else {
					radius = Integer.parseInt(arg3[0]);
					if (radius > maxChestRadius) {
						p.sendMessage(messages.MSG_RADIUS_TOO_HIGH);
						return true;
					}
				}
			}
			if (getConfig().getBoolean("unload-before-dumping") && p.hasPermission("invunload.unload")) {

				unloadInventory(p, radius, false);
			}
			dumpInventory(p, radius);
			return true;
		}

		return false;
	}

	@Override
	public void onEnable() {

		saveDefaultConfig();

		worldGuard = null;

		if (getConfig().getBoolean("use-worldguard")) {
			worldGuard = WorldGuardHook.tryHook(getLogger());
		}

		// Config version prior to 1? Then it must have been generated by InvUnload 0.x
		if (getConfig().getInt("config-version", 0) < 1) {
			getLogger().warning("========================================================");
			getLogger().warning("You are using a config file that has been generated");
			getLogger().warning("prior to InvUnload 1.0.0.");
			getLogger().warning("To allow everyone to use the new features, your config");
			getLogger().warning("has been renamed to config.old.yml and a new one has");
			getLogger().warning("been generated. Please examine the new config file to");
			getLogger().warning("see the new possibilities and adjust your settings.");
			getLogger().warning("========================================================");

			File configFile = new File(getDataFolder().getAbsolutePath() + File.separator + "config.yml");
			File oldConfigFile = new File(getDataFolder().getAbsolutePath() + File.separator + "config.old.yml");
			if (oldConfigFile.getAbsoluteFile().exists()) {
				oldConfigFile.getAbsoluteFile().delete();
			}
			configFile.getAbsoluteFile().renameTo(oldConfigFile.getAbsoluteFile());
			saveDefaultConfig();
			try {
				getConfig().load(configFile.getAbsoluteFile());
			} catch (IOException | InvalidConfigurationException e) {
				getLogger().warning("Could not load freshly generated config file!");
				e.printStackTrace();
			}
		} else if (getConfig().getInt("config-version", 0) != currentConfigVersion) {
			getLogger().warning("========================================================");
			getLogger().warning("YOU ARE USING AN OLD CONFIG FILE!");
			getLogger().warning("This is not a problem, as InvUnload will just use the");
			getLogger().warning("default settings for unset values. However, if you want");
			getLogger().warning("to configure the new options, please go to");
			getLogger().warning("https://www.spigotmc.org/resources/1-13-invunload-beta.60095/");
			getLogger().warning("and replace your config.yml with the new one. You can");
			getLogger().warning("then insert your old changes into the new file.");
			getLogger().warning("========================================================");
			usingMatchingConfig = false;
		}

		getCommand("unload").setExecutor(this);
		getCommand("dump").setExecutor(this);
		getServer().getPluginManager().registerEvents(this, this);
		messages = new Messages(this);

		// bStats metrics intentionally removed here - see CLAUDE.md
		// ("Removed / replaced dependencies"). The old vendored classic
		// Metrics.java never needed a registered bStats.org plugin ID; the
		// modern org.bstats:bstats-bukkit API requires one, and fabricating
		// an ID with no real dashboard behind it would misattribute
		// telemetry. Re-add with a real ID from bstats.org if Bert wants
		// metrics back.

		getConfig().addDefault("unload-before-dumping", true);
		getConfig().addDefault("default-chest-radius", 10);
		getConfig().addDefault("max-chest-radius", 20);
		getConfig().addDefault("config-version", 0);
		// Default flipped from "true" to "false": the original update-check
		// endpoint (api.jeff-media.de) no longer resolves at all - see
		// CLAUDE.md. Left configurable rather than removed.
		getConfig().addDefault("check-for-updates", "false");
		getConfig().addDefault("use-worldguard", false);

		updateChecker = new UpdateChecker(this);

		if (getConfig().getString("check-for-updates", "false").equalsIgnoreCase("true")) {
			getLogger().warning("check-for-updates is enabled, but the update-check endpoint (api.jeff-media.de) is known to be dead - this will just log a failure every check interval.");
			Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
				public void run() {
					updateChecker.checkForUpdate();
				}
			}, 0L, updateCheckInterval * 20);
		} else if (getConfig().getString("check-for-updates", "false").equalsIgnoreCase("on-startup")) {
			getLogger().warning("check-for-updates is enabled, but the update-check endpoint (api.jeff-media.de) is known to be dead - this will just log a failure.");
			updateChecker.checkForUpdate();
		}

	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		if (event.getPlayer().isOp()) {
			updateChecker.sendUpdateMessage(event.getPlayer());
		}
	}

	public boolean unloadInventory(Player p, int radius, boolean showMessageOnFail) {

		List<Block> nearbyBlocks = getNearbyChests(p.getLocation(), radius, p);

		if (nearbyBlocks.size() == 0) {
			if (showMessageOnFail)
				p.sendMessage(messages.MSG_NO_CHESTS_NEARBY);
			return false;
		}

		List<Block> whereToSpawnParticles = new ArrayList<Block>();
		Inventory playerInventory = p.getInventory();

		for (Block block : nearbyBlocks) {

			Inventory chestInventory = ((Chest) block.getState()).getInventory();

			// iterate through all items in the player's inventory

			for (ItemStack itemStack : playerInventory.getStorageContents()) {

				// discard empty stacks
				if (isItemStackEmpty(itemStack))
					continue;

				// only continue if the inventory already contains what we want to store
				if (getInventoryContents(chestInventory).contains(itemStack.getType())) {

					playerInventory.remove(itemStack);

					// try to add it to the chest
					HashMap<Integer, ItemStack> unstorableItemStacks = addItemsToChestInventory(chestInventory, itemStack);

					if (unstorableItemStacks.size() == 0
							|| unstorableItemStacks.get(0).getAmount() != itemStack.getAmount()) {
						InventoryHolder holder = ((Chest) block.getState()).getInventory().getHolder();
						if (holder instanceof DoubleChest) {
							DoubleChest doubleChest = ((DoubleChest) holder);
							Chest leftChest = (Chest) doubleChest.getLeftSide();
							Chest rightChest = (Chest) doubleChest.getRightSide();
							if (!whereToSpawnParticles.contains(leftChest.getBlock())) {
								whereToSpawnParticles.add(leftChest.getBlock());
							}
							if (!whereToSpawnParticles.contains(rightChest.getBlock())) {
								whereToSpawnParticles.add(rightChest.getBlock());
							}
						} else if (!whereToSpawnParticles.contains(block)) {

							whereToSpawnParticles.add(block);

						}
					}

					// give the player all items back that could not be stored because chest is full
					for (ItemStack unstorableItemStack : unstorableItemStacks.values()) {
						// System.out.println(" could not store " + unstorableItemStack.getAmount() + "x
						// "
						// + unstorableItemStack.getType().name());
						playerInventory.addItem(unstorableItemStack);
					}
				}
			}
		}

		if (whereToSpawnParticles.size() > 0) {
			for (Block block : whereToSpawnParticles) {
				World world = block.getLocation().getWorld();
				world.spawnParticle(particleType, block.getLocation().add(0.5, 0.75, 0.5), particleCount);
			}
			return true;
		} else {
			if (showMessageOnFail)
				p.sendMessage(messages.MSG_COULD_NOT_UNLOAD);
			return false;
		}

	}
}
