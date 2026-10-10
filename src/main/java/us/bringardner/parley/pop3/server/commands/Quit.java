package us.bringardner.parley.pop3.server.commands;

import java.io.IOException;

import us.bringardner.parley.net.server.IRequestContext;
import us.bringardner.parley.pop3.server.Pop3RequestProcessor;


/**
 * QUIT (RFC 1939 sections 5 and 6). In the TRANSACTION state this enters the
 * UPDATE state: messages marked deleted are removed and the maildrop is
 * released. Then the connection is closed.
 */
public class Quit extends NoAuthReqBaseCommand {

	private static final long serialVersionUID = 1L;

	public Quit() {
		super(QUIT);
	}

	@Override
	public java.util.Set<Pop3RequestProcessor.State> getValidStates() {
		return BEFORE_UPDATE;
	}

	@Override
	public void execute(Pop3RequestProcessor processor, IRequestContext context) throws IOException {
		String name = processor.getServer().getName();
		try {
			if (processor.getState() == Pop3RequestProcessor.State.TRANSACTION) {
				int remaining = processor.getMaildrop().getMessageCount();
				int failed = processor.update();
				if (failed == 0) {
					processor.replyOk(name + " POP3 server signing off (" + remaining + " messages left)");
				} else {
					processor.replyErr(CODE_SYS_TEMP + " " + failed + " deleted messages were not removed");
				}
			} else {
				processor.replyOk(name + " POP3 server signing off");
			}
		} catch (java.net.SocketException e) {
			// the client went away first
		}
		processor.stop();
	}
}
