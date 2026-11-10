package us.bringardner.parley.pop3.server;

import us.bringardner.parley.net.server.ServerMain;
import us.bringardner.parley.mail.server.AbstractMailServer;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.net.IProcessor;
import us.bringardner.parley.net.IProcessorFactory;
import us.bringardner.parley.iam.ParleyPrincipal;
import us.bringardner.parley.pop3.POP3;

/**
 * A POP3 server (RFC 1939) built on the BJL network framework, following the
 * design of FtpServer:
 * <ul>
 * <li>Pop3Server accepts connections and holds the configuration;</li>
 * <li>a {@link Pop3RequestProcessor} runs each session;</li>
 * <li>{@link Pop3CommandFactory} maps each command to a class in the
 *     {@code commands} package.</li>
 * </ul>
 * Each user's maildrop is a directory under the maildrop root, a
 * {@link FileSource}, holding one file per message (see {@link Maildrop}).
 * <p>
 * Users come from the server's parley-iam user registry: one given with {@code setUserRegistry}
 * (parley-mail reads {@code parley.mail.users} into one), or the class the
 * {@code parley.mail.pop3.authenticationProvider} property names (parley-net's FileUserRegistry,
 * say). With none, a login is a configuration error, not a refused login. The registry decides
 * the permissions: a user needs read to open a maildrop and write to delete messages. The
 * principal attribute {@code maildrop} names the user's maildrop directory (relative to the
 * root); the default is the user name. Failed logins are counted and answered late by parley-net
 * (loginFailureDelay, maxLoginAttempts).
 * <p>
 * A server reads its settings from its own configuration (the system properties for a server made
 * by code, see parley-core's {@code BaseObject.setConfiguration}), or they are set with the matching
 * setter. A server's own settings (port, key store, limits, user registry, autologout, requireTls,
 * utf8Downgrade) are read with its property prefix: {@value #POP3_PREFIX}, or {@value #POP3S_PREFIX}
 * for a server with implicit TLS (parley.mail.pop3s.autologout, say).
 * <table>
 * <caption>Configuration</caption>
 * <tr><td>parley.mail.pop3.port</td><td>110</td><td>Port (parley.mail.pop3s.port, 995, with implicit TLS)</td></tr>
 * <tr><td>parley.mail.root</td><td>(required)</td><td>Maildrop root; the store carries its own settings (parley-mail's MailLocks)</td></tr>
 * <tr><td>parley.mail.fileSource</td><td>default factory</td><td>FileSource factory for the root</td></tr>
 * <tr><td>parley.mail.pop3.autologout</td><td>600000 ms</td><td>Idle sessions are closed (RFC 1939: at least 10 minutes)</td></tr>
 * <tr><td>parley.mail.pop3.loginFailureDelay</td><td>1000 ms</td><td>Delay before replying to a failed login</td></tr>
 * <tr><td>parley.mail.pop3.maxLoginAttempts</td><td>3</td><td>Failed logins before the connection is closed</td></tr>
 * <tr><td>parley.mail.pop3.requireTls</td><td>false</td><td>Refuse USER, PASS, APOP and AUTH until STLS (or implicit TLS)</td></tr>
 * <tr><td>parley.mail.pop3.utf8Downgrade</td><td>surrogate</td><td>For sessions without UTF8: send messages with
 * UTF-8 headers as RFC 6858 surrogates, or "reject" them with -ERR [UTF8]</td></tr>
 * </table>
 * TLS (STLS, or implicit TLS) uses the key store properties of the framework's
 * SecureBaseObject, with the server's prefix (parley.mail.pop3.keyStoreName,
 * parley.mail.pop3.keyStorePassword, ...).
 */
public class Pop3Server extends AbstractMailServer implements POP3 {


	public static final String POP3_NAME = "JPop3";
	/** The prefix of the properties of a POP3 server (port 110, STLS). */
	public static final String POP3_PREFIX = "parley.mail.pop3";
	/** The prefix of the properties of a POP3 server with implicit TLS (port 995). */
	public static final String POP3S_PREFIX = "parley.mail.pop3s";
	/** A properties file main loads into the system properties. */
	public static final String CONFIG_PROP = POP3_PREFIX + ".configFile";
	public static final String ROOT_PROP = MAIL_ROOT_PROP;
	public static final String FILE_SOURCE_PROP = MAIL_FILE_SOURCE_PROP;
	public static final String DEFAULT_ROOT_WINDOWS = "C:/pop3";
	public static final String DEFAULT_ROOT = "/pop3";

	/** RFC 1939 section 3: the autologout timer must be at least 10 minutes. */
	public static final int DEFAULT_AUTOLOGOUT = 10 * 60 * 1000;
	/** A server's idle time setting, after its prefix ({@value #POP3_PREFIX} or {@value #POP3S_PREFIX}) */
	public static final String AUTOLOGOUT = "autologout";
	/** The POP3 (port 110) server's {@link #AUTOLOGOUT} */
	public static final String AUTOLOGOUT_PROP = POP3_PREFIX + "." + AUTOLOGOUT;

	/** Delay (ms) before replying to a failed login, to slow down password guessing. */
	public static final int DEFAULT_LOGIN_FAILURE_DELAY = 1000;

	/** A server's require-TLS setting, after its prefix */
	public static final String REQUIRE_TLS = "requireTls";
	/** The POP3 (port 110) server's {@link #REQUIRE_TLS} */
	public static final String REQUIRE_TLS_PROP = POP3_PREFIX + "." + REQUIRE_TLS;

	/**
	 * What a session that didn't send UTF8 (RFC 6856) gets for a message with
	 * non-ASCII headers.
	 */
	public enum Utf8Downgrade {
		/** The RFC 6858 surrogate message (default). */
		SURROGATE,
		/** "-ERR [UTF8]" for RETR, TOP and LIST of the message. */
		REJECT;

		public static Utf8Downgrade parse(String value) {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		}
	}

	/** A server's UTF-8 downgrade setting, after its prefix */
	public static final String UTF8_DOWNGRADE = "utf8Downgrade";
	/** The POP3 (port 110) server's {@link #UTF8_DOWNGRADE} */
	public static final String UTF8_DOWNGRADE_PROP = POP3_PREFIX + "." + UTF8_DOWNGRADE;

	private volatile Utf8Downgrade utf8Downgrade = Utf8Downgrade.parse(getProperty(propertyName(UTF8_DOWNGRADE), "surrogate"));


	/** Maildrops in use, by canonical path (RFC 1939: one session per maildrop). */
	private final ConcurrentHashMap<String, Pop3RequestProcessor> locks = new ConcurrentHashMap<>();

	public Pop3Server(int port, String name, boolean secure) {
		super(port, name, secure ? POP3S_PREFIX : POP3_PREFIX, secure);
		initMe();
	}

	@Override
	protected String getRootProperty() {
		return ROOT_PROP;
	}

	@Override
	protected void onAutologoutChanged(int autologout) {
		setMaxIdleConnection(autologout);
	}

	public Pop3Server() {
		this(POP3_PORT, POP3_NAME, false);
	}

	public Pop3Server(boolean secure) {
		this(secure ? POP3S_PORT : POP3_PORT, POP3_NAME, secure);
	}

	public Pop3Server(FileSource root, boolean secure) {
		this(secure);
		try {
			setMaildropRoot(root);
		} catch (IOException e) {
			throw new java.io.UncheckedIOException("Invalid maildrop root " + root, e);
		}
	}

	/**
	 * Run POP3 (parley.mail.pop3.port, default 110) and, when there is a TLS key store, POP3 with
	 * implicit TLS (parley.mail.pop3s.port, default 995) in this process until it is stopped, with
	 * parley-net's ServerMain.run: the settings are parley-pop3.properties in the settings folder (or
	 * the file parley.mail.pop3.configFile names) and the command line, read as system properties.
	 * Each server's store is the JMX bean us.bringardner.parley:type=MailStore,name=<its id> while it
	 * runs (MailServerMain).
	 */
	public static void main(String[] args) throws Exception {
		ServerMain.run("Pop3Server", args, CONFIG_PROP, "parley-pop3.properties", () -> {
			java.util.List<ServerMain.Service> ret = new java.util.ArrayList<>();
			{
				int p = standalonePort(POP3_PREFIX, POP3_PORT);
				ret.add(us.bringardner.parley.mail.server.MailServerMain.service(new Pop3Server(p, POP3_NAME, false), "POP3 server", "pop3"));
			}
			if (hasKeyStore(POP3S_PREFIX)) {
				int p = standalonePort(POP3S_PREFIX, POP3S_PORT);
				ret.add(us.bringardner.parley.mail.server.MailServerMain.service(new Pop3Server(p, POP3_NAME, true), "POP3 server (implicit TLS)", "pop3s"));
			} else {
				System.err.println("Warning: POP3 server (implicit TLS) is skipped: no TLS key store (set " + MAIL_TLS_KEY_STORE_PROP + " or " + POP3S_PREFIX + ".keyStoreName)");
			}
			return ret;
		});
	}

	/** A server's port in a standalone process: {@code <prefix>.port}, else its standard port */
	private static int standalonePort(String prefix, int standard) {
		String v = System.getProperty(prefix + ".port");
		return v == null || v.trim().isEmpty() ? standard : Integer.parseInt(v.trim());
	}

	/** A TLS key store for this server in a standalone process: the suite's, or its own */
	private static boolean hasKeyStore(String prefix) {
		String a = System.getProperty(MAIL_TLS_KEY_STORE_PROP);
		String b = System.getProperty(prefix + ".keyStoreName");
		return (a != null && !a.trim().isEmpty()) || (b != null && !b.trim().isEmpty());
	}

	private void initMe() {
		setName("Pop3Server");
		setProcessorFactory(new IProcessorFactory() {
			@Override
			public IProcessor getProcessor() {
				Pop3RequestProcessor ret = new Pop3RequestProcessor();
				ret.setLogLevel(Pop3Server.this.getLogLevel());
				return ret;
			}
		});
		initAutologout(propertyName(AUTOLOGOUT), DEFAULT_AUTOLOGOUT);
		setMaxIdleConnection(getAutologout());
		setRequireTls(getBooleanProperty(propertyName(REQUIRE_TLS), false));
		initMaildropRoot(FILE_SOURCE_PROP, null, ROOT_PROP, null, DEFAULT_ROOT, DEFAULT_ROOT_WINDOWS);

		// make the user registry now (authenticationProvider property), or warn that there is none
		warnIfNoAccessControl();
	}

	// ------------------------------------------------------------------ maildrops

	/**
	 * The maildrop directory of a user: the principal's {@code maildrop}
	 * attribute, or the user name, resolved inside the maildrop root (".." can't
	 * leave the root).
	 */
	public FileSource getMaildropDirectory(ParleyPrincipal principal) throws IOException {
		Object param = principal.getAttribute(Pop3RequestProcessor.PARAMETER_MAILDROP);
		return getMaildropDirectory(param != null ? param.toString() : principal.getName());
	}

	/** The maildrop directory for a path relative to the root. */
	public FileSource getMaildropDirectory(String path) throws IOException {
		java.util.ArrayDeque<String> segments = new java.util.ArrayDeque<>();
		for (String s : path.replace('\\', '/').split("/")) {
			if (s.isEmpty() || s.equals(".")) {
				continue;
			}
			if (s.equals("..")) {
				segments.pollLast();
			} else {
				segments.add(s);
			}
		}
		if (segments.isEmpty()) {
			throw new IOException("Invalid maildrop path '" + path + "'");
		}
		FileSource dir = getMaildropRoot();
		for (String s : segments) {
			dir = dir.getChild(s);
		}
		return dir;
	}

	/** Lock a maildrop for a session; false if another session has it (RFC 1939 [IN-USE]). */
	boolean lockMaildrop(String key, Pop3RequestProcessor owner) {
		return locks.putIfAbsent(key, owner) == null;
	}

	void unlockMaildrop(String key, Pop3RequestProcessor owner) {
		locks.remove(key, owner);
	}

	// ------------------------------------------------------------------ settings


	public Utf8Downgrade getUtf8Downgrade() {
		return utf8Downgrade;
	}

	/** How sessions without UTF8 (RFC 6856) get messages with non-ASCII headers. */
	public void setUtf8Downgrade(Utf8Downgrade utf8Downgrade) {
		this.utf8Downgrade = java.util.Objects.requireNonNull(utf8Downgrade);
	}
}
