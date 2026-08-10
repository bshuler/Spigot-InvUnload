package de.jeffclan.InvUnload;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.logging.Logger;

/**
 * Isolates every call InvUnload makes into the live WorldGuard API: the
 * optional-dependency class-presence checks, the plugin lookup, and the
 * actual region-protection query.
 *
 * Excluded from JaCoCo coverage verification (see PLAN.md): exercising the
 * "WorldGuard is present and actively denying access" path requires a
 * running WorldGuard platform (a real RegionContainer / ProtectionQuery
 * engine backed by loaded regions) that cannot be constructed in a unit
 * test without a real WorldGuard plugin instance - MockBukkit has no
 * WorldGuard equivalent. The "WorldGuard classes entirely absent from the
 * classpath" path (the default, softdepend-not-installed case) genuinely
 * is exercised by the test suite, since worldguard-bukkit/worldedit-bukkit
 * are compileOnly for the main plugin and deliberately not added to the
 * test classpath - that is the realistic default deployment.
 */
final class WorldGuardHook {

	private WorldGuardHook() {
	}

	/**
	 * Mirrors the original canUseChestHere() logic exactly, just moved
	 * behind this seam so InvUnload's own class can stay at 100% covered
	 * with a one-line delegation.
	 */
	static boolean canUseChestHere(PluginManager pluginManager, boolean useWorldGuardConfigured, Player player,
			Location loc) {
		try {
			Class.forName("com.sk89q.worldguard.bukkit.WorldGuardPlugin");
			Class.forName("com.sk89q.worldguard.WorldGuard");
		} catch (ClassNotFoundException e) {
			return true;
		}

		WorldGuardPlugin worldGuard = getWorldGuardPlugin(pluginManager);

		if (WorldGuard.getInstance() == null || worldGuard == null || !useWorldGuardConfigured) {
			return true;
		}

		if (WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
				.queryState(BukkitAdapter.adapt(loc), worldGuard.wrapPlayer(player), Flags.USE) == StateFlag.State.DENY) {
			return false;
		}

		return WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery().queryState(
				BukkitAdapter.adapt(loc), worldGuard.wrapPlayer(player), Flags.CHEST_ACCESS) != StateFlag.State.DENY;
	}

	private static WorldGuardPlugin getWorldGuardPlugin(PluginManager pluginManager) {
		try {
			Class.forName("com.sk89q.worldguard.bukkit.WorldGuardPlugin");
		} catch (ClassNotFoundException e) {
			return null;
		}

		Plugin plugin = pluginManager.getPlugin("WorldGuard");
		if (!(plugin instanceof WorldGuardPlugin)) {
			return null;
		}
		return (WorldGuardPlugin) plugin;
	}

	/**
	 * Mirrors the original onEnable() WorldGuard-hookup block exactly, just
	 * moved behind this seam.
	 */
	static WorldGuard tryHook(Logger logger) {
		WorldGuard hooked = null;

		try {
			Class.forName("com.sk89q.worldguard.WorldGuard");
			hooked = WorldGuard.getInstance();

			if (!(hooked instanceof WorldGuard)) {
				hooked = null;
			}
		} catch (ClassNotFoundException e) {
			// WorldGuard not installed
		}

		if (hooked != null) {
			logger.info("Hooked into WorldGuard succesfully.");
			logger.info("*** Please note that WorldGuard integration is currently experimental ***");
			logger.info("*** See the config.yml file for more information ***");
		}

		return hooked;
	}
}
