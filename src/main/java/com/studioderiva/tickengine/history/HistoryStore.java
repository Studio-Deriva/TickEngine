package com.studioderiva.tickengine.history;

import com.google.gson.JsonParser;
import com.studioderiva.tickengine.TickEngine;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.util.Util;

/**
 * History files: {@code tickengine/history/<date>.jsonl} in the server folder, one JSON line per
 * entry, one file per day (server time zone). Files older than {@code history.keepDays} are deleted.
 * Every disk access runs on the IO pool, never on the server thread.
 */
public final class HistoryStore {
	static final String FOLDER = "tickengine/history";
	private static final String SUFFIX = ".jsonl";
	/** Appends from the IO pool may run at the same time; lines must not mix. */
	private static final Object WRITE_LOCK = new Object();

	private static volatile Path directory;

	private HistoryStore() {
	}

	public static void configure(Path serverDirectory) {
		directory = serverDirectory.toAbsolutePath().normalize().resolve(FOLDER);
	}

	public static Path directory() {
		return directory;
	}

	public static CompletableFuture<Void> append(HistoryEntry entry) {
		Path target = directory;
		if (target == null) {
			return CompletableFuture.completedFuture(null);
		}
		return CompletableFuture.runAsync(() -> {
			try {
				appendTo(target, entry, ZoneId.systemDefault());
			} catch (IOException e) {
				TickEngine.LOGGER.warn("Could not write the history file in {}", target, e);
			}
		}, Util.ioPool());
	}

	/** Entries that ended between the two times, oldest first. Completes off the server thread. */
	public static CompletableFuture<List<HistoryEntry>> read(long fromMillis, long toMillis) {
		Path source = directory;
		if (source == null) {
			return CompletableFuture.completedFuture(List.of());
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				return readFrom(source, fromMillis, toMillis, ZoneId.systemDefault());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}, Util.ioPool());
	}

	public static void prune(int keepDays) {
		Path target = directory;
		if (target == null) {
			return;
		}
		CompletableFuture.runAsync(() -> {
			try {
				pruneIn(target, keepDays, LocalDate.now(ZoneId.systemDefault()));
			} catch (IOException e) {
				TickEngine.LOGGER.warn("Could not delete old history files in {}", target, e);
			}
		}, Util.ioPool());
	}

	static void appendTo(Path folder, HistoryEntry entry, ZoneId zone) throws IOException {
		LocalDate day = Instant.ofEpochMilli(entry.endMillis()).atZone(zone).toLocalDate();
		String line = entry.toJson() + "\n";
		synchronized (WRITE_LOCK) {
			Files.createDirectories(folder);
			Files.writeString(folder.resolve(day + SUFFIX), line, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
	}

	static List<HistoryEntry> readFrom(Path folder, long fromMillis, long toMillis, ZoneId zone) throws IOException {
		List<HistoryEntry> entries = new ArrayList<>();
		LocalDate first = Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate();
		LocalDate last = Instant.ofEpochMilli(toMillis).atZone(zone).toLocalDate();
		for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
			Path file = folder.resolve(day + SUFFIX);
			if (!Files.isRegularFile(file)) {
				continue;
			}
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				if (line.isBlank()) {
					continue;
				}
				try {
					HistoryEntry entry = HistoryEntry.fromJson(JsonParser.parseString(line).getAsJsonObject());
					if (entry.endMillis() >= fromMillis && entry.endMillis() <= toMillis) {
						entries.add(entry);
					}
				} catch (RuntimeException e) {
					// A line cut short by a crash, or edited by hand: the rest of the file still counts.
					TickEngine.LOGGER.debug("Skipped a malformed history line in {}", file);
				}
			}
		}
		entries.sort(Comparator.comparingLong(HistoryEntry::endMillis));
		return entries;
	}

	/** Deletes the day files older than {@code keepDays} days before {@code today}. Other files are left alone. */
	static void pruneIn(Path folder, int keepDays, LocalDate today) throws IOException {
		if (!Files.isDirectory(folder)) {
			return;
		}
		LocalDate oldestKept = today.minusDays(keepDays - 1L);
		try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*" + SUFFIX)) {
			for (Path file : files) {
				String name = file.getFileName().toString();
				try {
					if (LocalDate.parse(name.substring(0, name.length() - SUFFIX.length())).isBefore(oldestKept)) {
						Files.deleteIfExists(file);
					}
				} catch (DateTimeParseException e) {
					// Not one of ours.
				}
			}
		}
	}
}
