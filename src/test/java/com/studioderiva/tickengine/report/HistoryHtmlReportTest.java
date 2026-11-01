package com.studioderiva.tickengine.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.studioderiva.tickengine.history.HistoryEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

class HistoryHtmlReportTest {
	private static final long NOW = 1_793_500_000_000L;

	@Test
	void peaksAreChartedAndListedWithWhatTookTheTime() {
		HistoryEntry.Detail detail = new HistoryEntry.Detail("light profiler", List.of(new HistoryEntry.Mod("Create <&>", 31)),
			List.of(), List.of(new HistoryEntry.Chunk("minecraft:overworld", 6, -2, 100, 64, -20, true, 18, "create:drill")));
		HistoryEntry calm = new HistoryEntry(HistoryEntry.PERIOD, NOW - 120_000, 60, 1200, 8, 30, 20, 1, 500, 400, 0, false,
			List.of(), null);
		HistoryEntry peak = new HistoryEntry(HistoryEntry.PERIOD, NOW - 60_000, 60, 900, 85, 640, 15, 1, 500, 400, 7, true,
			List.of(HistoryEntry.AVERAGE, HistoryEntry.TUNING), detail);
		String html = HistoryHtmlReport.render(List.of(calm, peak), 24, NOW);

		assertTrue(html.contains("<path d=\"M"), "a line for the tick time");
		assertTrue(html.contains("<circle"), "a dot for the peak");
		assertTrue(html.contains("Create &lt;&amp;&gt;"), "names are escaped");
		assertTrue(html.contains("minecraft:overworld 100 64 -20"), "where the heavy chunk is");
		assertTrue(html.contains("average, auto-tuning, emergency"));
		assertFalse(html.contains("Detailed sessions during peaks"), "no session, no section");
	}

	@Test
	void anEmptyHistorySaysSo() {
		assertTrue(HistoryHtmlReport.render(List.of(), 24, NOW).contains("No data yet."));
	}
}
