# Changelog

## parley-pop3 2.0.0 (unreleased)

### Changed

- **`Pop3Server.main` runs the server as a service** with parley-net's `ServerMain.run`: settings from the command
  line (which wins) and `parley-pop3.properties` in Parley's administrator folder (`/etc/parley`, or the file the server's
  `configFile` property names); it stops on SIGTERM; a failed start now exits with 1 (it printed "started" and exited 0).

### Changed (needs a configuration change)

- **Property names** (Parley 2.0.0; the old names are no longer read): `JPop3.*` is `parley.mail.pop3.*`
  (`parley.mail.pop3.autologout`, `.requireTls`, `.utf8Downgrade`, `.secure`, `.port`), and the maildrop root is
  the shared `parley.mail.root` / `parley.mail.fileSource`. `JPop3.properties` is `parley.mail.pop3.configFile`.
- A server's own settings are read with its prefix, `parley.mail.pop3` (was `Pop3Server`): the key store
  (`parley.mail.pop3.keyStoreName` ...), limits and access control (`parley.mail.pop3.authenticationProvider`,
  `parley.mail.pop3.userFile`). A server with implicit TLS uses `parley.mail.pop3s` (port
  `parley.mail.pop3s.port`, 995).
- `JPop3.loginFailureDelay` is now the server's `loginFailureDelay` (`parley.mail.pop3.loginFailureDelay`):
  `LOGIN_FAILURE_DELAY_PROP` is gone.

### Fixed

- Opening a maildrop (at login) no longer reads every message to learn its size: the size comes
  from the store (which keeps CRLF line ends), and only each message's last byte is read, to add
  the CRLF RETR sends after a last line without one.

## parley-pop3 1.1.0 (unreleased)

### Added

- When the access control list can't answer (its database is down), PASS, APOP and AUTH get `-ERR [SYS/TEMP]`
  (try again later), not "invalid user name or password", and it isn't counted as a failed login
  (`LoginResult.UNAVAILABLE`). `Pop3Server.deliver` fails instead of guessing the user's maildrop.
- **SCRAM-SHA-256 and SCRAM-SHA-1** for `AUTH`, offered in CAPA when the server's access control list keeps SCRAM
  credentials for every user (parley-net 1.1.0: `FileBasedAcl --scram`, or parley-net-acl-jdbc). The user is
  logged in with `Pop3RequestProcessor.loginVerified(user)` after the proof, with the same permission checks as a password login.
- **Several servers on one store** (parley-mail 1.1.0's `parley.mail.sharedStore`): the maildrop lock (one session
  per maildrop, `-ERR [IN-USE]`) reaches every server sharing the store, through parley-net's locks. At QUIT, the
  deleted messages are removed only while the session still holds the lock (`Maildrop.commit(NamedLock)`).

### Changed (no code change needed)

- The maildrop is POP3's view of the INBOX of the user's parley-mail store (the mailbox IMAP serves), not its own
  reading of the directory: the messages, their surrogates and deletions are the store's. QUIT expunges the deleted
  messages from INBOX, so IMAP sessions are told at once; RETR sets `\Seen`, so IMAP shows the message as read
  (TOP doesn't). The INBOX gets the store's hidden index on first use. `Maildrop` keeps its methods, plus
  `markSeen`, `close`, `getStore` and `Entry.getMessage`. UIDLs are unchanged (from the file names).
- The test classes are no longer published as a tests jar (`parley-pop3-<version>-tests.jar`): no other module
  uses them. The tests use parley-net's tests jar for their keystore and parley-mail's for the shared test users
  (`MailTestAcl.txt`, was `Pop3TestAcl.txt` here) and helpers. That the maildrop is the INBOX of the user's
  parley-mail store is tested against the store, without an IMAP server.

### Removed (needs a code change)

- `Pop3Server.deliver(user, message)`, `Maildrop.deliver(...)` and `Maildrop.MESSAGE_SUFFIX`: POP3 never writes
  messages. Deliver through the user's parley-mail store, `MailStore.deliver(content, junk)`, as SMTP does; it
  stores the message with CRLF line ends and indexes it, so IMAP and POP3 see the same message at once.

## parley-pop3 1.0.0 (2026-10-16)

The POP3 server of BjlEmail (`us.bringardner:bjl_email` 1.0.0-SNAPSHOT, never released) is now
**parley-pop3**, part of the Parley library family.

### Changed (needs a code change)

- Maven coordinates: `us.bringardner.parley:parley-pop3`.
- Packages: `us.bringardner.net.pop3` (and `.server`, `.server.commands`) is now `us.bringardner.parley.pop3`.
- Module name (`Automatic-Module-Name`): `us.bringardner.parley.pop3`.
- Dependencies: `parley-mail` (messages) and `parley-net` (server framework) instead of
  `bjl_email`'s own packages and `bjl_net_framework`; no DNS, IMAP or SMTP code.
- The test classes are also published as `parley-pop3-<version>-tests.jar` for parley-imap's tests.

### Internal

- The session state is a parley-net `StateMachine`, and commands declare their states with
  `getValidStates()` (`Pop3Command` is an `IStatefulCommand`). `isValidIn(State)` is still there,
  now a default method that uses `getValidStates()`; a command that overrides it is still honoured.
- CAPA is built from a parley-net `CapabilityRegistry` and AUTH runs through `SaslServerDriver`,
  with `Pop3SaslAuthenticator` supplying the login. The replies, error codes and the advertised
  `SASL PLAIN` are the same as before. Requires a parley-net that has `StateMachine`, the
  `capability` package and the `sasl` package.
- Dot stuffing is parley-io's `DotStuffingOutputStream`; this module's class of the same name now
  only adds the `Counter` that gives the size LIST and STAT report. Requires a parley-io that has
  `DotStuffingOutputStream`.

### Unchanged

- The `Pop3Server.*` configuration properties and the maildrop layout on disk.
