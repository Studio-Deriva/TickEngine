package com.studioderiva.tickengine.report;

import com.studioderiva.tickengine.TickEngine;
import com.studioderiva.tickengine.config.TickEngineConfig;
import com.studioderiva.tickengine.mixin.ServerGamePacketListenerAccessor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Small web server that shows HTML reports in the browser, so the path in chat can be a link.
 *
 * <p>It serves only {@code .html} files of the report folder, each at {@code /r/<token>/<file>}.
 * The token is an HMAC of the file name with a secret kept in the config folder, so a link opens
 * one report, nobody can guess the others, and links keep working after a restart. Requests run
 * on the server's own two threads, never on the server thread.
 */
public final class ReportWebServer {
	private static final Path SECRET_FILE = FMLPaths.CONFIGDIR.get().resolve("tickengine-web.secret");
	private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9._-]+\\.html");
	private static final int TOKEN_CHARS = 32;
	/** Host name each client typed to join, from its handshake; weak so closed connections drop out. */
	private static final Map<Connection, String> JOIN_HOSTS = Collections.synchronizedMap(new WeakHashMap<>());

	private static HttpServer http;
	private static ExecutorService executor;
	private static String runningBind;
	private static int runningPort;
	private static Path serverDirectory;
	private static byte[] secret;

	private ReportWebServer() {
	}

	/** Starts, stops or restarts the server so it matches the config. Safe to call at any time. */
	public static synchronized void refresh(MinecraftServer server) {
		serverDirectory = server.getServerDirectory().toPath().toAbsolutePath().normalize();
		boolean matches = http != null && TickEngineConfig.webBindAddress.equals(runningBind)
			&& TickEngineConfig.webPort == runningPort;
		if (TickEngineConfig.webServer && matches) {
			return;
		}
		stop();
		if (TickEngineConfig.webServer) {
			start();
		}
	}

	private static void start() {
		try {
			if (secret == null) {
				secret = loadSecret();
			}
			HttpServer created = HttpServer.create(
				new InetSocketAddress(TickEngineConfig.webBindAddress, TickEngineConfig.webPort), 0);
			created.createContext("/", ReportWebServer::handle);
			executor = Executors.newFixedThreadPool(2, runnable -> {
				Thread thread = new Thread(runnable, "TickEngine report web server");
				thread.setDaemon(true);
				return thread;
			});
			created.setExecutor(executor);
			created.start();
			http = created;
			runningBind = TickEngineConfig.webBindAddress;
			runningPort = TickEngineConfig.webPort;
			TickEngine.LOGGER.info("Report web server listening on {}:{}", runningBind, runningPort);
		} catch (IOException | RuntimeException | LinkageError e) {
			// LinkageError: a Java runtime without the jdk.httpserver module.
			TickEngine.LOGGER.warn("Could not start the report web server on {}:{} ({}); report links stay off. "
				+ "Change output.webPort or set output.webServer to false.", TickEngineConfig.webBindAddress,
				TickEngineConfig.webPort, e.toString());
			stop();
		}
	}

	public static synchronized void stop() {
		if (http != null) {
			http.stop(0);
			http = null;
		}
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
		runningBind = null;
		runningPort = 0;
	}

	/** Called from the handshake, on a network thread. */
	public static void rememberJoinHost(Connection connection, String hostName) {
		// Forge and proxies append data after a NUL; a trailing dot is a fully qualified name.
		int nul = hostName.indexOf('\0');
		String host = (nul >= 0 ? hostName.substring(0, nul) : hostName).trim();
		if (host.endsWith(".")) {
			host = host.substring(0, host.length() - 1);
		}
		if (!host.isEmpty()) {
			JOIN_HOSTS.put(connection, host);
		}
	}

	/** The browser link for a report file, or null when the server is off or the file is not a served report. */
	public static synchronized String link(CommandSourceStack source, Path file) {
		if (http == null || secret == null) {
			return null;
		}
		String fileName = file.getFileName().toString();
		if (!FILE_NAME.matcher(fileName).matches() || !file.toAbsolutePath().normalize().getParent().equals(reportDirectory())) {
			return null;
		}
		String host = host(source);
		if (host.contains(":") && !host.startsWith("[")) {
			host = "[" + host + "]";
		}
		return "http://" + host + ":" + runningPort + "/r/" + token(fileName) + "/" + fileName;
	}

	private static String host(CommandSourceStack source) {
		if (!"auto".equalsIgnoreCase(TickEngineConfig.webPublicAddress)) {
			return TickEngineConfig.webPublicAddress;
		}
		MinecraftServer server = source.getServer();
		String serverIp = server.getLocalIp();
		if (serverIp != null && !serverIp.isBlank() && !serverIp.equals("0.0.0.0") && !serverIp.equals("::")) {
			return serverIp;
		}
		if (source.getEntity() instanceof ServerPlayer player) {
			String joinHost = JOIN_HOSTS.get(((ServerGamePacketListenerAccessor) player.connection).tickengine$getConnection());
			if (joinHost != null) {
				return joinHost;
			}
		}
		return "localhost";
	}

	private static Path reportDirectory() {
		return serverDirectory.resolve(TickEngineConfig.reportDirectory).normalize();
	}

	private static String token(String fileName) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(fileName.getBytes(StandardCharsets.UTF_8))).substring(0, TOKEN_CHARS);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 is missing from this Java runtime", e);
		}
	}

	private static byte[] loadSecret() throws IOException {
		if (Files.exists(SECRET_FILE)) {
			String text = Files.readString(SECRET_FILE, StandardCharsets.UTF_8).trim();
			if (text.matches("[0-9a-f]{64}")) {
				return HexFormat.of().parseHex(text);
			}
			TickEngine.LOGGER.warn("{} is not valid, making a new one; old report links stop working.", SECRET_FILE);
		}
		byte[] created = new byte[32];
		new SecureRandom().nextBytes(created);
		Files.createDirectories(SECRET_FILE.getParent());
		Files.writeString(SECRET_FILE, HexFormat.of().formatHex(created) + "\n", StandardCharsets.UTF_8);
		return created;
	}

	private static void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			String method = exchange.getRequestMethod();
			if (!method.equals("GET") && !method.equals("HEAD")) {
				exchange.getResponseHeaders().set("Allow", "GET, HEAD");
				send(exchange, 405, "Method not allowed");
				return;
			}
			Path file = resolve(exchange.getRequestURI().getRawPath());
			if (file == null) {
				send(exchange, 404, "Not found");
				return;
			}
			byte[] body = Files.readAllBytes(file);
			headers(exchange, "text/html; charset=utf-8");
			// Reports are self-contained: inline styles and data images only, no scripts.
			exchange.getResponseHeaders().set("Content-Security-Policy",
				"default-src 'none'; style-src 'unsafe-inline'; img-src data:");
			if (method.equals("HEAD")) {
				exchange.sendResponseHeaders(200, -1);
			} else {
				exchange.sendResponseHeaders(200, body.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(body);
				}
			}
		} catch (IOException e) {
			// A browser that closed the tab mid-transfer; nothing to do.
		}
	}

	/** The report file for {@code /r/<token>/<file>}, or null when the path, token or file is wrong. */
	private static Path resolve(String path) {
		String[] parts = path.split("/", -1);
		if (parts.length != 4 || !parts[0].isEmpty() || !parts[1].equals("r") || !FILE_NAME.matcher(parts[3]).matches()) {
			return null;
		}
		byte[] expected;
		Path directory;
		synchronized (ReportWebServer.class) {
			if (secret == null) {
				return null;
			}
			expected = token(parts[3]).getBytes(StandardCharsets.US_ASCII);
			directory = reportDirectory();
		}
		if (!MessageDigest.isEqual(expected, parts[2].getBytes(StandardCharsets.US_ASCII))) {
			return null;
		}
		Path file = directory.resolve(parts[3]).normalize();
		return file.getParent().equals(directory) && Files.isRegularFile(file) ? file : null;
	}

	private static void send(HttpExchange exchange, int status, String text) throws IOException {
		byte[] body = text.getBytes(StandardCharsets.UTF_8);
		headers(exchange, "text/plain; charset=utf-8");
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	private static void headers(HttpExchange exchange, String contentType) {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
		exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
	}
}
