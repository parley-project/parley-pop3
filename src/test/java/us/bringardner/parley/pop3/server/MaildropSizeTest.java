package us.bringardner.parley.pop3.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;

/** Sizes from the store (and the last byte) match what RETR sends, however the file ends. */
public class MaildropSizeTest {

	@TempDir
	File tmp;

	private static int seq;

	private static void drop(FileSource dir, String raw) throws IOException {
		String name = String.format("%013d.%08d.%08x.eml", 1_000_000_000_000L + seq, ++seq, raw.hashCode());
		try (OutputStream out = dir.getChild(name).getOutputStream()) {
			out.write(raw.getBytes(StandardCharsets.UTF_8));
		}
	}

	/** What RETR sends before byte-stuffing: CRLF line ends, and a CRLF after a last line without one. */
	private static long sent(String raw) {
		DotStuffingOutputStream.Counter c = new DotStuffingOutputStream.Counter();
		byte[] b = raw.replace("\r\n", "\n").replace("\r", "\n").getBytes(StandardCharsets.UTF_8);
		c.write(b, 0, b.length);
		return c.getCount();
	}

	@Test
	public void sizesMatchWhatRetrSends() throws IOException {
		FileSource dir = FileSourceFactory.getDefaultFactory().createFileSource(tmp.getAbsolutePath());
		List<String> raws = new ArrayList<>();
		raws.add("Subject: crlf\r\n\r\nbody\r\n");
		raws.add("Subject: lf\n\nbody\n");
		raws.add("Subject: no end\r\n\r\nlast line");
		raws.add("Subject: bare cr\r\rbody\r");
		raws.add("Subject: one\r\n");
		for (String r : raws) {
			drop(dir, r);
		}
		try (Maildrop m = Maildrop.open(dir)) {
			List<Maildrop.Entry> entries = m.getEntries();
			assertEquals(raws.size(), entries.size());
			for (int i = 0; i < raws.size(); i++) {
				Maildrop.Entry e = entries.get(i);
				assertEquals(sent(raws.get(i)), e.getSize(), raws.get(i));
				assertEquals(Maildrop.count(e.getFile()).getCount(), e.getSize(), raws.get(i));
			}
		}
	}
}
