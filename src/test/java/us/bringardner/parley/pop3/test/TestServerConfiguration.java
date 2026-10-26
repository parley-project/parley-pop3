package us.bringardner.parley.pop3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.mail.server.AbstractMailServer;
import us.bringardner.parley.pop3.server.Pop3Server;

/**
 * POP3 servers in one JVM, each made from its own configuration: the same name gets the value of
 * each server's own settings, a POP3S server reads its own prefix, and neither reads the system
 * properties.
 */
public class TestServerConfiguration {

	@TempDir
	File dir;

	private static Properties props(String... kv) {
		Properties p = new Properties();
		for (int i = 0; i < kv.length; i += 2) {
			p.setProperty(kv[i], kv[i + 1]);
		}
		return p;
	}

	private static Pop3Server make(Properties c, boolean secure) throws Exception {
		return AbstractMailServer.makeIn(null, c, () -> new Pop3Server(0, Pop3Server.POP3_NAME, secure));
	}

	@Test
	public void oneNameTwoValues() throws Exception {
		Pop3Server a = make(props("parley.mail.root", new File(dir, "a").getPath(), "parley.mail.pop3.autologout", "700000"), false);
		Pop3Server b = make(props("parley.mail.root", new File(dir, "b").getPath(), "parley.mail.pop3.autologout", "800000"), false);
		assertTrue(a.getMaildropRoot().getAbsolutePath().endsWith("a"));
		assertTrue(b.getMaildropRoot().getAbsolutePath().endsWith("b"));
		assertEquals(700000, a.getAutologout());
		assertEquals(800000, b.getAutologout());
	}

	@Test
	public void pop3sReadsItsOwnPrefix() throws Exception {
		Properties c = props("parley.mail.root", dir.getPath(), "parley.mail.pop3.requireTls", "true", "parley.mail.pop3.autologout", "700000",
				"parley.mail.pop3s.autologout", "900000");
		Pop3Server pop3 = make(c, false);
		Pop3Server pop3s = make(c, true);
		assertTrue(pop3.isRequireTls());
		assertFalse(pop3s.isRequireTls(), "pop3's setting isn't pop3s's");
		assertEquals(700000, pop3.getAutologout());
		assertEquals(900000, pop3s.getAutologout());
	}

	@Test
	public void theSystemPropertiesArentRead() throws Exception {
		System.setProperty("parley.mail.pop3.autologout", "1234567");
		try {
			Pop3Server s = make(props("parley.mail.root", dir.getPath()), false);
			assertEquals(Pop3Server.DEFAULT_AUTOLOGOUT, s.getAutologout());
			assertNotEquals(1234567, s.getAutologout());
		} finally {
			System.clearProperty("parley.mail.pop3.autologout");
		}
	}
}
