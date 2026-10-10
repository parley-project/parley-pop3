# Changelog

## parley-pop3 1.0.0 (unreleased)

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

### Unchanged

- The `Pop3Server.*` configuration properties and the maildrop layout on disk.
