package us.bringardner.parley.pop3.server;

import java.util.Map;

import us.bringardner.parley.net.server.AbstractCommandFactory;
import us.bringardner.parley.net.server.ICommand;
import us.bringardner.parley.pop3.server.commands.Apop;
import us.bringardner.parley.pop3.server.commands.Auth;
import us.bringardner.parley.pop3.server.commands.Capa;
import us.bringardner.parley.pop3.server.commands.Dele;
import us.bringardner.parley.pop3.server.commands.List;
import us.bringardner.parley.pop3.server.commands.Noop;
import us.bringardner.parley.pop3.server.commands.Pass;
import us.bringardner.parley.pop3.server.commands.Quit;
import us.bringardner.parley.pop3.server.commands.Retr;
import us.bringardner.parley.pop3.server.commands.Rset;
import us.bringardner.parley.pop3.server.commands.Stat;
import us.bringardner.parley.pop3.server.commands.Stls;
import us.bringardner.parley.pop3.server.commands.Top;
import us.bringardner.parley.pop3.server.commands.Uidl;
import us.bringardner.parley.pop3.server.commands.User;
import us.bringardner.parley.pop3.server.commands.Utf8;

/**
 * Maps POP3 command names to their command classes (as FtpCommandFactory does
 * for FTP). Commands can be replaced or added with {@link #addCommand(ICommand)}.
 */
public class Pop3CommandFactory extends AbstractCommandFactory {

	private static final long serialVersionUID = 1L;

	private static final Map<String, ICommand> commands = newRegistry();

	static {
		// AUTHORIZATION state (RFC 1939, RFC 2595, RFC 5034, RFC 6856)
		addCommand(new User());
		addCommand(new Pass());
		addCommand(new Apop());
		addCommand(new Auth());
		addCommand(new Stls());
		addCommand(new Utf8());
		// any state (RFC 2449)
		addCommand(new Capa());
		addCommand(new Quit());
		// TRANSACTION state
		addCommand(new Stat());
		addCommand(new List());
		addCommand(new Retr());
		addCommand(new Dele());
		addCommand(new Noop());
		addCommand(new Rset());
		addCommand(new Top());
		addCommand(new Uidl());
	}

	public Pop3CommandFactory() {
		super(commands);
	}

	public static void addCommand(ICommand cmd) {
		register(commands, cmd);
	}
}
