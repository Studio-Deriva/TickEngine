package com.studioderiva.tickengine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ThrottleRulesTest {
	private static final ResourceLocation HOPPER = new ResourceLocation("minecraft", "hopper");
	private static final ResourceLocation FURNACE = new ResourceLocation("minecraft", "furnace");
	private static final ResourceLocation CREATE_BELT = new ResourceLocation("create", "belt");
	private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
	private static final ResourceLocation NETHER = new ResourceLocation("minecraft", "the_nether");

	private static ThrottleRules<Integer> intervals() {
		return new ThrottleRules<>(ThrottleRules.INTERVAL, new String[] {"rules"}, new String[] {"dimension rules"});
	}

	@Test
	void defaultWhenNoRuleMatches() {
		assertEquals(4, intervals().resolve(HOPPER, OVERWORLD, true, 4));
	}

	@Test
	void precedenceFollowsTheDocumentedOrder() {
		ThrottleRules<Integer> rules = intervals();
		assertNull(rules.set("*", 50, NETHER.toString()));
		assertEquals(50, rules.resolve(HOPPER, NETHER, true, 4), "dimension * beats the default");
		assertNull(rules.set("minecraft:*", 40, null));
		assertEquals(40, rules.resolve(HOPPER, NETHER, true, 4), "mod beats dimension *");
		assertNull(rules.set("minecraft:*", 30, NETHER.toString()));
		assertEquals(30, rules.resolve(HOPPER, NETHER, true, 4), "dimension + mod beats mod");
		assertNull(rules.set("minecraft:hopper", 20, null));
		assertEquals(20, rules.resolve(HOPPER, NETHER, true, 4), "type beats dimension + mod");
		assertNull(rules.set("minecraft:hopper", 10, NETHER.toString()));
		assertEquals(10, rules.resolve(HOPPER, NETHER, true, 4), "dimension + type beats type");

		assertEquals(30, rules.resolve(FURNACE, NETHER, true, 4));
		assertEquals(40, rules.resolve(FURNACE, OVERWORLD, true, 4));
		assertEquals(50, rules.resolve(CREATE_BELT, NETHER, true, 4));
		assertEquals(4, rules.resolve(CREATE_BELT, OVERWORLD, true, 4));
	}

	@Test
	void dimensionRulesIgnoredWhenDisabled() {
		ThrottleRules<Integer> rules = intervals();
		rules.set("minecraft:hopper", 10, NETHER.toString());
		rules.set("*", 50, NETHER.toString());
		assertEquals(4, rules.resolve(HOPPER, NETHER, false, 4));
	}

	@Test
	void rejectsInvalidTargetsAndValues() {
		ThrottleRules<Integer> rules = intervals();
		assertNotNull(rules.set("hopper", 4, null), "id without namespace");
		assertNotNull(rules.set("*", 4, null), "* only inside a dimension");
		assertNotNull(rules.set("Bad Mod:*", 4, null), "invalid namespace");
		assertNotNull(rules.set(":*", 4, null), "empty namespace");
		assertNotNull(rules.set("minecraft:hopper", 0, null), "interval below 1");
		assertNotNull(rules.set("minecraft:hopper", 101, null), "interval above 100");
		assertNotNull(rules.set("minecraft:hopper", 4, "Not A Dimension"), "invalid dimension");
		assertTrue(rules.rules().isEmpty());
		assertTrue(rules.dimensionRules().isEmpty());
	}

	@Test
	void removeCleansUpEmptyDimensions() {
		ThrottleRules<Integer> rules = intervals();
		rules.set("minecraft:hopper", 8, NETHER.toString());
		assertFalse(rules.remove("minecraft:hopper", null));
		assertTrue(rules.remove("minecraft:hopper", NETHER.toString()));
		assertTrue(rules.dimensionRules().isEmpty());
		assertFalse(rules.remove("minecraft:hopper", NETHER.toString()));
	}

	@Test
	void entityIntervalsValidation() {
		ThrottleRules<ThrottleRules.EntityIntervals> rules = new ThrottleRules<>(ThrottleRules.ENTITY_INTERVALS,
			new String[] {"rules"}, new String[] {"dimension rules"});
		assertNull(rules.set("minecraft:cow", new ThrottleRules.EntityIntervals(2, 8), null));
		assertNotNull(rules.set("minecraft:pig", new ThrottleRules.EntityIntervals(0, 8), null));
		assertNotNull(rules.set("minecraft:pig", new ThrottleRules.EntityIntervals(2, 200), null));
		assertEquals(new ThrottleRules.EntityIntervals(2, 8), rules.resolve(new ResourceLocation("minecraft", "cow"),
			OVERWORLD, false, new ThrottleRules.EntityIntervals(4, 4)));
	}

	@Test
	void writeThenReadRoundTrip() {
		ThrottleRules<ThrottleRules.EntityIntervals> rules = new ThrottleRules<>(ThrottleRules.ENTITY_INTERVALS,
			new String[] {"rules"}, new String[] {"dimension rules"});
		rules.set("minecraft:cow", new ThrottleRules.EntityIntervals(2, 8), null);
		rules.set("create:*", new ThrottleRules.EntityIntervals(1, 1), NETHER.toString());
		rules.set("*", new ThrottleRules.EntityIntervals(5, 10), NETHER.toString());
		StringBuilder out = new StringBuilder("{\n");
		rules.write(out);
		out.append("\n}");

		ThrottleRules<ThrottleRules.EntityIntervals> copy = new ThrottleRules<>(ThrottleRules.ENTITY_INTERVALS,
			new String[] {"rules"}, new String[] {"dimension rules"});
		com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(new java.io.StringReader(out.toString()));
		reader.setLenient(true);
		copy.read(JsonParser.parseReader(reader).getAsJsonObject());
		assertEquals(rules.rules(), copy.rules());
		assertEquals(rules.dimensionRules(), copy.dimensionRules());
	}

	@Test
	void readSkipsMalformedEntriesAndKeepsTheRest() {
		ThrottleRules<Integer> rules = intervals();
		JsonObject section = JsonParser.parseString(
			"{\"rules\": {\"minecraft:hopper\": 8, \"minecraft:furnace\": \"fast\", \"nonsense\": 4, \"minecraft:chest\": 500},"
				+ " \"dimensionRules\": {\"minecraft:the_nether\": 3}}").getAsJsonObject();
		rules.read(section);
		assertEquals(1, rules.rules().size());
		assertEquals(8, rules.rules().get("minecraft:hopper"));
		assertTrue(rules.dimensionRules().isEmpty());
	}
}
