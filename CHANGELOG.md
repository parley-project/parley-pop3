# Changelog

## parley-pop3 2.0.0 (unreleased)

### Changed (2026-11-10: logging through System.Logger, To-Do 19.5)

- **Logging through `System.Logger`** (parley-core): `Pop3RequestProcessor` logs under its class
  name (no longer `Pop3RequestProcessor`); levels are set per object with `setLogLevel`. `Pop3Server` no longer calls
  `finishInit()` (removed from parley-mail), so it no longer sets INFO on itself.

### Changed (2026-11-06: users, logins and permissions come from parley-iam)

- **parley-pop3 uses parley-iam and the new parley-net**: the server's user registry (`authenticationProvider`, or
  `setUserRegistry`; a parley-iam `UserRegistry`) checks every login and answers permission checks.
  `Pop3Command.READ_PERMISSION` and `WRITE_PERMISSION` are `ParleyPermission`s (`read`, `write`), and
  `getPermission()` returns one. The `maildrop` setting is a principal attribute.
- **Logins go through the session's `authenticate`** (parley-mail's `authenticate(user, password)` for USER/PASS,
  `Pop3SaslAuthenticator` for AUTH). A registry that can't answer gets `-ERR [SYS/TEMP]` for a login or a permission
  check, never a refusal.
- **APOP is a `ProofCredential`** checked with parley-net's `ApopVerifier` against the registry's plain text secret
  (`StoredCredentialType.PLAIN_TEXT`); the greeting's timestamp is sent when
  `getUserRegistry().supports(ProofCredential.class, PLAIN_TEXT)`.
- **Failed logins are counted and delayed by parley-net** (POP3's own counting and `loginFailedDelay` calls are
  gone); a wrong SCRAM proof, a malformed APOP and a user without `read` are counted too.
- **`Pop3SaslAuthenticator` extends parley-mail's `MailSaslAuthenticator`**; `Pop3RequestProcessor.newSaslAuthenticator()`
  returns one. `loginVerified(String)` is `loginVerified(Pop3SaslAuthenticator, String)` (SCRAM is verified by the
  authenticator that ran the exchange). `Pop3Server.getMaildropDirectory` takes a `ParleyPrincipal`.
- **INBOX `$group` rights** use the user registry's `findGroups(ParleyPrincipal)`, through parley-mail's
  `AbstractMailServer.findGroupNames`.


### Changed (2026-11-03: To-Do 15.3)

- **`main` registers each server's store bean**: while they run, the servers `main` runs have their stores as
  `us.bringardner.parley:type=MailStore,name=<id>` (pop3, pop3s) on the platform MBean server (parley-mail's
  `MailServerMain`), so their mailbox ACLs can be managed without an application.

### Changed (2026-11-03: the launcher moved to parley-mail-admin)

- **`Pop3Providers` (the providers pop3, pop3s) moved to parley-mail-admin** with the launcher, which runs the mail suite; this module
  registers no server providers. Its `main` runs its servers alone in one process with parley-net's `ServerMain.run`
  (its settings file and the command line, as system properties; the implicit TLS server only when there is a TLS
  key store), not through the launcher.

### Changed (2026-11-02: IPrincipal split and mailbox ACLs, To-Do 3.2 and 3.4)

- **APOP is checked by the access control list** (`IBaseServer.checkApopLogin`, parley-net 2.0.0), which no longer
  hands out the password.
- **The INBOX's rights apply** (RFC 4314, the ACL IMAP uses): RETR and TOP need `r`, DELE `t` and `e`; RETR sets
  `\Seen` only with `s` (so no longer for a user without WRITE). `Pop3RequestProcessor.hasInboxRights(rights)`.
- **The greeting's APOP timestamp is sent only when the access control list can check APOP** (`supportsApop`).

### Changed (2026-11-02: durations, To-Do 2.19)

- **`<prefix>.autologout` is a duration** (`10m`, `15m`; a bare number is still ms), read by parley-mail.

### Changed (2026-11-01: parley.servers says which servers run, To-Do 2.12)

- **`pop3s` runs on 995 when `parley.servers` lists it** and it has a TLS key store (skipped with a warning while it
  has none); `parley.mail.pop3s.port` is only its port. **`parley.mail.pop3.secure` is no longer read** (a warning
  says so): it turned POP3 off and POP3S on, two settings for one thing. `Pop3Server.main` runs `pop3` and `pop3s`.

### Changed (2026-10-26: each server alone, shared assets carry their own settings)

- **Each POP3 server reads its own settings under its own prefix**: `autologout`, `requireTls` and `utf8Downgrade`
  are `parley.mail.pop3s.*` for POP3S (they used to be read as `parley.mail.pop3.*` for both). The providers make
  each server from its own configuration.

### Added

- **The settings this module reads are listed** in `META-INF/parley/settings.list`, so a Parley server's
  start-up check (parley-net's `KnownSettings`, To-Do 2.16) knows them and warns only about a setting
  nothing reads. A test checks the list has every name the code here reads.
- **`Pop3Providers`**: parley-net `ServerProvider`s `pop3` and `pop3s` (implicit TLS), so POP3 runs alone or with other
  servers in one process (parley-net's `ServerMain`), and is managed with JMX. `Pop3Server.main` runs them with
  `ServerMain.launch`: settings from `parley-mail.properties`, then `parley-pop3.properties`; `pop3s` runs when
  `parley.mail.pop3s.port` is set. Users and the TLS key store can come from the suite's `parley.mail.users` and
  `parley.mail.tls.*` (parley-mail).

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
