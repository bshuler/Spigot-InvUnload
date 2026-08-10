package de.jeffclan.InvUnload;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Test-only stand-in for a "ChestSort" plugin that, unlike the real one,
 * does NOT expose a {@code sortInventory(Inventory)} method - exercises the
 * {@code NoSuchMethodException} branch of InvUnload's reflective
 * {@code trySort(Inventory)} lookup, which is caught and logged as a
 * warning rather than propagated.
 */
public class FakeChestSortMissingMethodPlugin extends JavaPlugin {
}
