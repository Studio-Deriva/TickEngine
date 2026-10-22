package com.studioderiva.tickengine.tuning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.studioderiva.tickengine.config.TickEngineConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Auto-tuning decisions without a server (distances are left alone when there is none). Ticks are
 * simulated with onTickStart/onTickEnd; a loaded tick busy-waits to raise the measured MSPT.
 * Config values are set in memory only, never saved.
 */
class AutoTunerTest {
	private static final int TICKS_PER_DECISION = 2 * 20;

	private static void set(String key, String value) {
		assertNull(TickEngineConfig.options().get(key).set(value), key);
		TickEngineConfig.invalidateCaches();
	}

	private static void decision(long tickMillis) {
		for (int i = 0; i < TICKS_PER_DECISION; i++) {
			AutoTuner.onTickStart();
			long until = System.nanoTime() + tickMillis * 1_000_000L;
			while (System.nanoTime() < until) {
				Thread.onSpinWait();
			}
			AutoTuner.onTickEnd(null);
		}
	}

	@AfterEach
	void reset() {
		AutoTuner.shutdown();
	}

	@Test
	void loweringRelaxAfterSecondsAppliesAtTheNextDecision() {
		set("autoTuning.enabled", "true");
		set("autoTuning.decisionSeconds", "2");
		set("autoTuning.raiseAboveMspt", "45");
		set("autoTuning.lowerBelowMspt", "30");
		set("autoTuning.relaxAfterSeconds", "60");

		decision(60);
		assertEquals(1, AutoTuner.level(), "one loaded decision tightens one level");
		decision(0);
		decision(0);
		assertEquals(1, AutoTuner.level(), "4 calm seconds are not the 60 s wait");
		assertEquals(60, AutoTuner.relaxWaitSeconds());

		set("autoTuning.relaxAfterSeconds", "0");
		assertEquals(0, AutoTuner.relaxWaitSeconds(), "status must show the new wait right away");
		decision(0);
		assertEquals(0, AutoTuner.level(), "the lowered wait applies at the next decision");
	}

	@Test
	void bouncingLoadStillDoublesTheWait() {
		set("autoTuning.enabled", "true");
		set("autoTuning.decisionSeconds", "2");
		set("autoTuning.raiseAboveMspt", "45");
		set("autoTuning.lowerBelowMspt", "30");
		set("autoTuning.relaxAfterSeconds", "2");

		decision(60);
		assertEquals(1, AutoTuner.level());
		decision(0);
		assertEquals(0, AutoTuner.level(), "relaxes after 2 calm seconds");
		decision(60);
		assertEquals(1, AutoTuner.level(), "load came straight back");
		assertEquals(4, AutoTuner.relaxWaitSeconds(), "the wait doubles when relaxing brought the load back");
		decision(0);
		assertEquals(1, AutoTuner.level(), "2 calm seconds are no longer enough");
		decision(0);
		assertEquals(0, AutoTuner.level(), "relaxes after the doubled 4 s");
	}
}
