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
> the `Pop3Server.*` properties are unchanged. In 2.0.0 every property was renamed (`parley.mail.pop3.*`,
> `parley.mail.root`): see the CHANGELOG.

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

Supported: USER, PASS, APOP, QUIT, STAT, LIST, RETR, DELE, NOOP, RSET, TOP, UIDL (RFC 1939); CAPA and response codes (RFC 2449); STLS (RFC 2595); AUTH PLAIN and SCRAM (RFC 5034); UTF8 (RFC 6856). SCRAM-SHA-256 and SCRAM-SHA-1 (RFC 5802, 7677) are offered when the access control list keeps SCRAM credentials for every user (parley-net's `FileBasedAcl --scram`, or parley-net-acl-jdbc).

```java
Pop3Server server = new Pop3Server();          // port 110
server.setMaildropRoot(rootFileSource);
server.start();
```

POP3 never writes messages. Mail is delivered by SMTP, or by your code through the user's parley-mail store:
`new MailStore(server.getMaildropDirectory("tony"), MailboxRegistry.get())`, `init(false)`, then
`deliver(content, false)`.

Or from the command line: `java us.bringardner.parley.pop3.server.Pop3Server -Dparley.mail.pop3.port=1110 -Dparley.mail.root=/var/mail/pop3`

`Pop3Server.main` runs `pop3` and, when `parley.mail.pop3s.port` is set, `pop3s` (with implicit TLS), with
parley-net's `ServerMain.launch`: settings from `parley-mail.properties` then `parley-pop3.properties` in
the settings folder, the JVM's `-D` options and the command line; a JMX bean per server. To run POP3
with other servers in one process, use the providers `pop3` and `pop3s` (`Pop3Providers`) with
parley-net's launcher; see parley-mail's README.

### Users and maildrops

- **Users** come from the access control list, set up as for FtpServer with the `parley.mail.pop3.authenticationProvider` property (for example `us.bringardner.parley.net.server.FileBasedAcl` with `parley.mail.pop3.userFile`). See `MailTestAcl.txt` in parley-mail's test resources (the user file every mail protocol's tests share).
- **Permissions:** READ is needed to log in and read mail; WRITE to delete it.
- **Maildrops:** each user's maildrop is a directory under the root, named by the principal's `maildrop` parameter (default: the user name). Several users can share one maildrop.
- **Messages** are files in the maildrop, in name order. Names starting with "." are ignored: delivery (parley-mail's `MailStore.deliver`) writes a hidden temp file and renames it, so a session never sees a message half written.
- **APOP** needs the user's password stored in plain text in the access list; users with `{PBKDF2}` hashes use USER/PASS or AUTH PLAIN.

### Configuration

Each setting can be passed as a system property or set with the matching `Pop3Server` setter.

| Property | Default | Meaning |
|---|---|---|
| `parley.mail.pop3.port` | 110 | Port (used by `Pop3Server.main`) |
| `parley.mail.pop3s.port` | off | Port of POP3 with implicit TLS (`pop3s`); set it (995) to turn it on |
| `parley.mail.pop3.secure` | false | The older way: `main` runs only POP3 with implicit TLS, on `parley.mail.pop3s.port` (995) |
| `parley.mail.root` | `/pop3` (`C:/pop3` on Windows) | Maildrop root |
| `parley.mail.fileSource` | default factory | FileSource factory for the root |
| `parley.mail.pop3.autologout` | 600000 ms | Idle sessions are closed. RFC 1939 requires at least 10 minutes. `setAutologout()` |
| `parley.mail.pop3.loginFailureDelay` | 1000 ms | Delay before replying to a failed login. `setLoginFailureDelay()` |
| `parley.mail.pop3.requireTls` | false | Refuse USER, PASS, APOP and AUTH until STLS (or implicit TLS). `setRequireTls()` |
| `parley.mail.pop3.utf8Downgrade` | `surrogate` | What a client that didn't send UTF8 gets for a message with UTF-8 headers: the RFC 6858 surrogate, or `reject` (`-ERR [UTF8]`). `setUtf8Downgrade()` |
| `parley.mail.pop3.keyStoreName`, `parley.mail.pop3.keyStorePassword`, `parley.mail.pop3.keyStoreType` | none | Key store for STLS and implicit TLS. STLS is offered only when one is configured. |
| `parley.mail.pop3.*` | | The listener's own settings use its prefix: the key store, the limits of parley-core's `AbstractCoreServer` (`maxConnections`, `maxLoginAttempts`, ...) and the access control list (`authenticationProvider`, `userFile`). A server with implicit TLS uses `parley.mail.pop3s`. |

### Behaviour notes

- **One session per maildrop** (RFC 1939), across every server sharing the store. A second login gets `-ERR [IN-USE]`. The lock is released at QUIT or when the connection drops; a crashed server's is freed with it (the store's own locks) or when its lease runs out (leases).
- **Deletions** are applied only at QUIT. If the connection drops, nothing is deleted.
- **Snapshot:** a session sees the messages that were in the maildrop when it logged in. Mail delivered later appears in the next session.
- **The shared store:** the maildrop is the INBOX of the user's parley-mail store, the mailbox IMAP serves. QUIT expunges the deleted messages from it (IMAP sessions are told at once), and RETR sets `\Seen`, so IMAP shows mail read over POP3 as read (TOP doesn't).
- **Sizes** in STAT and LIST are exact: the octets RETR sends, with CRLF line ends, before byte-stuffing. They are computed when the maildrop is opened.
- **Streaming:** RETR and TOP stream from the maildrop, so messages can be of any size.
- **Failed logins** wait `parley.mail.pop3.loginFailureDelay` ms. The connection is closed after 3 failures. Unknown users get the same replies as wrong passwords.
- **Several servers on one store**: set `parley.mail.sharedStore` (`off`, the default, `host` or `cluster`; see parley-mail). The maildrop lock is a lock in the store's lock domain, so it reaches the other servers; at QUIT, messages are deleted only while the session still holds it.

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
