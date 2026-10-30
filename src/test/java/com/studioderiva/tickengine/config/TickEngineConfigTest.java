package com.studioderiva.tickengine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
