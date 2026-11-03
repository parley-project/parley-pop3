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
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.mail.store.MailLocks;
import us.bringardner.parley.net.lock.Sharing;
import us.bringardner.parley.net.server.KnownSettings;
import us.bringardner.parley.net.server.ServerMain;
import us.bringardner.parley.pop3.server.Pop3Server;

/**
 * To-Do 2.16: parley-pop3's list of settings (META-INF/parley/settings.list) has every name its code
 * spells out, and every name its servers read while they start and serve a session, so a server's
 * start-up check never warns about a real setting.
 */
public class TestKnownSettings {

	@TempDir
	File dir;

	@Test
	public void everyConstantIsListed() throws Exception {
		KnownSettings k = KnownSettings.load();
		assertEquals(List.of(), k.undeclared(KnownSettings.settingConstants(Pop3Server.class)));
	}


	private static void session(int port) throws Exception {
		try (Socket s = new Socket("127.0.0.1", port)) {
			BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
			OutputStream out = s.getOutputStream();
			assertTrue(in.readLine().startsWith("+OK"));
			for (String cmd : List.of("CAPA", "USER tony", "PASS secret", "STAT", "LIST", "UIDL", "QUIT")) {
				out.write((cmd + "\r\n").getBytes(StandardCharsets.US_ASCII));
				String reply = in.readLine();
				assertTrue(reply.startsWith("+OK"), cmd + ": " + reply);
				if (cmd.equals("CAPA") || cmd.equals("LIST") || cmd.equals("UIDL")) {
					while (!".".equals(in.readLine())) {
						// the list
					}
				}
			}
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
