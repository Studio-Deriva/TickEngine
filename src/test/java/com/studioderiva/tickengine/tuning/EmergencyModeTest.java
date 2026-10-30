package com.studioderiva.tickengine.tuning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.studioderiva.tickengine.config.TickEngineConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Emergency decisions on simulated measurements and clock values, without a server. Config
 * values are set in memory only, never saved.
 */
class EmergencyModeTest {
	private static final long SECOND = 1_000_000_000L;

	private static void set(String key, String value) {
		assertNull(TickEngineConfig.options().get(key).set(value), key);
		TickEngineConfig.invalidateCaches();
	}

	@BeforeEach
	void configure() {
		set("emergency.enabled", "true");
		set("emergency.triggerMspt", "150");
		set("emergency.triggerSeconds", "30");
		set("emergency.exitMspt", "60");
		set("emergency.exitSeconds", "60");
		set("emergency.interval", "20");
	}

	@AfterEach
	void reset() {
		EmergencyMode.shutdown();
		set("emergency.enabled", "false");
	}

	@Test
	void entersOnlyAfterTheLoadStaysCriticalForTriggerSeconds() {
		assertEquals(EmergencyMode.Change.NONE, EmergencyMode.update(200, false, 0));
		assertEquals(EmergencyMode.Change.NONE, EmergencyMode.update(200, false, 29 * SECOND));
		assertEquals(EmergencyMode.Change.ENTER, EmergencyMode.update(200, false, 30 * SECOND));
	}

	@Test
	void aCalmMeasurementStartsTheWaitOver() {
		EmergencyMode.update(200, false, 0);
		EmergencyMode.update(100, false, 20 * SECOND);
		assertEquals(EmergencyMode.Change.NONE, EmergencyMode.update(200, false, 31 * SECOND));
		assertEquals(EmergencyMode.Change.ENTER, EmergencyMode.update(200, false, 61 * SECOND));
	}

	@Test
	void memoryAloneIsEnough() {
		EmergencyMode.update(10, true, 0);
		assertEquals(EmergencyMode.Change.ENTER, EmergencyMode.update(10, true, 30 * SECOND));
	}

	@Test
	void neverEntersWhileDisabled() {
		set("emergency.enabled", "false");
		EmergencyMode.update(500, true, 0);
		assertEquals(EmergencyMode.Change.NONE, EmergencyMode.update(500, true, 300 * SECOND));
	}

	@Test
	void leavesOnlyAfterTheLoadStaysLowForExitSeconds() {
		EmergencyMode.enter(null, "test", false);
		assertTrue(EmergencyMode.isActive());
		assertEquals(EmergencyMode.Change.NONE, EmergencyMode.update(100, false, 0), "between the thresholds: stay");
		assertEquals(EmergencyMode.Change.NONE, EmergencyMode.update(30, false, 10 * SECOND));
		assertEquals(EmergencyMode.Change.NONE, EmergencyMode.update(30, false, 69 * SECOND));
		assertEquals(EmergencyMode.Change.EXIT, EmergencyMode.update(30, false, 70 * SECOND));
	}

	@Test
	void turningItOffEndsAnAutomaticEmergencyButNotOneStartedByCommand() {
		EmergencyMode.enter(null, "test", false);
		set("emergency.enabled", "false");
		assertEquals(EmergencyMode.Change.EXIT, EmergencyMode.update(500, false, 0));
		EmergencyMode.exit(null, "test");

		EmergencyMode.enter(null, "test", true);
		assertEquals(EmergencyMode.Change.NONE, EmergencyMode.update(500, false, 0));
	}

	@Test
	void intervalsGrowOnlyWhileActiveAndNeverForUnthrottledTypes() {
		assertEquals(4, EmergencyMode.adjustInterval(4));
		EmergencyMode.enter(null, "test", false);
		assertEquals(20, EmergencyMode.adjustInterval(4));
		assertEquals(40, EmergencyMode.adjustInterval(40), "a slower rule stays slower");
		assertEquals(1, EmergencyMode.adjustInterval(1), "1 means never throttle");
		assertEquals(TickEngineConfig.emergencyPauseSpawning, EmergencyMode.pausesSpawning());
	}
}
