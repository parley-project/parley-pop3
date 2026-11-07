package us.bringardner.parley.pop3.server;

import java.io.IOException;
import java.util.Set;

import us.bringardner.parley.iam.ParleyPermission;
import us.bringardner.parley.iam.permission.BasicParleyPermission;
import us.bringardner.parley.iam.permission.StandardPermissions;
import us.bringardner.parley.net.server.IRequestContext;
import us.bringardner.parley.net.server.IStatefulCommand;

/**
 * A POP3 command. Like FtpCommand, permissions are checked by the server's user
 * registry (parley-iam): read is needed to open a maildrop, write to delete messages.
 */
public interface Pop3Command extends IStatefulCommand<Pop3RequestProcessor.State> {

	/** read on the server as a whole: opening a maildrop and reading its messages */
	ParleyPermission READ_PERMISSION = new BasicParleyPermission(StandardPermissions.READ, null);
	/** write on the server as a whole: deleting messages */
	ParleyPermission WRITE_PERMISSION = new BasicParleyPermission(StandardPermissions.WRITE, null);

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
