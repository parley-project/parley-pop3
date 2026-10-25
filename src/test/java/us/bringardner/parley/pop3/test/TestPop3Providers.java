package us.bringardner.parley.pop3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.net.server.LaunchSettings;
import us.bringardner.parley.net.server.ServerContext;
import us.bringardner.parley.net.server.ServerLauncher;

/**
 * The POP3 providers: found by the launcher, settings from parley-mail.properties then
 * parley-pop3.properties, the suite's user file (parley.mail.users), pop3s off unless its port is set.
 */
public class TestPop3Providers {

	/** Settings other tests in this JVM set that would change what these see */
	private static final String[] CLEARED = { "parley.mail.pop3.authenticationProvider", "parley.mail.pop3.userFile",
			"parley.mail.pop3.port", "parley.mail.pop3s.port", "parley.mail.pop3.secure", "parley.mail.users", "parley.mail.root",
			"parley.mail.pop3.keyStoreName" };

	@TempDir
	File dir;

	private final Map<String, String> saved = new HashMap<>();
	private ServerLauncher launcher;

	@BeforeEach
	public void clear() {
		for (String n : CLEARED) {
			saved.put(n, System.getProperty(n));
			System.clearProperty(n);
		}
	}

	@AfterEach
	public void restore() {
		if (launcher != null) {
			launcher.close();
		}
		for (String n : CLEARED) {
			System.clearProperty(n);
			if (saved.get(n) != null) {
				System.setProperty(n, saved.get(n));
			}
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}

	private void write(String name, String... lines) throws Exception {
		Files.write(new File(dir, name).toPath(), (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void providersFound() {
		assertTrue(ServerLauncher.providers().keySet().containsAll(Arrays.asList("pop3", "pop3s")), ServerLauncher.providers().keySet().toString());
	}

	@Test
	public void runsWithTheSuiteSettings() throws Exception {
		int port = freePort();
		File mail = new File(dir, "mail");
		write("users.txt", "tony , secret , READ|WRITE");
		write("parley-mail.properties", "parley.mail.root=" + mail.getPath(), "parley.mail.users=" + new File(dir, "users.txt").getPath(),
				"parley.mail.pop3.port=1");
		write("parley-pop3.properties", "parley.mail.pop3.port=" + port); // wins over the shared file
		launcher = new ServerLauncher("Pop3Test", new LaunchSettings(new Properties()), Arrays.asList("pop3", "pop3s"), dir, new ServerContext());
		launcher.init();
		assertEquals(ServerLauncher.State.OFF, launcher.getState("pop3s"), "no pop3s port: off");
		launcher.startAll();
		assertEquals(ServerLauncher.State.RUNNING, launcher.getState("pop3"));
		assertEquals(port, launcher.getServer("pop3").getPort());

		try (Socket s = new Socket("127.0.0.1", port)) {
			BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
			OutputStream out = s.getOutputStream();
			assertTrue(in.readLine().startsWith("+OK"));
			out.write("USER tony\r\n".getBytes(StandardCharsets.US_ASCII));
			assertTrue(in.readLine().startsWith("+OK"));
			out.write("PASS secret\r\n".getBytes(StandardCharsets.US_ASCII));
			String reply = in.readLine();
			assertTrue(reply.startsWith("+OK"), "logged in with parley.mail.users: " + reply);
			out.write("QUIT\r\n".getBytes(StandardCharsets.US_ASCII));
			in.readLine();
		}
	}

	@Test
	public void legacySecureTurnsOnPop3sOnly() throws Exception {
		System.setProperty("parley.mail.pop3.secure", "true");
		us.bringardner.parley.net.server.ServerProvider pop3 = ServerLauncher.providers().get("pop3");
		us.bringardner.parley.net.server.ServerProvider pop3s = ServerLauncher.providers().get("pop3s");
		assertTrue(!pop3.enabled());
		assertTrue(pop3s.enabled());
	}
}
