package us.bringardner.parley.pop3.server.commands;

import java.io.IOException;

import us.bringardner.parley.net.server.AbstractCommand;
import us.bringardner.parley.net.server.ICommandProcessor;
import us.bringardner.parley.net.server.IPermission;
import us.bringardner.parley.net.server.IRequestContext;
import us.bringardner.parley.pop3.POP3;
import us.bringardner.parley.pop3.server.Maildrop;
import us.bringardner.parley.pop3.server.Pop3Command;
import us.bringardner.parley.pop3.server.Pop3RequestProcessor;
import us.bringardner.parley.pop3.server.Pop3RequestProcessor.State;

/**
 * Base for POP3 commands. By default a command is valid in the TRANSACTION state
 * and needs the READ permission.
 */
public abstract class BaseCommand extends AbstractCommand implements Pop3Command, POP3 {

	private static final long serialVersionUID = 1L;


	public BaseCommand(String command) {
		super(command);
	}

	@Override
	public void execute(ICommandProcessor processor, IRequestContext context) throws IOException {
		execute((Pop3RequestProcessor) processor, context);
	}

	@Override
	public IPermission getPermission() {
		return READ_PERMISSION;
	}

	@Override
	public boolean isValidIn(State state) {
		return state == State.TRANSACTION;
	}

	/**
	 * Parse a message number argument (RFC 1939: a message number, not one marked
	 * deleted). Replies -ERR and returns null if it isn't valid.
	 */
	protected static Maildrop.Entry message(Pop3RequestProcessor processor, String arg) throws IOException {
		Maildrop drop = processor.getMaildrop();
		int number = parseNumber(arg);
		if (number < 1) {
			processor.replyErr("Invalid message number");
			return null;
		}
		Maildrop.Entry e = drop.get(number);
		if (e == null) {
			processor.replyErr(drop.exists(number) ? "Message " + number + " already deleted" : "No such message");
		}
		return e;
	}

	/** A non-negative decimal number, or -1. */
	protected static int parseNumber(String arg) {
		if (arg == null || arg.isEmpty() || arg.length() > 9) {
			return -1;
		}
		for (int i = 0; i < arg.length(); i++) {
			char c = arg.charAt(i);
			if (c < '0' || c > '9') {
				return -1;
			}
		}
		return Integer.parseInt(arg);
	}

	/** The message number of an entry. */
	protected static int numberOf(Pop3RequestProcessor processor, Maildrop.Entry e) {
		return processor.getMaildrop().getEntries().indexOf(e) + 1;
	}
}
