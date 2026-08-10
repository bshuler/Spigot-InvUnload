package de.jeffclan.InvUnload;

import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Test-only stand-in for the real ChestSort plugin. InvUnload's
 * {@code trySort(Inventory)} soft-depends on ChestSort purely by name and
 * reflection (no compile-time dependency), looking up a plugin literally
 * named "ChestSort" and invoking its public {@code sortInventory(Inventory)}
 * method via {@code Method.invoke}. This fixture is registered into the
 * MockBukkit server under that exact name so the reflective-success branch
 * of {@code trySort} can be exercised for real, without depending on the
 * actual third-party ChestSort jar (which, per this repo's own history, was
 * never even resolvable from a build machine - see CLAUDE.md).
 */
public class FakeChestSortPlugin extends JavaPlugin {

	private Inventory lastSorted;
	private int sortInventoryCallCount;

	public void sortInventory(Inventory inventory) {
		lastSorted = inventory;
		sortInventoryCallCount++;
	}

	public Inventory getLastSorted() {
		return lastSorted;
	}

	public int getSortInventoryCallCount() {
		return sortInventoryCallCount;
	}
}
