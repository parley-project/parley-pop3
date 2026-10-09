package us.bringardner.parley.pop3.server.commands;

import java.io.IOException;
import java.util.Locale;

import us.bringardner.parley.mail.Sasl;
import us.bringardner.parley.net.server.IRequestContext;
import us.bringardner.parley.pop3.server.Pop3RequestProcessor;


/**
 * AUTH mechanism [initial-response] (RFC 5034), with the PLAIN mechanism
 * (RFC 4616). Without an initial response the server sends "+ " and reads the
 * response from the next line; "*" cancels.
 */
public class Auth extends NoAuthReqBaseCommand {

	private static final long serialVersionUID = 1L;

	public static final String PLAIN = "PLAIN";

	public Auth() {
		super(AUTH);
	}

	@Override
	public void execute(Pop3RequestProcessor processor, IRequestContext context) throws IOException {
		String mechanism = context.getNextToken();
		if (mechanism == null) {
			processor.replyMultiLine("Supported mechanisms follow", java.util.List.of(PLAIN));
			return;
		}
		if (!PLAIN.equals(mechanism.toUpperCase(Locale.ROOT))) {
			processor.replyErr("Unsupported authentication mechanism");
			return;
		}
		if (processor.isLoginBlockedUntilTls()) {
			processor.replyErr(CODE_AUTH + " Use STLS before logging in");
			return;
		}
		String response = context.getNextToken();
		if (response == null) {
			processor.reply(CONTINUE);
			response = processor.readLine();
			if (response == null) {
				return; // connection closed
			}
			response = response.trim();
		}
		if (response.equals("*")) {
			processor.replyErr("Authentication cancelled");
			return;
		}
		byte[] decoded;
		try {
			decoded = Sasl.decodeResponse(response);
		} catch (IllegalArgumentException e) {
			processor.replyErr("Invalid base64 in the AUTH response");
			return;
		}
		String[] credentials = Sasl.parsePlain(decoded);
		if (credentials == null) {
			processor.replyLoginResult(Pop3RequestProcessor.LoginResult.FAILED);
			return;
		}
		processor.replyLoginResult(processor.login(credentials[0], credentials[1]));
	}
}
