package us.bringardner.parley.pop3.server.commands;

import java.util.Set;

import us.bringardner.parley.pop3.server.Pop3RequestProcessor.State;

/** Base for commands of the AUTHORIZATION state, which need no login. */
public abstract class NoAuthReqBaseCommand extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public NoAuthReqBaseCommand(String command) {
		super(command);
	}

	@Override
	public boolean requiresAuthorization() {
		return false;
	}

	@Override
	public Set<State> getValidStates() {
		return AUTHORIZATION_ONLY;
	}
}
