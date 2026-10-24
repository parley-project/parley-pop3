package us.bringardner.parley.pop3.server;

import us.bringardner.parley.mail.server.MailServerProvider;
import us.bringardner.parley.pop3.POP3;
import us.bringardner.parley.net.server.ServerContext;
import us.bringardner.parley.net.server.ServerMain;

/**
 * The POP3 servers a launcher can run ({@code parley.servers=pop3,pop3s}): settings from
 * {@code parley-mail.properties}, then {@code parley-pop3.properties} (or the file
 * {@code parley.mail.pop3.configFile} names).
 *
 * @since 2.0.0
 */
public final class Pop3Providers {

	private Pop3Providers() {
	}

	/** {@code parley.mail.pop3.secure=true}: the older way to run POP3 with implicit TLS only */
	static boolean legacySecure() {
		return Boolean.parseBoolean(System.getProperty(Pop3Server.POP3_PREFIX + ".secure", "false"));
	}

	/** POP3 (port 110, STLS): {@code pop3}, on unless parley.mail.pop3.port is 0 */
	public static final class Pop3 extends MailServerProvider {
		public Pop3() {
			super("pop3", "POP3", "parley-pop3.properties", Pop3Server.CONFIG_PROP);
		}

		@Override
		public int port() {
			return legacySecure() ? 0 : intProperty(Pop3Server.POP3_PREFIX + ".port", POP3.POP3_PORT);
		}

		@Override
		public ServerMain.Service create(ServerContext context) {
			return ServerMain.service(new Pop3Server(port(), Pop3Server.POP3_NAME, false), "POP3 server");
		}
	}

	/** POP3 with implicit TLS (port 995): {@code pop3s}, on when parley.mail.pop3s.port is set (and not 0) */
	public static final class Pop3s extends MailServerProvider {
		public Pop3s() {
			super("pop3s", "POP3 with implicit TLS", "parley-pop3.properties", Pop3Server.CONFIG_PROP);
		}

		@Override
		public int port() {
			return intProperty(Pop3Server.POP3S_PREFIX + ".port", legacySecure() ? POP3.POP3S_PORT : 0);
		}

		@Override
		public ServerMain.Service create(ServerContext context) {
			return ServerMain.service(new Pop3Server(port(), Pop3Server.POP3_NAME, true), "POP3 server (implicit TLS)");
		}
	}
}
