package com.studioderiva.tickengine.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.studioderiva.tickengine.config.TickEngineConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** History files and peaks, without a server. */
class HistoryTest {
	private static final ZoneId UTC = ZoneOffset.UTC;
	/** 2026-11-01 03:12 UTC. */
	private static final long NIGHT = LocalDate.of(2026, 11, 1).atTime(3, 12).toInstant(ZoneOffset.UTC).toEpochMilli();

	private static HistoryEntry period(long end, double avgMs, List<String> peaks, HistoryEntry.Detail detail) {
		return new HistoryEntry(HistoryEntry.PERIOD, end, 60, 1150, avgMs, avgMs * 4, 19.17, 3, 4200, 900, 6, false,
			peaks, detail);
	}

	private static HistoryEntry.Detail detail() {
		return new HistoryEntry.Detail("light profiler",
			List.of(new HistoryEntry.Mod("Create", 31.25)),
			List.of(new HistoryEntry.Type("Block entity", "create:mechanical_drill", 12.5, "minecraft:overworld", 100, 64, -20),
				new HistoryEntry.Type("Chunk work", "Unloading and saving chunks", 2, null, 0, 0, 0)),
			List.of(new HistoryEntry.Chunk("minecraft:overworld", 6, -2, 100, 64, -20, true, 18, "create:mechanical_drill")));
	}

	@Test
	void entriesSurviveTheFile(@TempDir Path folder) throws IOException {
		HistoryEntry peak = period(NIGHT, 78.5, List.of(HistoryEntry.AVERAGE, HistoryEntry.TUNING), detail());
		HistoryEntry calm = period(NIGHT + 60_000, 12, List.of(), null);
		HistoryStore.appendTo(folder, peak, UTC);
		HistoryStore.appendTo(folder, calm, UTC);

		assertTrue(Files.exists(folder.resolve("2026-11-01.jsonl")), "one file per day");
		List<HistoryEntry> read = HistoryStore.readFrom(folder, NIGHT - 1, NIGHT + 120_000, UTC);
		assertEquals(List.of(peak, calm), read);
		assertEquals(List.of(calm), HistoryStore.readFrom(folder, NIGHT + 1, NIGHT + 120_000, UTC), "time range");
	}

	@Test
	void aBrokenLineDoesNotHideTheRest(@TempDir Path folder) throws IOException {
		HistoryStore.appendTo(folder, period(NIGHT, 20, List.of(), null), UTC);
		// A crash in the middle of a write leaves half a line.
		Files.writeString(folder.resolve("2026-11-01.jsonl"), "{\"kind\":\"period\",\"end\":17\n",
			StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
		HistoryStore.appendTo(folder, period(NIGHT + 60_000, 21, List.of(), null), UTC);
		assertEquals(2, HistoryStore.readFrom(folder, NIGHT - 1, NIGHT + 60_000, UTC).size());
	}

	@Test
	void readsAcrossMidnight(@TempDir Path folder) throws IOException {
		long beforeMidnight = NIGHT - 3 * 3_600_000L - 13 * 60_000L;
		HistoryStore.appendTo(folder, period(beforeMidnight, 20, List.of(), null), UTC);
		HistoryStore.appendTo(folder, period(NIGHT, 20, List.of(), null), UTC);
		assertTrue(Files.exists(folder.resolve("2026-10-31.jsonl")));
		assertEquals(2, HistoryStore.readFrom(folder, beforeMidnight - 1, NIGHT, UTC).size());
	}

	@Test
	void oldDaysAreDeleted(@TempDir Path folder) throws IOException {
		LocalDate today = LocalDate.of(2026, 11, 8);
		for (int day = 0; day < 10; day++) {
			Files.writeString(folder.resolve(today.minusDays(day) + ".jsonl"), "");
		}
		Files.writeString(folder.resolve("notes.jsonl"), "");
		HistoryStore.pruneIn(folder, 7, today);
		assertTrue(Files.exists(folder.resolve("2026-11-02.jsonl")), "seven days kept, today included");
		assertFalse(Files.exists(folder.resolve("2026-11-01.jsonl")));
		assertFalse(Files.exists(folder.resolve("2026-10-30.jsonl")));
		assertTrue(Files.exists(folder.resolve("notes.jsonl")), "files that are not days stay");
	}

	@Test
	void peaksFollowTheThresholds() {
		int average = TickEngineConfig.historyPeakAverageMspt;
		int tick = TickEngineConfig.historyPeakTickMs;
		int level = TickEngineConfig.historyPeakTuningLevel;
		try {
			TickEngineConfig.historyPeakAverageMspt = 50;
			TickEngineConfig.historyPeakTickMs = 500;
			TickEngineConfig.historyPeakTuningLevel = 5;
			assertEquals(List.of(), HistoryRecorder.peakReasons(49.9, 499, 5));
			assertEquals(List.of(HistoryEntry.AVERAGE), HistoryRecorder.peakReasons(50, 120, 0));
			assertEquals(List.of(HistoryEntry.SINGLE_TICK), HistoryRecorder.peakReasons(10, 800, 0));
			assertEquals(List.of(HistoryEntry.TUNING), HistoryRecorder.peakReasons(10, 20, 6), "above level 5, not at it");
		} finally {
			TickEngineConfig.historyPeakAverageMspt = average;
			TickEngineConfig.historyPeakTickMs = tick;
			TickEngineConfig.historyPeakTuningLevel = level;
		}
	}
}
