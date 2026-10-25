package us.bringardner.parley.pop3.server;

import us.bringardner.parley.io.IoUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.Downgrader;
import us.bringardner.parley.mail.Message;
import us.bringardner.parley.mail.store.MailStore;
import us.bringardner.parley.mail.store.Mailbox;
import us.bringardner.parley.mail.store.MailboxConstants;
import us.bringardner.parley.mail.store.MailboxRegistry;
import us.bringardner.parley.mail.store.MessageInfo;

/**
 * A user's maildrop: POP3's view of the INBOX of the user's parley-mail store (a {@link Mailbox}),
 * the same mailbox IMAP serves. What is POP3's own sits here on top of it; what every protocol
 * shares (the message files, their index, the surrogates, the locks) is the store's. POP3 never
 * writes messages: mail is delivered through the store ({@link MailStore#deliver}, as SMTP's local
 * delivery does).
 * <p>
 * A Maildrop is a snapshot taken when the session logs in (RFC 1939): messages
 * are numbered 1..n in name order (delivery order), mail delivered later is seen in the next
 * session, and deletions are only marks until {@link #commit()} (QUIT), which expunges them
 * from INBOX. RETR sets \Seen ({@link #markSeen}), saved when the maildrop is closed.
 * Messages are only read through streams, so they can be of any size.
 * <p>
 * A maildrop opened for a session that is not in UTF-8 mode (RFC 6856) presents
 * a message with non-ASCII headers (RFC 6532) as its RFC 6858 surrogate (see
 * {@link Downgrader}): RETR and TOP send the surrogate and LIST reports its size.
 */
public class Maildrop implements java.io.Closeable {

	/** The flag RETR sets, so IMAP shows a message read over POP3 as read. */
	public static final String SEEN = "\\Seen";

	/** How many deletions are made at once at QUIT, with the session's lock checked before each batch. */
	private static final int EXPUNGE_BATCH = 100;

	/** One message of the maildrop. */
	public static final class Entry {
		private final MessageInfo message;
		private final FileSource file;
		private final long size;
		private final String uid;
		private final boolean surrogate;
		private boolean deleted;

		Entry(MessageInfo message, FileSource file, long size, String uid, boolean surrogate) {
			this.message = message;
			this.file = file;
			this.size = size;
			this.uid = uid;
			this.surrogate = surrogate;
		}

		/**
		 * True if the message has non-ASCII headers and the session isn't in UTF-8
		 * mode, so it is sent as its surrogate (or refused with [UTF8]).
		 */
		public boolean isSurrogate() {
			return surrogate;
		}

		/** The stored message file. */
		public FileSource getFile() {
			return file;
		}

		/**
		 * Size in octets as sent by RETR (CRLF line ends, before byte-stuffing); for
		 * a surrogate, the surrogate's size.
		 */
		public long getSize() {
			return size;
		}

		/** The unique id for UIDL (RFC 1939 section 7), stable across sessions: from the file name. */
		public String getUid() {
			return uid;
		}

		public boolean isDeleted() {
			return deleted;
		}

		/** The message in the INBOX mailbox (its IMAP UID, flags, ...). @since 1.1.0 */
		public MessageInfo getMessage() {
			return message;
		}
	}

	private final FileSource directory;
	private final MailStore store;
	private final Mailbox inbox;
	private final long uidValidity;
	private final List<Entry> entries;
	private boolean closed;

	private Maildrop(FileSource directory, MailStore store, Mailbox inbox, List<Entry> entries) {
		this.directory = directory;
		this.store = store;
		this.inbox = inbox;
		this.uidValidity = inbox.getUidValidity();
		this.entries = entries;
	}

	/** Open a maildrop in UTF-8 mode (messages are presented as they are stored). */
	public static Maildrop open(FileSource directory) throws IOException {
		return open(directory, true);
	}

	/**
	 * Open a maildrop: the INBOX of the user's parley-mail store in {@code directory} (made if it
	 * doesn't exist yet), as it is now. Messages are numbered in name order, which is delivery
	 * order. {@link #close()} it when the session ends.
	 *
	 * @param utf8Mode false for a session that didn't send UTF8 (RFC 6856):
	 *                 messages with non-ASCII headers are presented as surrogates
	 */
	public static Maildrop open(FileSource directory, boolean utf8Mode) throws IOException {
		if (directory.exists() && !directory.isDirectory()) {
			throw new IOException("Not a directory: " + directory.getAbsolutePath());
		}
		MailStore store = new MailStore(directory, MailboxRegistry.get());
		store.init(false);
		Mailbox inbox = store.open(MailboxConstants.INBOX);
		try {
			// mail put into the directory by something other than the store is taken in now
			inbox.refresh(true);
			List<MessageInfo> messages = inbox.getMessages();
			messages.sort(Comparator.comparing(MessageInfo::getName));
			List<Entry> entries = new ArrayList<>(messages.size());
			for (MessageInfo m : messages) {
				boolean surrogate = !utf8Mode && m.isUtf8();
				long size = surrogate ? count(inbox.surrogate(m)).getCount() : sentSize(m);
				entries.add(new Entry(m, m.getFile(), size, uidFor(m.getName()), surrogate));
			}
			return new Maildrop(directory, store, inbox, entries);
		} catch (IOException | RuntimeException e) {
			MailboxRegistry.get().release(inbox);
			throw e;
		}
	}

	public FileSource getDirectory() {
		return directory;
	}

	/** The user's store, whose INBOX this is. @since 1.1.0 */
	public MailStore getStore() {
		return store;
	}

	/** All entries, including ones marked deleted, numbered from 1. */
	public List<Entry> getEntries() {
		return Collections.unmodifiableList(entries);
	}

	/** The number of messages not marked deleted. */
	public int getMessageCount() {
		int n = 0;
		for (Entry e : entries) {
			if (!e.deleted) {
				n++;
			}
		}
		return n;
	}

	/** The size in octets of the messages not marked deleted. */
	public long getTotalSize() {
		long n = 0;
		for (Entry e : entries) {
			if (!e.deleted) {
				n += e.size;
			}
		}
		return n;
	}

	/** True if the number is in 1..n (whether or not the message is deleted). */
	public boolean exists(int number) {
		return number >= 1 && number <= entries.size();
	}

	/** Message {@code number} (from 1), or null if there is none or it is marked deleted. */
	public Entry get(int number) {
		if (!exists(number)) {
			return null;
		}
		Entry e = entries.get(number - 1);
		return e.deleted ? null : e;
	}

	/** Mark a message deleted; false if there is no such message or it already is. */
	public boolean delete(int number) {
		Entry e = get(number);
		if (e == null) {
			return false;
		}
		e.deleted = true;
		return true;
	}

	/** Unmark every deleted message (RSET). */
	public void reset() {
		for (Entry e : entries) {
			e.deleted = false;
		}
	}

	/**
	 * Set \Seen on a message (after RETR), so IMAP shows it as read. Saved when the maildrop is
	 * closed. Nothing happens if the message is gone from INBOX meanwhile.
	 *
	 * @since 1.1.0
	 */
	public void markSeen(Entry e) {
		MessageInfo now = inbox.get(e.message.getUid());
		if (now != null) {
			inbox.storeFlags(now, Mailbox.FlagOp.ADD, Collections.singleton(SEEN), null);
		}
	}

	/**
	 * Remove the messages marked deleted (the UPDATE state after QUIT).
	 *
	 * @return the number of messages that could not be removed
	 */
	public int commit() {
		return commit(null);
	}

	/**
	 * Remove the messages marked deleted from INBOX (an expunge, which IMAP sessions are told about
	 * at once), for as long as the session still holds its lock: the lock is checked before each
	 * batch, and once it is lost (a server paused past its lease) nothing more is removed, as RFC
	 * 1939 asks of a session that didn't finish. Nothing is removed if the INBOX was deleted and
	 * made again since the session opened it (its messages are others now).
	 *
	 * @param lock the session's maildrop lock, or null
	 * @return the number of messages that could not be removed
	 */
	public int commit(us.bringardner.parley.net.lock.NamedLock lock) {
		List<Long> marked = new ArrayList<>();
		for (Entry e : entries) {
			if (e.deleted) {
				marked.add(e.message.getUid());
			}
		}
		int failed = 0;
		for (int i = 0; i < marked.size(); i += EXPUNGE_BATCH) {
			List<Long> batch = marked.subList(i, Math.min(marked.size(), i + EXPUNGE_BATCH));
			try {
				if (lock != null) {
					lock.checkValid();
				}
				inbox.expunge(batch, null, uidValidity);
			} catch (IOException | RuntimeException gone) {
				// the lock lost, or the store failed: this batch and the rest stay
				for (Long uid : marked.subList(i, marked.size())) {
					if (inbox.get(uid) != null) {
						failed++;
					}
				}
				return failed;
			}
			for (Long uid : batch) {
				if (inbox.get(uid) != null) {
					failed++; // still there: its file couldn't be removed, or the INBOX is another one now
				}
			}
		}
		return failed;
	}

	/** Save the flags set (\Seen) and let go of the INBOX. The maildrop can't be used afterwards. @since 1.1.0 */
	@Override
	public void close() throws IOException {
		if (closed) {
			return;
		}
		closed = true;
		try {
			inbox.saveIfDirty();
		} finally {
			MailboxRegistry.get().release(inbox);
		}
	}

	/**
	 * Write a whole message (for RETR) to a {@link DotStuffingOutputStream}: the
	 * stored message, or its surrogate.
	 */
	public void writeMessage(Entry e, OutputStream out) throws IOException {
		FileSource f = e.surrogate ? inbox.surrogate(e.message) : e.file;
		try (InputStream in = IoUtils.buffered(f.getInputStream())) {
			byte[] buf = new byte[64 * 1024];
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
			}
		}
	}

	/**
	 * Write the headers, the blank line and the first {@code lines} lines of the
	 * body (for TOP, RFC 1939 section 7). For a surrogate, the lines are those of
	 * the surrogate (RFC 6858 section 4). Only the part needed is read.
	 */
	public void writeTop(Entry e, int lines, OutputStream out) throws IOException {
		TopOutputStream top = new TopOutputStream(out, lines);
		try {
			writeMessage(e, top);
		} catch (TopOutputStream.Done done) {
			// enough lines written
		}
	}

	/** The surrogate (RFC 6858) of a stored message: the one the store keeps for IMAP too. */
	public Message surrogate(Entry e) throws IOException {
		return Message.parse(inbox.surrogate(e.message));
	}

	/** Passes on the headers and the first n body lines, then stops the writer. */
	static final class TopOutputStream extends java.io.FilterOutputStream {
		static final class Done extends IOException {
			private static final long serialVersionUID = 1L;

			Done() {
				super("TOP complete", null);
			}
		}

		private final int lines;
		private boolean inHeader = true;
		private boolean lineHasContent;
		private int bodyLines;
		private int prev = '\n';

		TopOutputStream(OutputStream out, int lines) {
			super(out);
			this.lines = lines;
		}

		@Override
		public void write(int b) throws IOException {
			b &= 0xff;
			if (!inHeader && prev == '\n' && bodyLines >= lines) {
				throw new Done();
			}
			out.write(b);
			if (b == '\n') {
				if (inHeader) {
					if (!lineHasContent) {
						inHeader = false; // the blank line between headers and body
					}
				} else {
					bodyLines++;
				}
				lineHasContent = false;
			} else if (b != '\r') {
				lineHasContent = true;
			}
			prev = b;
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			for (int i = off; i < off + len; i++) {
				write(b[i]);
			}
		}

		/** Doesn't close the stream it writes to. */
		@Override
		public void close() throws IOException {
			flush();
		}
	}

	/** Count a stored message as RETR sends it. */
	/**
	 * The size RETR sends for a stored message, without reading all of it: the store keeps files
	 * with CRLF line ends, so it is the stored size, plus the CRLF that RETR adds after a last line
	 * without one. Only the last byte is read; a file that isn't the size the store says is
	 * measured in full.
	 */
	static long sentSize(MessageInfo m) throws IOException {
		long size = m.getSize();
		FileSource f = m.getFile();
		if (size <= 0) {
			return count(f).getCount();
		}
		int last;
		int after;
		try (InputStream in = f.getInputStream(size - 1)) {
			last = in.read();
			after = last < 0 ? -1 : in.read();
		}
		if (last < 0 || after >= 0) {
			return count(f).getCount();
		}
		return last == '\n' ? size : size + 2;
	}

	static DotStuffingOutputStream.Counter count(FileSource f) throws IOException {
		DotStuffingOutputStream.Counter c = new DotStuffingOutputStream.Counter();
		try (InputStream in = IoUtils.buffered(f.getInputStream())) {
			byte[] buf = new byte[64 * 1024];
			int n;
			while ((n = in.read(buf)) > 0) {
				c.write(buf, 0, n);
			}
		}
		return c;
	}

	/**
	 * A UID from a file name: the name itself if it is 1-70 characters in
	 * 0x21-0x7E (RFC 1939 section 7), otherwise a SHA-256 of it.
	 */
	static String uidFor(String name) {
		boolean ok = !name.isEmpty() && name.length() <= 70;
		for (int i = 0; i < name.length() && ok; i++) {
			char c = name.charAt(i);
			ok = c >= 0x21 && c <= 0x7e;
		}
		if (ok) {
			return name;
		}
		try {
			StringBuilder sb = new StringBuilder();
			for (byte x : MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8))) {
				sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
