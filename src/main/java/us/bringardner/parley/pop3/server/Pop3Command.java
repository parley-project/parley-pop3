package us.bringardner.parley.pop3.server;

import java.io.IOException;
import java.util.Set;

import us.bringardner.parley.net.server.IPermission;
import us.bringardner.parley.net.server.IRequestContext;
import us.bringardner.parley.net.server.IStatefulCommand;
import us.bringardner.parley.net.server.Permission;

/**
 * A POP3 command. Like FtpCommand, permissions are checked against the access
 * control list: READ is needed to open a maildrop, WRITE to delete messages.
 */
public interface Pop3Command extends IStatefulCommand<Pop3RequestProcessor.State> {

	IPermission READ_PERMISSION = new Permission("READ");
	IPermission WRITE_PERMISSION = new Permission("WRITE");

	void execute(Pop3RequestProcessor processor, IRequestContext context) throws IOException;

	/** The session states the command may be used in (RFC 1939 section 3). */
	@Override
	Set<Pop3RequestProcessor.State> getValidStates();

	/** True if the command may be used in this session state; by default, if it is in {@link #getValidStates()}. */
	default boolean isValidIn(Pop3RequestProcessor.State state) {
		Set<Pop3RequestProcessor.State> valid = getValidStates();
		return valid == null || valid.isEmpty() || valid.contains(state);
	}
}
