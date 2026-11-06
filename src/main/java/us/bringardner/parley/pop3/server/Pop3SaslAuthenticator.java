package us.bringardner.parley.pop3.server;

import us.bringardner.parley.iam.AuthenticationResult;
import us.bringardner.parley.mail.server.MailSaslAuthenticator;
import us.bringardner.parley.net.sasl.PlainMechanism;
import us.bringardner.parley.net.sasl.ScramMechanism;
import us.bringardner.parley.pop3.server.Pop3RequestProcessor.LoginResult;

/**
 * parley-mail's {@link MailSaslAuthenticator} for a POP3 session: SASL logins go through the
 * session's {@code authenticate} with the server's user registry, the user name and password
 * prepared with SASLprep, as USER / PASS does; when one succeeds the maildrop is opened and the
 * session enters the TRANSACTION state.
 * <p>
 * PLAIN is served, and SCRAM-SHA-1 and SCRAM-SHA-256 when the user registry keeps SCRAM
 * credentials for every user. SCRAM checks no password here: after it succeeds, the AUTH command
 * logs the user in with {@link Pop3RequestProcessor#loginVerified(Pop3SaslAuthenticator, String)}.
 * CRAM-MD5 needs the clear password, which a hash can't give, so it isn't served.
 * <p>
 * One instance per AUTH command: it remembers why the login failed, for the reply.
 */
public class Pop3SaslAuthenticator extends MailSaslAuthenticator {

	private final Pop3RequestProcessor processor;
	private LoginResult lastResult;

	public Pop3SaslAuthenticator(Pop3RequestProcessor processor) {
		super(processor);
		this.processor = processor;
	}

	/** PLAIN: log in with the registry, then open the maildrop. */
	@Override
	public boolean checkPassword(String user, String password) {
		super.checkPassword(user, password);
		AuthenticationResult result = getResult();
		lastResult = processor.afterAuthentication(result, user);
		return lastResult == LoginResult.OK;
	}

	/** @return the result of the last login attempt, or null if the mechanism failed before one */
	public LoginResult getLastResult() {
		return lastResult;
	}

	/** PLAIN, and SCRAM when the user registry can serve it; LOGIN isn't a POP3 mechanism here. */
	@Override
	public boolean supports(String mechanism) {
		if (PlainMechanism.NAME.equals(mechanism) || ScramMechanism.hashOf(mechanism) != null) {
			return super.supports(mechanism);
		}
		return false;
	}
}
