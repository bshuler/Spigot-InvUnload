package de.jeffclan.InvUnload;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

public class UpdateChecker {

	private InvUnload plugin;

	// Package-visible (not final) so tests can point this at a local,
	// disposable HTTP endpoint instead of the real (permanently dead,
	// see CLAUDE.md) api.jeff-media.de host.
	String latestVersionLink = "https://api.jeff-media.de/invunload/invunload-latest-version.txt";

	String downloadLink = "https://www.spigotmc.org/resources/1-13-invunload-beta.60095/";
	private String currentVersion = "undefined";
	private String latestVersion = "undefined";
	public UpdateChecker(InvUnload plugin) {
		this.plugin = plugin;
	}

	public void checkForUpdate() {

		plugin.getLogger().info("Checking for available updates...");

		try {
			handleLatestVersionLine(fetchLatestVersionLine());
		} catch (Exception e) {
			plugin.getLogger().warning("Could not check for updates.");
		}

	}

	// The only piece of this class that does real network I/O. Kept as a
	// tiny, separate method so the version-comparison logic below it can be
	// unit-tested without depending on either the real (dead) endpoint or a
	// mocking framework for java.net - see PLAN.md.
	String fetchLatestVersionLine() throws IOException {
		HttpURLConnection httpcon = (HttpURLConnection) new URL(latestVersionLink).openConnection();
		httpcon.addRequestProperty("User-Agent", "Mozilla/4.76");
		// Bug found while writing tests for this class (see PLAN.md): neither
		// timeout was set, so a slow/unresponsive endpoint could hang this
		// call indefinitely. That matters because checkForUpdate() runs
		// either on the main server thread via a scheduled repeating task
		// (check-for-updates: true) or synchronously inside onEnable()
		// (check-for-updates: on-startup), where an indefinite hang would
		// stall server startup entirely. Bounded to 5s each so a bad
		// endpoint fails fast instead of hanging the server.
		httpcon.setConnectTimeout(5000);
		httpcon.setReadTimeout(5000);

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(httpcon.getInputStream()))) {
			return reader.readLine().trim();
		}
	}

	// Package-visible pure logic: given a fetched "latest version" line,
	// updates state and logs the comparison. Split out of checkForUpdate()
	// so it is directly unit-testable.
	void handleLatestVersionLine(String inputLine) {
		latestVersion = inputLine;
		currentVersion = plugin.getDescription().getVersion().trim();

		if (latestVersion.equals(currentVersion)) {
			plugin.getLogger().info("You are using the latest version of InvUnload.");
		} else {
			plugin.getLogger().warning("========================================================");
			plugin.getLogger().warning("There is a new version of InvUnload available!");
			plugin.getLogger().warning("Latest : " + inputLine);
			plugin.getLogger().warning("Current: " + currentVersion);
			plugin.getLogger().warning("Please update to the newest version. Download:");
			plugin.getLogger().warning(downloadLink);
			plugin.getLogger().warning("========================================================");
		}
	}

	public void sendUpdateMessage(Player p) {
		if (!latestVersion.equals("undefined")) {
			if (!currentVersion.equals(latestVersion)) {
				p.sendMessage(ChatColor.GRAY + "There is a new version of " + ChatColor.GOLD + "InvUnload"
						+ ChatColor.GRAY + " available.");
				p.sendMessage(ChatColor.GRAY + "Please download at " + downloadLink);
			}
		}
	}

}
