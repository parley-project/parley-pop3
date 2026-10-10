package us.bringardner.parley.pop3.server.commands;

import java.io.IOException;
import java.util.List;

import us.bringardner.parley.net.sasl.ISaslChannel;
import us.bringardner.parley.net.sasl.ISaslMechanism;
import us.bringardner.parley.net.sasl.SaslMechanisms;
import us.bringardner.parley.net.sasl.SaslOutcome;
import us.bringardner.parley.net.sasl.SaslServerDriver;
import us.bringardner.parley.net.server.IRequestContext;
import us.bringardner.parley.pop3.server.Pop3RequestProcessor;
import us.bringardner.parley.pop3.server.Pop3RequestProcessor.LoginResult;
import us.bringardner.parley.pop3.server.Pop3SaslAuthenticator;


/**
 * AUTH mechanism [initial-response] (RFC 5034). The exchange itself is run by
 * {@link SaslServerDriver}; this class supplies the POP3 parts: "+ " continuations, "*" to
 * cancel, and the replies. Which mechanisms are offered is decided by
 * {@link Pop3SaslAuthenticator} (PLAIN, RFC 4616).
 */
public class Auth extends NoAuthReqBaseCommand {

	private static final long serialVersionUID = 1L;

	public static final String PLAIN = "PLAIN";

	/** Every mechanism known; the authenticator limits what is offered. CRAM-MD5 is never offered here. */
	private static final SaslMechanisms MECHANISMS = SaslMechanisms.standard("pop3");

	public Auth() {
		super(AUTH);
	}

	/**
	 * @return the mechanism names to advertise to this session. TLS is a policy of the server
	 *         (require TLS before login), so plaintext mechanisms are not filtered here.
	 */
	public static List<String> mechanisms(Pop3RequestProcessor processor) {
		return MECHANISMS.offered(new Pop3SaslAuthenticator(processor), true);
	}

	@Override
	public void execute(Pop3RequestProcessor processor, IRequestContext context) throws IOException {
		String name = context.getNextToken();
		if (name == null) {
			processor.replyMultiLine("Supported mechanisms follow", mechanisms(processor));
			return;
		}
		Pop3SaslAuthenticator authenticator = new Pop3SaslAuthenticator(processor);
		ISaslMechanism mechanism = MECHANISMS.find(name);
		if (mechanism == null || !MECHANISMS.offered(authenticator, true).contains(mechanism.getName())) {
			processor.replyErr("Unsupported authentication mechanism");
			return;
		}
		if (processor.isLoginBlockedUntilTls()) {
			processor.replyErr(CODE_AUTH + " Use STLS before logging in");
			return;
		}
		String initial = context.getNextToken();
		if ("*".equals(initial)) {
			processor.replyErr("Authentication cancelled");
			return;
		}
		SaslOutcome outcome = SaslServerDriver.authenticate(mechanism, authenticator, initial, new ISaslChannel() {
			@Override
			public void sendChallenge(String base64) throws IOException {
				processor.reply(CONTINUE + base64);
			}

			@Override
			public String readResponse() throws IOException {
				return processor.readLine();
			}
		});
		switch (outcome.getStatus()) {
		case SUCCESS:
			processor.replyLoginResult(LoginResult.OK);
			break;
		case FAILED:
			LoginResult result = authenticator.getLastResult();
			processor.replyLoginResult(result != null ? result : LoginResult.FAILED);
			break;
		case CANCELLED:
			processor.replyErr("Authentication cancelled");
			break;
		case MALFORMED:
			processor.replyErr("Invalid base64 in the AUTH response");
			break;
		default:
			// CLOSED: the client went away
			break;
		}
	}
}
