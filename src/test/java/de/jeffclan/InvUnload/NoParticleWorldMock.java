package de.jeffclan.InvUnload;

import org.mockbukkit.mockbukkit.world.WorldMock;
import org.bukkit.Location;
import org.bukkit.Particle;

/**
 * MockBukkit 3.133.2's WorldMock does not implement the
 * spawnParticle(Particle, Location, int) overload that InvUnload uses for
 * its purely cosmetic "poof" effect after a successful unload/dump - calling
 * it throws org.mockbukkit.mockbukkit.exception.UnimplementedOperationException, which
 * JUnit reports as a skipped (not failed) test, silently truncating the real
 * production code path we actually want exercised (the particle call is the
 * very last statement in each success branch).
 *
 * This test-only subclass no-ops just that one overload so the rest of
 * InvUnload's real, unmodified production logic - permission checks, chest
 * scanning, item movement, DoubleChest handling - keeps running for real up
 * to and including the line that decides particles should be spawned.
 */
class NoParticleWorldMock extends WorldMock {

	@Override
	public void spawnParticle(Particle particle, Location location, int count) {
		// intentionally no-op - see class Javadoc
	}
}
