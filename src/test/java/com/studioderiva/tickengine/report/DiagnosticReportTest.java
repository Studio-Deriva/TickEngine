package com.studioderiva.tickengine.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The issue link: it stays under the length limit by dropping mod lines, and everything else survives. */
class DiagnosticReportTest {
	private static final String TITLE = "[Report] entity minecraft:villager";
	private static final String FOOTER = "---\nfooter\n";

	private static DiagnosticReport.Section mods(int count) {
		List<String> lines = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			lines.add("some-mod-" + i + " 1." + i + ".0+1.21.11");
		}
		return DiagnosticReport.Section.code("Mods (" + count + ")", lines);
	}

	private static String body(String url) {
		String query = url.substring(url.indexOf("&body=") + "&body=".length());
		return URLDecoder.decode(query, StandardCharsets.UTF_8);
	}

	@Test
	void shortReportIsLeftAlone() {
		DiagnosticReport.Fit fit = DiagnosticReport.fit(TITLE, List.of(
			DiagnosticReport.Section.list("Environment", List.of("- TickEngine: 1.0.0")), mods(5)), FOOTER);
		assertFalse(fit.trimmed());
		assertTrue(fit.url().length() <= DiagnosticReport.MAX_URL_LENGTH);
		assertTrue(body(fit.url()).contains("some-mod-4 1.4.0+1.21.11"));
	}

	@Test
	void longModListIsCutToFit() {
		DiagnosticReport.Section environment = DiagnosticReport.Section.list("Environment", List.of("- TickEngine: 1.0.0"));
		DiagnosticReport.Fit fit = DiagnosticReport.fit(TITLE, List.of(environment, mods(400)), FOOTER);
		assertTrue(fit.trimmed());
		assertTrue(fit.url().length() <= DiagnosticReport.MAX_URL_LENGTH);
		String body = body(fit.url());
		assertTrue(body.contains("more in the attached file"));
		assertTrue(body.contains("- TickEngine: 1.0.0"));
		assertTrue(body.endsWith(FOOTER));
		assertFalse(body.contains("some-mod-399 "));
	}

	@Test
	void spacesAreEncodedAsPercent20AndPlusSignsSurvive() {
		DiagnosticReport.Fit fit = DiagnosticReport.fit("a b", List.of(
			DiagnosticReport.Section.list("Version", List.of("- 1.0+2"))), FOOTER);
		assertFalse(fit.url().contains("+2"));
		assertTrue(fit.url().contains("title=a%20b"));
		assertTrue(body(fit.url()).contains("- 1.0+2"));
		assertEquals(1, fit.url().split("&body=").length - 1);
	}

	@Test
	void sectionsThatCannotBeTrimmedFallBackToTheTitleOnly() {
		List<String> lines = new ArrayList<>();
		for (int i = 0; i < 400; i++) {
			lines.add("- Entity rule somemod:mob_" + i + ": AI every 4 ticks, far tick every 8 ticks");
		}
		DiagnosticReport.Fit fit = DiagnosticReport.fit(TITLE, List.of(
			DiagnosticReport.Section.list("About mod somemod", lines), mods(50)), FOOTER);
		assertTrue(fit.trimmed());
		assertTrue(fit.url().length() <= DiagnosticReport.MAX_URL_LENGTH);
		assertFalse(fit.url().contains("&body="));
		assertTrue(fit.url().endsWith("?title=%5BReport%5D%20entity%20minecraft%3Avillager"));
	}
}
