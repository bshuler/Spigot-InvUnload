package de.jeffclan.InvUnload;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.bukkit.ChatColor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {

	private InvUnload plugin;

	@BeforeEach
	void setUp() {
		MockBukkit.mock();
		plugin = MockBukkit.load(InvUnload.class);
	}

	@AfterEach
	void tearDown() {
		MockBukkit.unmock();
	}

	@Test
	void loadsDefaultMessagesFromConfigWithColorTranslationAndPrefix() {
		Messages messages = new Messages(plugin);

		String expectedPrefix = ChatColor.GOLD + "[InvUnload] " + ChatColor.RESET;
		assertEquals(expectedPrefix, messages.PREFIX);

		assertTrue(messages.MSG_COULD_NOT_UNLOAD.startsWith(expectedPrefix));
		assertTrue(messages.MSG_COULD_NOT_UNLOAD.contains(ChatColor.GRAY.toString()));
		assertTrue(messages.MSG_COULD_NOT_UNLOAD.contains("nothing to unload"));

		assertTrue(messages.MSG_RADIUS_TOO_HIGH.contains("20"));
		assertTrue(messages.MSG_RADIUS_TOO_HIGH.contains(ChatColor.RED.toString()));

		assertTrue(messages.MSG_NOT_A_NUMBER.contains(ChatColor.RED.toString()));

		assertTrue(messages.MSG_NO_CHESTS_NEARBY.contains("no chests nearby"));
	}

	@Test
	void honorsCustomizedConfigStrings() {
		plugin.getConfig().set("message-could-not-unload", "&aCustom unload message");
		plugin.getConfig().set("max-chest-radius", 5);
		plugin.getConfig().set("message-radius-too-high", "Max is %d blocks");

		Messages messages = new Messages(plugin);

		assertTrue(messages.MSG_COULD_NOT_UNLOAD.contains("Custom unload message"));
		assertTrue(messages.MSG_COULD_NOT_UNLOAD.contains(ChatColor.GREEN.toString()));
		assertTrue(messages.MSG_RADIUS_TOO_HIGH.contains("Max is 5 blocks"));
	}
}
