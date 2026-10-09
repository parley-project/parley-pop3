package us.bringardner.parley.pop3.server;

import us.bringardner.parley.net.server.ServerMain;
import us.bringardner.parley.mail.server.AbstractMailServer;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;


import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.Message;
import us.bringardner.parley.net.IProcessor;
import us.bringardner.parley.net.IProcessorFactory;
import us.bringardner.parley.net.server.IAccessControlList;
import us.bringardner.parley.net.server.IPrincipal;
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
 * Users come from the access control list, configured as for FtpServer with the
 * {@code AuthenticationProvider} property (e.g. FileBasedAcl). A user needs the
 * READ permission to open a maildrop and WRITE to delete messages. The
 * principal parameter {@code maildrop} names the user's maildrop directory
 * (relative to the root); the default is the user name.
 * <p>
 * Each setting can be a system property or set with the matching setter:
 * <table>
 * <caption>Configuration</caption>
 * <tr><td>JPop3.port</td><td>110</td><td>Port (used by main)</td></tr>
 * <tr><td>JPop3.secure</td><td>false</td><td>Implicit TLS (normally on port 995)</td></tr>
 * <tr><td>JPop3.root</td><td>/pop3 (C:/pop3 on Windows)</td><td>Maildrop root</td></tr>
 * <tr><td>JPop3.fileSource</td><td>default factory</td><td>FileSource factory for the root</td></tr>
 * <tr><td>JPop3.autologout</td><td>600000 ms</td><td>Idle sessions are closed (RFC 1939: at least 10 minutes)</td></tr>
 * <tr><td>LoginFailureDelay (or JPop3.loginFailureDelay)</td><td>1000 ms</td><td>Delay before replying to a failed login</td></tr>
 * <tr><td>MaxLoginAttempts</td><td>3</td><td>Failed logins before the connection is closed</td></tr>
 * <tr><td>JPop3.requireTls</td><td>false</td><td>Refuse USER, PASS, APOP and AUTH until STLS (or implicit TLS)</td></tr>
 * <tr><td>JPop3.utf8Downgrade</td><td>surrogate</td><td>For sessions without UTF8: send messages with
 * UTF-8 headers as RFC 6858 surrogates, or "reject" them with -ERR [UTF8]</td></tr>
 * </table>
 * TLS (STLS, or implicit TLS) uses the key store properties of the framework's
 * SecureBaseObject, prefixed with "Pop3Server." (Pop3Server.KeyStoreName,
 * Pop3Server.KeyStorePassword, ...).
 */
public class Pop3Server extends AbstractMailServer implements POP3 {

	private static final long serialVersionUID = 1L;

	public static final String POP3_NAME = "JPop3";
	public static final String CONFIG_PROP = POP3_NAME + ".properties";
	public static final String ROOT_PROP = POP3_NAME + ".root";
	public static final String FILE_SOURCE_PROP = POP3_NAME + ".fileSource";
	public static final String DEFAULT_ROOT_WINDOWS = "C:/pop3";
	public static final String DEFAULT_ROOT = "/pop3";

	/** RFC 1939 section 3: the autologout timer must be at least 10 minutes. */
	public static final int DEFAULT_AUTOLOGOUT = 10 * 60 * 1000;
	public static final String AUTOLOGOUT_PROP = POP3_NAME + ".autologout";

	/** Delay (ms) before replying to a failed login, to slow down password guessing. */
	public static final int DEFAULT_LOGIN_FAILURE_DELAY = 1000;
	public static final String LOGIN_FAILURE_DELAY_PROP = POP3_NAME + ".loginFailureDelay";

	public static final String REQUIRE_TLS_PROP = POP3_NAME + ".requireTls";

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

	public static final String UTF8_DOWNGRADE_PROP = POP3_NAME + ".utf8Downgrade";

	private volatile Utf8Downgrade utf8Downgrade = Utf8Downgrade.parse(System.getProperty(UTF8_DOWNGRADE_PROP, "surrogate"));


	/** Maildrops in use, by canonical path (RFC 1939: one session per maildrop). */
	private final ConcurrentHashMap<String, Pop3RequestProcessor> locks = new ConcurrentHashMap<>();

	public Pop3Server(int port, String name, boolean secure) {
		super(port, name, "Pop3Server", secure);
		initMe();
		finishInit();
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

	public static void main(String[] args) throws Exception {
		ServerMain.configure("Pop3Server", args, CONFIG_PROP);
		boolean secure = Boolean.parseBoolean(System.getProperty(POP3_NAME + ".secure", "false"));
		int port = Integer.getInteger(POP3_NAME + ".port", secure ? POP3S_PORT : POP3_PORT);
		Pop3Server server = new Pop3Server(port, POP3_NAME, secure);
		server.start();
		System.out.println("Pop3Server started on port " + port);
	}

	private void initMe() {
		setName("Pop3Server");
		setProcessorFactory(new IProcessorFactory() {
			@Override
			public IProcessor getProcessor() {
				Pop3RequestProcessor ret = new Pop3RequestProcessor();
				ret.getLogger().setLevel(Pop3Server.this.getLogger().getLevel());
				return ret;
			}
		});
		initAutologout(AUTOLOGOUT_PROP, DEFAULT_AUTOLOGOUT);
		setMaxIdleConnection(getAutologout());
		setRequireTls(Boolean.getBoolean(REQUIRE_TLS_PROP));
		initMaildropRoot(FILE_SOURCE_PROP, null, ROOT_PROP, null, DEFAULT_ROOT, DEFAULT_ROOT_WINDOWS);

		// trigger access control initialization (AuthenticationProvider property)
		warnIfNoAccessControl();
	}

	// ------------------------------------------------------------------ maildrops

	/**
	 * The maildrop directory of a user: the principal's {@code maildrop}
	 * parameter, or the user name, resolved inside the maildrop root (".." can't
	 * leave the root).
	 */
	public FileSource getMaildropDirectory(IPrincipal principal) throws IOException {
		Object param = principal.getParameter(Pop3RequestProcessor.PARAMETER_MAILDROP);
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

	/**
	 * Deliver a message to a user's maildrop (for local delivery, e.g. from an
	 * SMTP server). The user's {@code maildrop} parameter is used when the access
	 * control list knows the user.
	 *
	 * @return the stored message file
	 */
	public FileSource deliver(String user, Message message) throws IOException {
		IAccessControlList acl = getAccessControl();
		IPrincipal p = acl == null ? null : acl.getPrincipal(user);
		FileSource dir = p != null ? getMaildropDirectory(p) : getMaildropDirectory(user);
		return Maildrop.deliver(dir, message);
	}

	/** Lock a maildrop for a session; false if another session has it (RFC 1939 [IN-USE]). */
	boolean lockMaildrop(String key, Pop3RequestProcessor owner) {
		return locks.putIfAbsent(key, owner) == null;
	}

	void unlockMaildrop(String key, Pop3RequestProcessor owner) {
		locks.remove(key, owner);
	}

	// ------------------------------------------------------------------ settings

	/**
	 * The shared LoginFailureDelay setting (see AbstractCoreServer) defaults to the older
	 * {@value #LOGIN_FAILURE_DELAY_PROP} system property, else {@value #DEFAULT_LOGIN_FAILURE_DELAY} ms.
	 */
	@Override
	protected int getDefaultLoginFailureDelay() {
		return Integer.getInteger(LOGIN_FAILURE_DELAY_PROP, DEFAULT_LOGIN_FAILURE_DELAY);
	}

	public Utf8Downgrade getUtf8Downgrade() {
		return utf8Downgrade;
	}

	/** How sessions without UTF8 (RFC 6856) get messages with non-ASCII headers. */
	public void setUtf8Downgrade(Utf8Downgrade utf8Downgrade) {
		this.utf8Downgrade = java.util.Objects.requireNonNull(utf8Downgrade);
	}
}
