package us.bringardner.parley.pop3.server;

import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.iam.AuthenticationResult;
import us.bringardner.parley.iam.AuthorizationResult;
import us.bringardner.parley.iam.ParleyPermission;
import us.bringardner.parley.iam.ParleyPrincipal;
import us.bringardner.parley.iam.StoredCredentialType;
import us.bringardner.parley.iam.UserRegistry;
import us.bringardner.parley.iam.UserRegistryUnavailableException;
import us.bringardner.parley.iam.credential.ProofCredential;
import us.bringardner.parley.mail.server.AbstractMailProcessor;
import us.bringardner.parley.mail.store.MailboxAcl;
import us.bringardner.parley.net.IConnection;
import us.bringardner.parley.net.sasl.SaslPrep;
import us.bringardner.parley.net.server.ApopVerifier;
import us.bringardner.parley.net.server.ICommand;
import us.bringardner.parley.net.server.IRequestContext;
import us.bringardner.parley.net.server.StateMachine;
import us.bringardner.parley.pop3.POP3;

/**
 * One POP3 session (RFC 1939). Like FtpRequestProcessor, it reads command lines
 * and runs the command classes from {@link Pop3CommandFactory}; it also holds the
 * session state (AUTHORIZATION, TRANSACTION, UPDATE) and the open {@link Maildrop}.
 */
public class Pop3RequestProcessor extends AbstractMailProcessor implements POP3 {

	private static final long serialVersionUID = 1L;

	/** Principal attribute: the user's maildrop directory, relative to the maildrop root. */
	public static final String PARAMETER_MAILDROP = "maildrop";

	/** Session states, RFC 1939 section 3. */
	public enum State {
		AUTHORIZATION, TRANSACTION, UPDATE
	}

	/** The outcome of a login attempt. */
	public enum LoginResult {
		OK, FAILED, IN_USE, TLS_REQUIRED, ERROR,
		/** The user registry can't answer now (its database is down, say): try again later. @since 1.1.0 */
		UNAVAILABLE
	}

	private static final SecureRandom RANDOM = new SecureRandom();

	/** AUTHORIZATION, then TRANSACTION after a login, then UPDATE after QUIT (RFC 1939 section 3). */
	private final StateMachine<State> states = new StateMachine<>(State.AUTHORIZATION)
			.allow(State.AUTHORIZATION, State.TRANSACTION)
			.allow(State.TRANSACTION, State.UPDATE);
	/** UTF-8 mode, set by the UTF8 command (RFC 6856). */
	private volatile boolean utf8Mode;
	private final transient Map<String, Object> tempStorage = new HashMap<>();
	/** True if the greeting sent an APOP timestamp. */
	private boolean apopOffered;
	/** Why the logged-in user can't read mail (the read permission is missing), or null. */
	private String readMissing;
	/** The rights on the INBOX, read at login. */
	private String inboxRights = "";
	/** The APOP timestamp sent in the greeting (RFC 1939 section 7). */
	private final String timestamp;
	private transient Maildrop maildrop;
	private String lockKey;
	/** The maildrop's lock in the store's lock domain (reaches other servers), or null. */
	private us.bringardner.parley.net.lock.NamedLock maildropLock;
	private String lastError;

	public Pop3RequestProcessor() {
		super();
		setCommandFactory(new Pop3CommandFactory());
		setName("Pop3RequestProcessor");
		setPropertyPrefix("parley.mail.pop3.session");
		timestamp = "<" + ProcessHandle.current().pid() + "." + System.currentTimeMillis() + "."
				+ Long.toHexString(RANDOM.nextLong() & Long.MAX_VALUE) + "@" + hostName() + ">";
	}

	private static String hostName() {
		try {
			return java.net.InetAddress.getLocalHost().getHostName();
		} catch (IOException | RuntimeException e) {
			return "localhost";
		}
	}

	/** The greeting, with the APOP timestamp only when the user registry keeps plain text secrets (RFC 1939 section 7). */
	@Override
	protected void sendGreeting() throws IOException {
		UserRegistry registry = getServer().getUserRegistry();
		apopOffered = registry != null && registry.supports(ProofCredential.class, StoredCredentialType.PLAIN_TEXT);
		reply(REPLY_OK, getServer().getName() + " POP3 server ready" + (apopOffered ? " " + timestamp : ""));
	}

	/** RFC 1939: without QUIT there is no UPDATE state, so nothing is deleted. */
	@Override
	protected void sessionEnded() {
		releaseMaildrop();
	}

	/**
	 * Run a command line: unknown commands, commands that aren't valid in the
	 * current state and commands the user lacks permission for get -ERR.
	 */
	@Override
	protected void runRequest(IRequestContext context) throws IOException {
		String first = context.getFirstToken();
		if (first == null || first.isEmpty()) {
			reply(REPLY_ERR, "Empty command");
			return;
		}
		if (isDebugEnabled()) {
			logDebug("Received command=" + first);
		}
		ICommand command = getCommandFactory().getCommand(context);
		if (!(command instanceof Pop3Command)) {
			reply(REPLY_ERR, "Unknown command " + sanitize(first));
			return;
		}
		Pop3Command cmd = (Pop3Command) command;
		recordCommandUsed(cmd.getName());
		if (!cmd.isValidIn(states.get())) {
			reply(REPLY_ERR, cmd.getName() + " is not valid in the " + states.get() + " state");
			return;
		}
		if (states.is(State.TRANSACTION) && cmd.requiresAuthorization()) {
			AuthorizationResult allowed = checkPermission(cmd.getPermission(), null);
			if (allowed.getStatus() == AuthorizationResult.Status.UNAVAILABLE) {
				logError(cmd.getName() + " not checked: " + allowed.getReason());
				replyPermissionUnavailable(cmd);
				return;
			}
			if (allowed.getStatus() != AuthorizationResult.Status.ALLOWED) {
				replyNotAuthorized(cmd, reportMissingPermission(cmd.getName(), cmd.getPermission()));
				return;
			}
		}
		cmd.execute(this, context);
	}

	@Override
	protected void replyNotAuthorized(ICommand command, String reason) throws IOException {
		reply(REPLY_ERR, "Permission denied for " + command.getName() + ": " + reason);
	}

	/** The user registry can't answer a permission check now: a temporary error, not "denied" (RFC 3206). */
	@Override
	protected void replyPermissionUnavailable(ICommand command) throws IOException {
		reply(REPLY_ERR, CODE_SYS_TEMP + " " + command.getName() + " can't be checked now, try again later");
	}

	/**
	 * A command failed with an unexpected error (logged by the command loop): -ERR with RFC 3206's
	 * [SYS/TEMP], a problem on the server that may go away, instead of the loop's 500, which isn't
	 * a POP3 reply. The session goes on.
	 */
	@Override
	protected void replyCommandFailed(IRequestContext request) throws IOException {
		String command = request.getFirstToken();
		reply(REPLY_ERR, CODE_SYS_TEMP + " " + (command == null ? "The command" : sanitize(command)) + " failed, try again later");
	}

	/** Echo client text in a reply without control characters. */
	static String sanitize(String text) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < text.length() && sb.length() < 40; i++) {
			char c = text.charAt(i);
			sb.append(c < 32 || c == 127 ? '?' : c);
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------ replies

	@Override
	public void reply(String text) throws IOException {
		java.util.concurrent.locks.ReentrantLock outputLock = getOutputLock();
		outputLock.lock();
		try {
			super.reply(text);
		} finally {
			outputLock.unlock();
		}
	}

	/** "+OK" for {@link POP3#REPLY_OK}, otherwise "-ERR". */
	@Override
	public String translateResponseCode(int code) {
		return code == REPLY_OK ? OK : ERR;
	}

	public void replyOk(String text) throws IOException {
		reply(REPLY_OK, text);
	}

	public void replyErr(String text) throws IOException {
		reply(REPLY_ERR, text);
	}

	/**
	 * A multi-line reply: the status line, the lines (dot-stuffed) and the
	 * terminating ".", sent with one flush.
	 */
	public void replyMultiLine(String status, List<String> lines) throws IOException {
		java.util.concurrent.locks.ReentrantLock outputLock = getOutputLock();
		outputLock.lock();
		try {
			IConnection con = getConnection();
			con.writeLine(OK + " " + status);
			for (String line : lines) {
				con.writeLine(line.startsWith(".") ? "." + line : line);
			}
			con.writeLine(TERMINATOR);
			con.flush();
		} finally {
			outputLock.unlock();
		}
	}

	/**
	 * Send "+OK status" and then message content written by {@code body} to the
	 * raw socket (dot-stuffed, CRLF line ends, terminated by "."). Used for RETR
	 * and TOP, so a message of any size is streamed from the maildrop.
	 */
	public void replyContent(String status, ContentWriter body) throws IOException {
		java.util.concurrent.locks.ReentrantLock outputLock = getOutputLock();
		outputLock.lock();
		try {
			IConnection con = getConnection();
			con.writeLine(OK + " " + status);
			con.flush();
			OutputStream raw = new BufferedOutputStream(new FilterOutputStream(con.getSocket().getOutputStream()) {
				@Override
				public void write(byte[] b, int off, int len) throws IOException {
					out.write(b, off, len);
				}

				@Override
				public void close() throws IOException {
					flush(); // never close the socket's stream
				}
			}, 64 * 1024);
			try (DotStuffingOutputStream out = new DotStuffingOutputStream(raw)) {
				body.write(out);
			}
		} finally {
			outputLock.unlock();
		}
	}

	/** Writes message content for {@link #replyContent(String, ContentWriter)}. */
	@FunctionalInterface
	public interface ContentWriter {
		void write(OutputStream out) throws IOException;
	}

	/** Read one line from the client (for AUTH continuations). */
	public String readLine() throws IOException {
		return getConnection().readLine();
	}

	// ------------------------------------------------------------------ login

	public State getState() {
		return states.get();
	}

	@Override
	protected StateMachine<?> getStateMachine() {
		return states;
	}

	public Maildrop getMaildrop() {
		return maildrop;
	}

	public String getTimestamp() {
		return timestamp;
	}

	public Pop3Server getPop3Server() {
		return (Pop3Server) getServer();
	}


	/** True after a successful UTF8 command (RFC 6856). */
	public boolean isUtf8Mode() {
		return utf8Mode;
	}

	/** Enter UTF-8 mode (RFC 6856): messages are sent with their UTF-8 headers. */
	public void enableUtf8Mode() {
		utf8Mode = true;
	}

	/**
	 * Log in with a user name and password (USER/PASS). Both are prepared with SASLprep
	 * (RFC 6856 section 2.2, RFC 4616) and checked by the server's user registry through the
	 * session's {@code authenticate}; invalid UTF-8 or prohibited characters fail the login.
	 * A refused login is counted, and answered late, by parley-net.
	 *
	 * @throws IllegalStateException when the server has no user registry
	 */
	public LoginResult login(String user, String password) {
		if (isLoginBlockedUntilTls()) {
			return LoginResult.TLS_REQUIRED;
		}
		return afterAuthentication(authenticate(user, password), user);
	}

	/**
	 * A SASL authenticator for this session's AUTH command (PLAIN, and SCRAM when the user registry
	 * can serve it).
	 *
	 * @since 2.0.0
	 */
	@Override
	public Pop3SaslAuthenticator newSaslAuthenticator() {
		return new Pop3SaslAuthenticator(this);
	}

	/**
	 * Log in with APOP (RFC 1939 section 7): the digest is MD5(timestamp + secret). The server's
	 * user registry gives the user's plain text secret ({@link StoredCredentialType#PLAIN_TEXT})
	 * to parley-net's {@link ApopVerifier}, through the session's {@code authenticate} with a
	 * {@link ProofCredential}; users without a plain text password can't use APOP. The user name
	 * is prepared with SASLprep (RFC 6856 section 2.2).
	 *
	 * @throws IllegalStateException when the server has no user registry
	 */
	public LoginResult loginApop(String user, String digest) {
		if (isLoginBlockedUntilTls()) {
			return LoginResult.TLS_REQUIRED;
		}
		String prepared = SaslPrep.prepare(user, false);
		if (prepared == null || prepared.isEmpty() || digest == null || !apopOffered) {
			getServer().requireUserRegistry(); // no registry is a mistake in the configuration, not a refusal
			refuseLogin(apopOffered ? "the user name can't be prepared (SASLprep)" : "APOP wasn't offered");
			return LoginResult.FAILED;
		}
		ProofCredential credential = new ProofCredential(prepared, StoredCredentialType.PLAIN_TEXT,
				new ApopVerifier(timestamp, digest), getRequester());
		return afterAuthentication(authenticate(credential), prepared);
	}

	/**
	 * After a login was checked (USER / PASS, APOP, or a whole AUTH exchange): open the maildrop
	 * when it was good; a temporary failure when the user registry couldn't answer; otherwise
	 * FAILED (already counted by the session).
	 *
	 * @param result how the login ended
	 * @param user who, or which command, for the log
	 * @since 2.0.0
	 */
	public LoginResult afterAuthentication(AuthenticationResult result, String user) {
		if (result == null) {
			return LoginResult.FAILED;
		}
		switch (result.getStatus()) {
		case AUTHENTICATED:
			return openMaildrop(result.getPrincipal());
		case UNAVAILABLE:
			logError("Can't check the login of " + user + ": " + result.getReason());
			return LoginResult.UNAVAILABLE;
		default:
			return LoginResult.FAILED;
		}
	}

	/**
	 * Lock and open the user's maildrop, entering the TRANSACTION state. The session is logged
	 * in already; it is logged out again when the maildrop can't be opened.
	 */
	private LoginResult openMaildrop(ParleyPrincipal p) {
		LoginResult ret = LoginResult.ERROR;
		try {
			ret = openMaildropFor(p);
			return ret;
		} finally {
			if (ret != LoginResult.OK) {
				logout();
			}
		}
	}

	private LoginResult openMaildropFor(ParleyPrincipal p) {
		if (p == null) {
			return LoginResult.FAILED;
		}
		AuthorizationResult read = checkPermission(Pop3Command.READ_PERMISSION, null);
		if (read.getStatus() == AuthorizationResult.Status.UNAVAILABLE) {
			logError("Can't check the read permission of " + p.getName() + ": " + read.getReason());
			return LoginResult.UNAVAILABLE;
		}
		// without read the login still stands (the password was right): the user is told, the
		// administrators are told, and every command that needs read is refused
		readMissing = read.getStatus() == AuthorizationResult.Status.ALLOWED ? null
				: reportMissingPermission("login", Pop3Command.READ_PERMISSION);
		try {
			Pop3Server server = getPop3Server();
			FileSource dir = server.getMaildropDirectory(p);
			String key = dir.getCanonicalPath();
			if (!server.lockMaildrop(key, this)) {
				return LoginResult.IN_USE;
			}
			try {
				// one session per maildrop across every server sharing the store (RFC 1939 [IN-USE])
				us.bringardner.parley.net.lock.LockDomain d = us.bringardner.parley.mail.store.MailboxRegistry.get().domainOf(dir);
				if (d != null) {
					maildropLock = d.request(us.bringardner.parley.mail.store.MailboxRegistry.lockName("maildrop", d, dir))
							.exclusive().reentrant(false).tryAcquire();
					if (maildropLock == null) {
						server.unlockMaildrop(key, this);
						return LoginResult.IN_USE;
					}
				}
				inboxRights = inboxRights(dir, p);
				maildrop = Maildrop.open(dir, utf8Mode);
			} catch (IOException | RuntimeException e) {
				maildrop = null;
				closeMaildropLock();
				server.unlockMaildrop(key, this);
				throw e;
			}
			lockKey = key;
			states.moveTo(State.TRANSACTION);
			tempStorage.clear();
			return LoginResult.OK;
		} catch (UserRegistryUnavailableException e) {
			logError("Can't check the rights of " + p.getName() + ": " + e.getMessage());
			return LoginResult.UNAVAILABLE;
		} catch (IOException | RuntimeException e) {
			logError("Can't open the maildrop of " + p.getName(), e);
			lastError = e.getMessage();
			return LoginResult.ERROR;
		}
	}

	/**
	 * The user's rights on the INBOX (RFC 4314, parley-mail's {@code .imap-acl}, shared with IMAP),
	 * limited by the read and write permissions.
	 *
	 * @throws UserRegistryUnavailableException when the user registry can't answer now
	 */
	private String inboxRights(FileSource dir, ParleyPrincipal p) throws IOException {
		String r = MailboxAcl.rightsOf(MailboxAcl.read(dir), p.getName(), p.getName(), groupNames(p));
		return MailboxAcl.limit(r, holds(Pop3Command.READ_PERMISSION), holds(Pop3Command.WRITE_PERMISSION));
	}

	/** True when the registry allows it; an exception when it can't answer. */
	private boolean holds(ParleyPermission permission) throws UserRegistryUnavailableException {
		AuthorizationResult r = checkPermission(permission, null);
		if (r.getStatus() == AuthorizationResult.Status.UNAVAILABLE) {
			throw new UserRegistryUnavailableException(r.getReason());
		}
		return r.getStatus() == AuthorizationResult.Status.ALLOWED;
	}

	/**
	 * The names of the user's groups, for {@code $group} entries in the mailbox ACL: parley-mail's
	 * {@code findGroupNames} (the user registry's {@code findGroups}).
	 *
	 * @throws UserRegistryUnavailableException when the user registry can't answer now
	 */
	private java.util.Collection<String> groupNames(ParleyPrincipal p) throws UserRegistryUnavailableException {
		return getPop3Server().findGroupNames(p);
	}

	/**
	 * True if the user has all these rights on the INBOX: RETR and TOP need r, DELE t and e,
	 * and RETR sets \Seen only with s.
	 * @since 2.0.0
	 */
	public boolean hasInboxRights(String rights) {
		return MailboxAcl.has(inboxRights, rights);
	}

	/** The message of the last error opening a maildrop. */
	public String getLastError() {
		return lastError;
	}

	/**
	 * Reply to a login. A refused login has been counted and delayed by the session already; after
	 * too many, the command processor closes the connection after this reply.
	 */
	public void replyLoginResult(LoginResult result) throws IOException {
		switch (result) {
		case OK:
			if (readMissing != null) {
				// logged in, but nothing about the maildrop is told
				replyOk("Logged in, but " + readMissing);
			} else {
				replyOk("Maildrop has " + maildrop.getMessageCount() + " messages (" + maildrop.getTotalSize() + " octets)");
			}
			break;
		case IN_USE:
			replyErr(CODE_IN_USE + " The maildrop is already in use");
			break;
		case TLS_REQUIRED:
			replyErr(CODE_AUTH + " Use STLS before logging in");
			break;
		case ERROR:
			replyErr(CODE_SYS_TEMP + " The maildrop can't be opened");
			break;
		case UNAVAILABLE:
			// not the client's fault: no delay, not counted as a failed login (RFC 3206)
			replyErr(CODE_SYS_TEMP + " Logins can't be checked now, try again later");
			break;
		default:
			replyErr(CODE_AUTH + " Invalid user name or password");
		}
	}

	public void setTempValue(String key, Object value) {
		tempStorage.put(key, value);
	}

	public Object getTempValue(String key) {
		return tempStorage.get(key);
	}

	public Object removeTempValue(String key) {
		return tempStorage.remove(key);
	}

	/** STLS: switch the connection to TLS. Any USER given before is forgotten (RFC 2595). */
	@Override
	protected void afterTls() {
		tempStorage.clear();
	}

	/**
	 * True if this message can't be sent in this session: it needs UTF-8 mode and
	 * the server is set to reject instead of sending a surrogate. Replies
	 * "-ERR [UTF8]" if so.
	 */
	public boolean rejectUtf8(Maildrop.Entry e) throws IOException {
		if (e.isSurrogate() && getPop3Server().getUtf8Downgrade() == Pop3Server.Utf8Downgrade.REJECT) {
			replyErr(CODE_UTF8 + " The message has UTF-8 headers; send UTF8 first");
			return true;
		}
		return false;
	}

	/**
	 * QUIT in the TRANSACTION state: enter UPDATE, remove the deleted messages and
	 * release the maildrop.
	 *
	 * @return the number of messages that could not be removed
	 */
	public int update() {
		states.moveTo(State.UPDATE);
		int failed;
		try {
			failed = maildrop.commit(maildropLock);
		} catch (RuntimeException e) {
			logError("Error removing deleted messages", e);
			failed = 1;
		}
		releaseMaildrop();
		return failed;
	}

	private void releaseMaildrop() {
		if (maildrop != null) {
			try {
				maildrop.close();
			} catch (IOException | RuntimeException e) {
				logError("Can't save the flags of the maildrop", e);
			}
		}
		closeMaildropLock();
		if (lockKey != null) {
			getPop3Server().unlockMaildrop(lockKey, this);
			lockKey = null;
		}
	}

	private void closeMaildropLock() {
		if (maildropLock != null) {
			maildropLock.close();
			maildropLock = null;
		}
	}

}
