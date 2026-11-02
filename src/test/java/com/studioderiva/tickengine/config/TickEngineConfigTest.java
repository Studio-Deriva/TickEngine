package com.studioderiva.tickengine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Values typed in '/tickengine config set' arrive as greedy text, spaces included. */
class TickEngineConfigTest {
	@Test
	void onOffValuesIgnoreSurroundingSpacesAndCase() {
		TickEngineConfig.Option<?> option = TickEngineConfig.options().get("blockEntities.stagger");
		String previous = option.format();
		try {
			assertNull(option.set(" TRUE "));
			assertEquals("true", option.format());
			assertNull(option.set("off "));
			assertEquals("false", option.format());
			assertNotNull(option.set("maybe"));
			assertEquals("false", option.format());
		} finally {
			option.set(previous);
		}
	}

	@Test
	void stackingModeAcceptsOnlyItsChoicesAndNeedsAConfirmation() {
		TickEngineConfig.Option<?> option = TickEngineConfig.options().get("stacking.mode");
		String previous = option.format();
		try {
			assertNull(option.set(" AUTOTUNING "));
			assertEquals("autoTuning", option.format(), "stored as written in the choices");
			assertNotNull(option.set("sometimes"));
			assertEquals("autoTuning", option.format());
			assertEquals("off", option.safeValue(), "config set may only turn it off");
			assertNotNull(option.confirmCommand());
		} finally {
			option.set(previous);
		}
	}

	@Test
	void emergencyThresholdsMustBeConsistent() {
		TickEngineConfig.Option<?> exit = TickEngineConfig.options().get("emergency.exitMspt");
		String previous = exit.format();
		try {
			assertNull(exit.set("150"));
			assertNotNull(TickEngineConfig.checkConsistency(), "leaving at the entry threshold would flip-flop");
			assertNull(exit.set("60"));
			assertNull(TickEngineConfig.checkConsistency());
		} finally {
			exit.set(previous);
		}
	}

	@Test
	void everyInconsistencyIsListedApart() {
		TickEngineConfig.Option<?> memory = TickEngineConfig.options().get("emergency.memoryPercent");
		TickEngineConfig.Option<?> lower = TickEngineConfig.options().get("autoTuning.lowerBelowMspt");
		String previousMemory = memory.format();
		String previousLower = lower.format();
		try {
			assertNull(memory.set("30"));
			assertNull(lower.set("199"));
			assertEquals(2, TickEngineConfig.inconsistencies().size(), "two problems, so fixing one leaves the other");
			assertNull(memory.set("60"));
			assertEquals(1, TickEngineConfig.inconsistencies().size());
			assertTrue(TickEngineConfig.inconsistencies().get(0).startsWith("autoTuning.lowerBelowMspt"));
		} finally {
			memory.set(previousMemory);
			lower.set(previousLower);
		}
	}

	@Test
	void theReportWebServerIsOffByDefault() {
		assertEquals("false", TickEngineConfig.options().get("output.webServer").defaultText());
	}
}
