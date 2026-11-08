package us.bringardner.parley.pop3.server;

import us.bringardner.parley.mail.server.MailSaslAuthenticator;
import us.bringardner.parley.net.sasl.PlainMechanism;
import us.bringardner.parley.net.sasl.ScramMechanism;

/**
 * parley-mail's {@link MailSaslAuthenticator} for a POP3 session: SASL logins go through the
 * session's {@code authenticate} with the server's user registry, the user name and password
 * prepared with SASLprep, as USER / PASS does. The AUTH command runs the exchange with
 * {@link #authenticate(us.bringardner.parley.net.sasl.ISaslMechanism, String, us.bringardner.parley.net.sasl.ISaslChannel)}
 * and then opens the maildrop.
 * <p>
 * PLAIN is served, and SCRAM-SHA-1 and SCRAM-SHA-256 when the user registry keeps SCRAM
 * credentials for every user. LOGIN isn't a POP3 mechanism here, so it isn't served (nor are
 * CRAM-MD5 and XOAUTH2, which no Parley server offers since 2.0.0).
 * <p>
 * One instance per AUTH command.
 */
public class Pop3SaslAuthenticator extends MailSaslAuthenticator {

	public Pop3SaslAuthenticator(Pop3RequestProcessor processor) {
		super(processor);
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
