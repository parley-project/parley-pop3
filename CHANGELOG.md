# Changelog

## parley-pop3 1.1.0 (unreleased)

### Added

- **SCRAM-SHA-256 and SCRAM-SHA-1** for `AUTH`, offered in CAPA when the server's access control list keeps SCRAM
  credentials for every user (parley-net 1.1.0: `FileBasedAcl --scram`, or parley-net-acl-jdbc). The user is
  logged in with `Pop3RequestProcessor.loginVerified(user)` after the proof, with the same permission checks as a password login.
- **Several servers on one store** (parley-mail 1.1.0's `parley.mail.sharedStore`): the maildrop lock (one session
  per maildrop, `-ERR [IN-USE]`) reaches every server sharing the store, through parley-net's locks. At QUIT, the
  deleted messages are removed only while the session still holds the lock (`Maildrop.commit(NamedLock)`).

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
