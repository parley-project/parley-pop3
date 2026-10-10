package us.bringardner.parley.pop3.server;

import us.bringardner.parley.net.sasl.ISaslAuthenticator;
import us.bringardner.parley.pop3.server.Pop3RequestProcessor.LoginResult;

/**
 * Checks SASL passwords the way USER / PASS does: through {@link Pop3RequestProcessor#login},
 * which prepares both with SASLprep, opens the maildrop and enters the TRANSACTION state.
 * <p>
 * Only PLAIN is served. The access control list holds password hashes, which can't answer
 * CRAM-MD5 or SCRAM; a store that has the clear password or SCRAM credentials can offer them by
 * overriding {@link #supports(String)} and the matching lookup.
 * <p>
 * One instance per AUTH command: it remembers why the login failed, for the reply.
 */
public class Pop3SaslAuthenticator implements ISaslAuthenticator {

	private final Pop3RequestProcessor processor;
	private LoginResult lastResult;

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
		return "PLAIN".equals(mechanism);
	}
}
