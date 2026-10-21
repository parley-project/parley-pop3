package us.bringardner.parley.pop3.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static us.bringardner.parley.mail.test.MailTestSupport.ACL_FILE;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.mail.store.MailLocks;
import us.bringardner.parley.mail.store.MailboxRegistry;
import us.bringardner.parley.net.lock.LockDomain;
import us.bringardner.parley.net.lock.LockOptions;
import us.bringardner.parley.net.lock.Locks;
import us.bringardner.parley.net.lock.NamedLock;
import us.bringardner.parley.net.lock.Sharing;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IServer;
import us.bringardner.parley.pop3.server.Pop3Server;

/** A maildrop on a store shared with another server: RFC 1939's one session per maildrop reaches it. */
public class TestSharedMaildrop {

	private static Pop3Server server;
	private static FileSource root;
	private static Locks other;
	private static LockDomain otherDomain;
	private static String before;

	@BeforeAll
	public static void start() throws Exception {
		System.setProperty("parley.mail.pop3." + FileBasedAcl.PROP_FILE_NAME, ACL_FILE);
		System.setProperty("parley.mail.pop3." + IServer.AUTHENTICATION_PROVIDER_PROPERTY, FileBasedAcl.class.getName());
		before = System.getProperty(MailLocks.SHARED_STORE_PROPERTY);
		System.setProperty(MailLocks.SHARED_STORE_PROPERTY, "cluster");
		root = FileSourceFactory.getDefaultFactory().createTempDirectory("pop3shared");
		server = new Pop3Server(0, Pop3Server.POP3_NAME, false);
		server.setMaildropRoot(root);
		server.setLoginFailureDelay(0);
		server.getLogger().setLevel(Level.ERROR);
		server.startAndWait(10000);
		// another server on the same store: a lock service of its own
		other = Locks.open(LockOptions.sharing(Sharing.CLUSTER).withOwnerId("other").withLease(Duration.ofSeconds(2), Duration.ofMillis(400)));
		otherDomain = other.domain("mail", root);
	}

	@AfterAll
	public static void stop() {
		if (server != null) {
			server.stop();
		}
		if (other != null) {
			other.close();
		}
		if (before == null) {
			System.clearProperty(MailLocks.SHARED_STORE_PROPERTY);
		} else {
			System.setProperty(MailLocks.SHARED_STORE_PROPERTY, before);
		}
	}

	private static String maildropLockName() throws IOException {
		FileSource dir = server.getMaildropDirectory("tony");
		dir.mkdirs();
		return MailboxRegistry.lockName("maildrop", otherDomain, dir);
	}

	@Test
	public void aMaildropHeldByAnotherServerIsInUse() throws Exception {
		NamedLock held = otherDomain.tryLock(maildropLockName(), us.bringardner.parley.net.lock.Mode.EXCLUSIVE);
		assertNotNull(held);
		try (Client c = new Client()) {
			c.cmd("USER tony");
			assertTrue(c.cmd("PASS secret").startsWith("-ERR [IN-USE]"), "another server's session has it");
		} finally {
			held.close();
		}
		try (Client c = new Client()) {
			c.cmd("USER tony");
			assertTrue(c.cmd("PASS secret").startsWith("+OK"), "free once it lets go");
			assertNull(otherDomain.tryLock(maildropLockName(), us.bringardner.parley.net.lock.Mode.EXCLUSIVE),
					"and now the other server finds it in use");
			c.cmd("QUIT");
		}
		Thread.sleep(200);
		NamedLock again = otherDomain.tryLock(maildropLockName(), us.bringardner.parley.net.lock.Mode.EXCLUSIVE);
		assertNotNull(again, "released at QUIT");
		again.close();
	}

	/** A minimal POP3 client. */
	private static final class Client implements Closeable {
		private final Socket s;
		private final BufferedReader in;
		private final OutputStream out;

		Client() throws IOException {
			s = new Socket("localhost", server.getLocalPort());
			s.setSoTimeout(20000);
			in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
			out = s.getOutputStream();
			in.readLine(); // greeting
		}

		String cmd(String line) throws IOException {
			out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
			out.flush();
			return in.readLine();
		}

		@Override
		public void close() throws IOException {
			s.close();
		}
	}
}
