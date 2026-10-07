# parley-pop3

A POP3 server for **Parley**, a family of Java libraries for implementing internet protocols:
RFC 1939 with CAPA and response codes (RFC 2449), STLS (RFC 2595), AUTH PLAIN (RFC 5034) and UTF8
(RFC 6856). It is built on `parley-net` like the FTP server in `parley-ftp`, and serves the
`parley-mail` store, so POP3 and IMAP (`parley-imap`) see the same mail.

Requires Java 11 or later. Depends on `parley-mail` and `parley-net` (which bring in
`parley-files`, `parley-core` and `parley-io`).

```xml
<dependency>
    <groupId>us.bringardner.parley</groupId>
    <artifactId>parley-pop3</artifactId>
    <version>1.0.0</version>
</dependency>
```

> parley-pop3 was split out of `us.bringardner:bjl_email` (BjlEmail). `us.bringardner.net.pop3` is now
> `us.bringardner.parley.pop3`. Property names that start with a class name change with the package;
> the `Pop3Server.*` properties are unchanged.

## Build

```
mvn package
```

Tests run with a 64 MB heap, so message bodies must stay out of memory.

## The server

The design follows parley-ftp:

| parley-ftp | parley-pop3 | Role |
|---|---|---|
| `FtpServer` | `Pop3Server` | Accepts connections, holds the configuration |
| `FtpRequestProcessor` | `Pop3RequestProcessor` | Runs one session |
| `FtpCommandFactory` | `Pop3CommandFactory` | Maps command names to command classes |
| `FtpCommand`, `commands.*` | `Pop3Command`, `commands.*` | One class per command |
| `FTP` | `POP3` | Protocol constants |

Supported: USER, PASS, APOP, QUIT, STAT, LIST, RETR, DELE, NOOP, RSET, TOP, UIDL (RFC 1939); CAPA and response codes (RFC 2449); STLS (RFC 2595); AUTH PLAIN (RFC 5034); UTF8 (RFC 6856).

```java
Pop3Server server = new Pop3Server();          // port 110
server.setMaildropRoot(rootFileSource);
server.start();

server.deliver("tony", message);               // local delivery, e.g. from SMTP
```

Or from the command line: `java us.bringardner.parley.pop3.server.Pop3Server -DJPop3.port=1110 -DJPop3.root=/var/mail/pop3`

### Users and maildrops

- **Users** come from the access control list, set up as for FtpServer with the `Pop3Server.AuthenticationProvider` property (for example `us.bringardner.parley.net.server.FileBasedAcl` with `Pop3Server.userFile`). See `src/test/resources/Pop3TestAcl.txt`.
- **Permissions:** READ is needed to log in and read mail; WRITE to delete it.
- **Maildrops:** each user's maildrop is a directory under the root, named by the principal's `maildrop` parameter (default: the user name). Several users can share one maildrop.
- **Messages** are files in the maildrop, in name order. Names starting with "." are ignored: `Maildrop.deliver` writes a hidden temp file and renames it, so a session never sees a message half written.
- **APOP** needs the user's password stored in plain text in the access list; users with `{PBKDF2}` hashes use USER/PASS or AUTH PLAIN.

### Configuration

Each setting can be passed as a system property or set with the matching `Pop3Server` setter.

| Property | Default | Meaning |
|---|---|---|
| `JPop3.port` | 110 (995 if secure) | Port (used by `Pop3Server.main`) |
| `JPop3.secure` | false | Implicit TLS |
| `JPop3.root` | `/pop3` (`C:/pop3` on Windows) | Maildrop root |
| `JPop3.fileSource` | default factory | FileSource factory for the root |
| `JPop3.autologout` | 600000 ms | Idle sessions are closed. RFC 1939 requires at least 10 minutes. `setAutologout()` |
| `JPop3.loginFailureDelay` | 1000 ms | Delay before replying to a failed login. `setLoginFailureDelay()` |
| `JPop3.requireTls` | false | Refuse USER, PASS, APOP and AUTH until STLS (or implicit TLS). `setRequireTls()` |
| `JPop3.utf8Downgrade` | `surrogate` | What a client that didn't send UTF8 gets for a message with UTF-8 headers: the RFC 6858 surrogate, or `reject` (`-ERR [UTF8]`). `setUtf8Downgrade()` |
| `Pop3Server.KeyStoreName`, `Pop3Server.KeyStorePassword`, `Pop3Server.KeyStoreType` | none | Key store for STLS and implicit TLS. STLS is offered only when one is configured. |

### Behaviour notes

- **One session per maildrop** (RFC 1939). A second login gets `-ERR [IN-USE]`. The lock is released at QUIT or when the connection drops.
- **Deletions** are applied only at QUIT. If the connection drops, nothing is deleted.
- **Snapshot:** a session sees the messages that were in the maildrop when it logged in. Mail delivered later appears in the next session.
- **Sizes** in STAT and LIST are exact: the octets RETR sends, with CRLF line ends, before byte-stuffing. They are computed when the maildrop is opened.
- **Streaming:** RETR and TOP stream from the maildrop, so messages can be of any size.
- **Failed logins** wait `JPop3.loginFailureDelay` ms. The connection is closed after 3 failures. Unknown users get the same replies as wrong passwords.
- **Locks** are held in the server process, so only one Pop3Server should serve a maildrop root.

### Internationalized mail (RFC 6856)

- **UTF8 command:** CAPA advertises `UTF8 USER`. A client that sends `UTF8` before logging in gets messages exactly as stored, including UTF-8 headers (RFC 6532). STLS is refused after UTF8, as RFC 6856 allows.
- **Other clients** never see raw UTF-8 in headers. A message with UTF-8 headers (in the message header or in any MIME part header) is sent as its RFC 6858 surrogate, built by `Downgrader`:
  - an address with a non-ASCII mailbox becomes `"José <josé@exämple.com>" <invalid@internationalized-address.invalid>`;
  - Subject and other unstructured fields are RFC 2047-encoded;
  - Content-Type and Content-Disposition parameters are RFC 2231-encoded, so attachment names survive (RFC 6858 allows dropping them);
  - any other field that isn't ASCII (Message-ID, Received, ...) is removed.

  Bodies are not changed and are still streamed from the file. LIST reports the surrogate's exact size, TOP counts the surrogate's lines, and UIDs are the same in both modes. The stored message is never modified.
- **Logins** may use UTF-8 user names and passwords (USER, PASS, APOP and AUTH PLAIN). They are prepared with SASLprep (RFC 4013), so composed and decomposed forms match. Invalid UTF-8 and prohibited characters fail the login. Passwords in the access list should be stored in their prepared (NFC) form.
- **Not implemented:** the optional LANG command (RFC 6856 section 3).
