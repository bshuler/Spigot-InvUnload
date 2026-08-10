package de.jeffclan.InvUnload;

import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ChestSpaceCombinationTest {

	@Test
	void constructorStoresFields() {
		Block block = mock(Block.class);
		ChestSpaceCombination combo = new ChestSpaceCombination(4, block, 42);

		assertEquals(4, combo.freeSlots);
		assertEquals(block, combo.block);
		assertEquals(42, combo.myHash);
	}

	@Test
	void compareTo_ordersByFreeSlots() {
		ChestSpaceCombination fewer = new ChestSpaceCombination(2, mock(Block.class), 1);
		ChestSpaceCombination more = new ChestSpaceCombination(9, mock(Block.class), 2);
		ChestSpaceCombination sameAsFewer = new ChestSpaceCombination(2, mock(Block.class), 3);

		assertTrue(fewer.compareTo(more) < 0);
		assertTrue(more.compareTo(fewer) > 0);
		assertEquals(0, fewer.compareTo(sameAsFewer));
	}
}
