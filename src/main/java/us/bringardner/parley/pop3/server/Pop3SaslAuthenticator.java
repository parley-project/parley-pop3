package us.bringardner.parley.pop3.server;

import us.bringardner.parley.net.sasl.ISaslAuthenticator;
import us.bringardner.parley.net.sasl.ScramCredentials;
import us.bringardner.parley.net.sasl.ScramMechanism;
import us.bringardner.parley.net.server.AccessControlUnavailableException;
import us.bringardner.parley.pop3.server.Pop3RequestProcessor.LoginResult;

/**
 * Checks SASL passwords the way USER / PASS does: through {@link Pop3RequestProcessor#login},
 * which prepares both with SASLprep, opens the maildrop and enters the TRANSACTION state.
 * <p>
 * PLAIN is served, and SCRAM-SHA-1 and SCRAM-SHA-256 when the server's access control list keeps
 * SCRAM credentials. SCRAM checks no password here: after it succeeds, the AUTH command logs the
 * user in with {@link Pop3RequestProcessor#loginVerified(String)}. CRAM-MD5 needs the clear
 * password, which a hash can't give, so it isn't served.
 * <p>
 * One instance per AUTH command: it remembers why the login failed, for the reply.
 */
public class Pop3SaslAuthenticator implements ISaslAuthenticator {

	private final Pop3RequestProcessor processor;
	private LoginResult lastResult;
	private boolean unavailable;

	public Pop3SaslAuthenticator(Pop3RequestProcessor processor) {
		this.processor = processor;
	}

	@Override
	public boolean checkPassword(String user, String password) {
		lastResult = processor.login(user, password);
		return lastResult == LoginResult.OK;
	}

	/** @return the result of the last login attempt, or null if the mechanism failed before one */
	public LoginResult getLastResult() {
		return lastResult;
	}

	@Override
	public boolean supports(String mechanism) {
		if ("PLAIN".equals(mechanism)) {
			return true;
		}
		String hash = ScramMechanism.hashOf(mechanism);
		return hash != null && processor.supportsScram(hash);
	}

	/** SCRAM credentials from the server's access control list (the name prepared with SASLprep). */
	@Override
	public ScramCredentials getScramCredentials(String user, String hash) {
		try {
			return processor.getScramCredentials(user, hash);
		} catch (AccessControlUnavailableException e) {
			unavailable = true;
			return null;
		}
	}

	/** @return true if the access control list couldn't answer, so the login failed for that reason */
	public boolean isUnavailable() {
		return unavailable || lastResult == LoginResult.UNAVAILABLE;
	}
}
