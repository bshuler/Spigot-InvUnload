package de.jeffclan.InvUnload;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Test-only subclass that lets tests inject a canned chest-scan result
 * instead of relying on MockBukkit's real world block-scanning (MockBukkit
 * has no support at all for merged DoubleChest blocks, so tests that need to
 * exercise InvUnload's DoubleChest-handling branches cannot get there via a
 * real world scan). When {@link #canned} is left null, this defers to the
 * real production {@code getNearbyChests} implementation so the same
 * plugin instance can also be used for tests that want the genuine
 * radius-scan behavior.
 */
public class TestableInvUnload extends InvUnload {

	List<Block> canned;

	// When true, canUseChestHere() always returns false, regardless of what
	// WorldGuardHook would say. The real WorldGuardHook.canUseChestHere()
	// always returns true in this test suite (WorldGuard isn't on the test
	// classpath, so the ClassNotFoundException short-circuit always fires -
	// see WorldGuardHook's own Javadoc on why the actual "WorldGuard denies
	// access" path is excluded from coverage), so this flag is the only way
	// to exercise getNearbyChests()'s "chest is here, but access is denied"
	// branch without a running WorldGuard platform.
	boolean denyChestAccess = false;

	// How many times unloadInventory() has actually run. Mockito spying on
	// InvUnload itself does not work here (Mockito's inline mock maker
	// needs to instrument the whole class hierarchy, including the
	// worldGuard field's declared type com.sk89q.worldguard.WorldGuard,
	// which - by design, see WorldGuardHook - is compileOnly and absent
	// from the test runtime classpath, so byte-buddy fails with a
	// NoClassDefFoundError trying to redefine it), so this counter is the
	// only way to prove from a test that a config-gated call site did or
	// did not actually invoke unloadInventory().
	int unloadInventoryCallCount = 0;

	@Override
	public boolean unloadInventory(Player p, int radius, boolean showMessageOnFail) {
		unloadInventoryCallCount++;
		return super.unloadInventory(p, radius, showMessageOnFail);
	}

	@Override
	public List<Block> getNearbyChests(Location location, int radius, Player player) {
		if (canned != null) {
			return canned;
		}
		return super.getNearbyChests(location, radius, player);
	}

	@Override
	protected boolean canUseChestHere(Player player, Location loc) {
		if (denyChestAccess) {
			return false;
		}
		return super.canUseChestHere(player, loc);
	}
}
