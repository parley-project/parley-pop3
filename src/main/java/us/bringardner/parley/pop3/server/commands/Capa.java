package us.bringardner.parley.pop3.server.commands;

import java.io.IOException;

import us.bringardner.parley.net.capability.CapabilityRegistry;
import us.bringardner.parley.net.server.ICommandProcessor;
import us.bringardner.parley.net.server.IRequestContext;
import us.bringardner.parley.pop3.server.Pop3RequestProcessor;


/**
 * CAPA (RFC 2449): list the server's capabilities. Valid in every state; the
 * login capabilities are listed only before login.
 */
public class Capa extends NoAuthReqBaseCommand {

	private static final long serialVersionUID = 1L;

	public Capa() {
		super(CAPA);
	}

	@Override
	public java.util.Set<Pop3RequestProcessor.State> getValidStates() {
		return BEFORE_UPDATE;
	}

	private static Pop3RequestProcessor pop3(ICommandProcessor p) {
		return (Pop3RequestProcessor) p;
	}

	/** The login capabilities are offered only before login, and not while TLS must come first. */
	private static boolean canLogin(ICommandProcessor p) {
		return pop3(p).getState() == Pop3RequestProcessor.State.AUTHORIZATION && !pop3(p).isLoginBlockedUntilTls();
	}

	/** What a POP3 server offers, and when (RFC 2449, RFC 5034, RFC 2595, RFC 6856). */
	private static final CapabilityRegistry CAPABILITIES = new CapabilityRegistry()
			.add(TOP)
			.add(UIDL)
			.add("RESP-CODES")
			.add("AUTH-RESP-CODE")
			.add("PIPELINING")
			.add(UTF8, USER) // RFC 6856: UTF8 command and UTF-8 user names and passwords
			.addWhen(Capa::canLogin, USER)
			.addDynamicRequireParams("SASL", p -> canLogin(p) ? Auth.mechanisms(pop3(p)) : null)
			.addWhen(p -> {
				Pop3RequestProcessor processor = pop3(p);
				return processor.getState() == Pop3RequestProcessor.State.AUTHORIZATION && !processor.isTls()
						&& !processor.isUtf8Mode() && processor.getPop3Server().isTlsAvailable();
			}, STLS)
			.add("IMPLEMENTATION", "BjlEmail-Pop3");

	@Override
	public void execute(Pop3RequestProcessor processor, IRequestContext context) throws IOException {
		processor.replyMultiLine("Capability list follows", CAPABILITIES.resolve(processor).toLines());
	}
}
