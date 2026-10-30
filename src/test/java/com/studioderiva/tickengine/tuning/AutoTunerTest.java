package com.studioderiva.tickengine.tuning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

	private static final String[] FORCED_KEYS = {"autoTuning.tuneIntervals", "autoTuning.tuneForcedChunks",
		"autoTuning.maxForcedChunkInterval", "autoTuning.tuneRadii", "blockEntities.forcedChunkMaxInterval",
		"entities.forcedChunkMaxInterval", "blockEntities.defaultInterval", "entities.defaultAiInterval",
		"entities.defaultTickInterval", "blockEntities.activeRadiusChunks"};

	/** Puts the options these tests change back to their defaults. */
	private static void forcedDefaults() {
		set("autoTuning.tuneIntervals", "true");
		set("autoTuning.tuneForcedChunks", "true");
		set("autoTuning.maxForcedChunkInterval", "4");
		set("autoTuning.tuneRadii", "true");
		set("blockEntities.forcedChunkMaxInterval", "2");
		set("entities.forcedChunkMaxInterval", "2");
		set("blockEntities.defaultInterval", "4");
		set("entities.defaultAiInterval", "4");
		set("entities.defaultTickInterval", "4");
		set("blockEntities.activeRadiusChunks", "4");
	}

	private static AutoTuner.Values at(int level) {
		AutoTuner.forceLevel(null, level);
		return AutoTuner.values();
	}

	@Test
	void forcedChunkLimitsRiseRightAfterTheIntervals() {
		forcedDefaults();
		AutoTuner.Values three = at(3);
		assertEquals(20, three.blockEntityInterval(), "levels 1-3: intervals 4 -> 5 -> 10 -> 20");
		assertEquals(2, three.blockEntityForcedMax());
		assertEquals(2, three.entityForcedMax());
		AutoTuner.Values four = at(4);
		assertEquals(4, four.blockEntityForcedMax(), "level 4 raises the force-loaded limits");
		assertEquals(4, four.entityForcedMax());
		assertEquals(4, four.blockEntityRadius(), "radii are not touched yet");
		assertEquals(3, at(5).blockEntityRadius(), "level 5 starts on the radii");
		assertEquals(2, at(0).blockEntityForcedMax(), "level 0 puts the limits back");
		forcedDefaults();
	}

	@Test
	void forcedChunkStepCanBeTurnedOff() {
		forcedDefaults();
		set("autoTuning.tuneForcedChunks", "false");
		AutoTuner.Values four = at(4);
		assertEquals(2, four.blockEntityForcedMax());
		assertEquals(2, four.entityForcedMax());
		assertEquals(3, four.blockEntityRadius(), "without the step, level 4 goes to the radii");
		forcedDefaults();
	}

	@Test
	void aLimitOfOneIsNeverRaised() {
		forcedDefaults();
		set("entities.forcedChunkMaxInterval", "1");
		AutoTuner.Values four = at(4);
		assertEquals(4, four.blockEntityForcedMax());
		assertEquals(1, four.entityForcedMax(), "1 means never throttle force-loaded entities");
		set("blockEntities.forcedChunkMaxInterval", "1");
		assertEquals(3, at(4).blockEntityRadius(), "with both limits at 1 there is no forced-chunk step");
		forcedDefaults();
	}

	@Test
	void forcedChunkLimitsStepThroughSafeValuesUpToTheMaximum() {
		forcedDefaults();
		set("autoTuning.maxForcedChunkInterval", "10");
		assertEquals(4, at(4).entityForcedMax());
		assertEquals(5, at(5).entityForcedMax());
		assertEquals(10, at(6).entityForcedMax());
		assertEquals(4, at(6).blockEntityRadius(), "radii untouched while the limits still rise");
		assertEquals(3, at(7).blockEntityRadius(), "radii only after the limits reach the maximum");
		set("autoTuning.maxForcedChunkInterval", "3");
		assertEquals(2, at(4).entityForcedMax(), "no safe value between 2 and a maximum of 3");
		forcedDefaults();
	}

	@Test
	void stackingIsTheLastLevelOnlyInAutoTuningMode() {
		forcedDefaults();
		int withoutStacking = AutoTuner.maxLevel(null);
		assertFalse(at(withoutStacking).stackEntities());
		try {
			TickEngineConfig.stackingMode = TickEngineConfig.STACKING_AUTO_TUNING;
			TickEngineConfig.invalidateCaches();
			assertEquals(withoutStacking + 1, AutoTuner.maxLevel(null), "one more level");
			assertFalse(at(withoutStacking).stackEntities(), "every other step comes first");
			assertTrue(at(withoutStacking + 1).stackEntities());
			TickEngineConfig.stackingMode = TickEngineConfig.STACKING_ALWAYS;
			TickEngineConfig.invalidateCaches();
			assertEquals(withoutStacking, AutoTuner.maxLevel(null), "'always' needs no level");
		} finally {
			TickEngineConfig.stackingMode = TickEngineConfig.STACKING_OFF;
			TickEngineConfig.invalidateCaches();
		}
	}

	@Test
	void emergencyJumpsToTheHighestLevelAndHoldsIt() {
		forcedDefaults();
		set("autoTuning.enabled", "true");
		set("autoTuning.decisionSeconds", "2");
		set("autoTuning.relaxAfterSeconds", "0");
		try {
			EmergencyMode.enter(null, "test", true);
			decision(0);
			assertEquals(AutoTuner.maxLevel(null), AutoTuner.level(), "a calm decision during an emergency still goes to the top");
			decision(0);
			assertEquals(AutoTuner.maxLevel(null), AutoTuner.level(), "and does not relax");
			EmergencyMode.exit(null, "test");
			assertEquals(0, AutoTuner.level(), "after it, the level from before comes back at once");
		} finally {
			EmergencyMode.shutdown();
			set("autoTuning.relaxAfterSeconds", "60");
		}
	}
}
