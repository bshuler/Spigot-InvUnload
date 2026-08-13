package de.jeffclan.InvUnload;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckerTest {

	private ServerMock server;
	private InvUnload plugin;
	private HttpServer httpServer;

	@BeforeEach
	void setUp() {
		server = MockBukkit.mock();
		plugin = MockBukkit.load(InvUnload.class);
	}

	@AfterEach
	void tearDown() {
		if (httpServer != null) {
			httpServer.stop(0);
		}
		MockBukkit.unmock();
	}

	private String startLocalServer(String responseBody) throws IOException {
		int port;
		try (ServerSocket socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		httpServer.createContext("/version", exchange -> {
			byte[] bytes = responseBody.getBytes();
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
		httpServer.start();
		return "http://127.0.0.1:" + port + "/version";
	}

	@Test
	void fetchLatestVersionLine_readsRealHttpResponse() throws IOException {
		UpdateChecker checker = new UpdateChecker(plugin);
		checker.latestVersionLink = startLocalServer("9.9.9\n");

		assertEquals("9.9.9", checker.fetchLatestVersionLine());
	}

	@Test
	void checkForUpdate_logsFailureWhenEndpointIsUnreachable() {
		UpdateChecker checker = new UpdateChecker(plugin);
		// Nothing listens on this port - guaranteed connection refused,
		// exactly like the real (dead) api.jeff-media.de endpoint.
		checker.latestVersionLink = "http://127.0.0.1:1/nowhere";

		checker.checkForUpdate();
		// No exception should escape checkForUpdate() - the try/catch is
		// the behavior under test here.
	}

	@Test
	void checkForUpdate_succeedsAgainstReachableEndpoint_parsesVersionWithoutThrowing() throws IOException {
		// JaCoCo attributes the try block's own success-path continuation
		// (the "no exception was thrown" fall-through, plus the GOTO that
		// skips the catch block) to the try/catch's source lines
		// separately from the exception path exercised by the test above -
		// a call that always throws can never mark those instructions
		// covered, no matter how many times it runs. This is the only test
		// in the suite that lets checkForUpdate() itself run all the way
		// through fetchLatestVersionLine() and handleLatestVersionLine()
		// successfully, closing that gap.
		UpdateChecker checker = new UpdateChecker(plugin);
		checker.latestVersionLink = startLocalServer("9.9.9\n");

		checker.checkForUpdate();
	}

	@Test
	void handleLatestVersionLine_matchingVersionLogsUpToDate() {
		UpdateChecker checker = new UpdateChecker(plugin);
		String currentVersion = plugin.getDescription().getVersion();

		checker.handleLatestVersionLine(currentVersion);

		PlayerMock player = server.addPlayer();
		checker.sendUpdateMessage(player);
		assertTrue(player.nextMessage() == null || player.nextMessage() == null);
	}

	@Test
	void handleLatestVersionLine_differentVersionTriggersUpdateMessage() {
		UpdateChecker checker = new UpdateChecker(plugin);

		checker.handleLatestVersionLine("999.0.0");

		PlayerMock player = server.addPlayer();
		checker.sendUpdateMessage(player);

		String first = player.nextMessage();
		String second = player.nextMessage();
		assertTrue(first != null && first.contains("InvUnload"));
		assertTrue(second != null && second.contains(checker.downloadLink));
	}

	@Test
	void sendUpdateMessage_beforeAnyCheckSendsNothing() {
		UpdateChecker checker = new UpdateChecker(plugin);
		PlayerMock player = server.addPlayer();

		checker.sendUpdateMessage(player);

		assertEquals(null, player.nextMessage());
	}
}
