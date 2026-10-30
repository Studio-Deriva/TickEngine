package com.studioderiva.tickengine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** Which rule wins for a type, and that explain() names the same rule resolve() uses. */
class ThrottleRulesTest {
	private static final Identifier HOPPER = Identifier.parse("minecraft:hopper");
	private static final Identifier NETHER = Identifier.parse("minecraft:the_nether");
	private static final int DEFAULT = 4;

	private static ThrottleRules<Integer> rules() {
		return new ThrottleRules<>(ThrottleRules.INTERVAL, new String[] {"rules"}, new String[] {"dimension rules"});
	}

	@Test
	void defaultAppliesWithoutRules() {
		ThrottleRules<Integer> rules = rules();
		assertNull(rules.explain(HOPPER, NETHER, true));
		assertEquals(DEFAULT, rules.resolve(HOPPER, NETHER, true, DEFAULT));
	}

	@Test
	void typeRuleBeatsModRule() {
		ThrottleRules<Integer> rules = rules();
		rules.set("minecraft:*", 10, null);
		rules.set("minecraft:hopper", 2, null);
		ThrottleRules.Match<Integer> match = rules.explain(HOPPER, NETHER, false);
		assertNotNull(match);
		assertEquals(2, match.value());
		assertEquals("rule for minecraft:hopper", match.source());
	}

	@Test
	void modRuleAppliesToItsTypes() {
		ThrottleRules<Integer> rules = rules();
		rules.set("minecraft:*", 10, null);
		ThrottleRules.Match<Integer> match = rules.explain(HOPPER, NETHER, false);
		assertNotNull(match);
		assertEquals(10, match.value());
		assertEquals("rule for minecraft:*", match.source());
	}

	@Test
	void dimensionRulesOnlyCountWhenEnabled() {
		ThrottleRules<Integer> rules = rules();
		rules.set("minecraft:hopper", 20, "minecraft:the_nether");
		rules.set("minecraft:hopper", 5, null);
		ThrottleRules.Match<Integer> on = rules.explain(HOPPER, NETHER, true);
		assertNotNull(on);
		assertEquals(20, on.value());
		assertEquals("rule for minecraft:hopper in minecraft:the_nether", on.source());
		assertEquals(5, rules.explain(HOPPER, NETHER, false).value());
		assertEquals(5, rules.resolve(HOPPER, NETHER, false, DEFAULT));
	}

	@Test
	void everyTypeRuleInADimensionComesLast() {
		ThrottleRules<Integer> rules = rules();
		rules.set(ThrottleRules.ALL, 40, "minecraft:the_nether");
		assertEquals(40, rules.resolve(HOPPER, NETHER, true, DEFAULT));
		rules.set("minecraft:*", 8, null);
		assertEquals(8, rules.resolve(HOPPER, NETHER, true, DEFAULT));
	}
}
