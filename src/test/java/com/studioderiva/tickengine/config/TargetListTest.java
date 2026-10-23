package com.studioderiva.tickengine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** entities.spreadExclude: which targets are accepted, what they match, and how they are read and written. */
class TargetListTest {
	private static TargetList list() {
		return new TargetList("spreadExclude", new String[] {"comment"});
	}

	private static JsonObject section(String json) {
		return JsonParser.parseString("{ \"spreadExclude\": " + json + " }").getAsJsonObject();
	}

	@Test
	void matchesTypesAndWholeMods() {
		TargetList list = list();
		assertNull(list.add("minecraft:villager"));
		assertNull(list.add("somemod:*"));
		assertTrue(list.matches(Identifier.parse("minecraft:villager")));
		assertFalse(list.matches(Identifier.parse("minecraft:cow")));
		assertTrue(list.matches(Identifier.parse("somemod:dragon")));
		assertTrue(list.matches(Identifier.parse("somemod:golem")));
		assertFalse(list.matches(Identifier.parse("othermod:dragon")));
	}

	@Test
	void rejectsWhatIsNotATypeOrAMod() {
		TargetList list = list();
		assertNotNull(list.add("villager"), "an id without a namespace");
		assertNotNull(list.add("*"), "every type, which only exists inside a dimension rule");
		assertNotNull(list.add("Bad Mod:*"), "an invalid mod id");
		assertNotNull(list.add("minecraft:Villager"), "uppercase is not a valid id");
		assertTrue(list.targets().isEmpty());
	}

	@Test
	void addingTwiceKeepsOneEntryAndRemoveReportsWhatItDid() {
		TargetList list = list();
		assertNull(list.add("minecraft:villager"));
		assertNull(list.add("minecraft:villager"));
		assertEquals(1, list.targets().size());
		assertTrue(list.remove("minecraft:villager"));
		assertFalse(list.remove("minecraft:villager"));
		assertFalse(list.matches(Identifier.parse("minecraft:villager")));
	}

	@Test
	void readKeepsValidEntriesAndSkipsTheRest() {
		TargetList list = list();
		list.read(section("[\"minecraft:villager\", \"nonsense\", 3, {\"a\": 1}, \"somemod:*\"]"));
		assertEquals(List.of("minecraft:villager", "somemod:*"), List.copyOf(list.targets()));
	}

	@Test
	void readReplacesTheListButLeavesItAloneWhenMissingOrMalformed() {
		TargetList list = list();
		list.read(section("[\"minecraft:villager\"]"));
		list.read(new JsonObject());
		assertEquals(List.of("minecraft:villager"), List.copyOf(list.targets()), "missing key");
		list.read(section("\"minecraft:cow\""));
		assertEquals(List.of("minecraft:villager"), List.copyOf(list.targets()), "not a list");
		list.read(section("[\"minecraft:cow\"]"));
		assertEquals(List.of("minecraft:cow"), List.copyOf(list.targets()), "a new list replaces the old one");
		list.read(section("[]"));
		assertTrue(list.targets().isEmpty(), "an empty list clears it");
	}

	@Test
	void writtenListReadsBackTheSame() {
		TargetList list = list();
		assertNull(list.add("minecraft:villager"));
		assertNull(list.add("somemod:*"));
		StringBuilder out = new StringBuilder();
		list.write(out);
		String text = out.toString();
		assertTrue(text.contains("// comment"), text);
		String json = "{" + text.substring(text.indexOf("\"spreadExclude\"")) + "}";
		TargetList back = list();
		back.read(JsonParser.parseString(json).getAsJsonObject());
		assertEquals(List.copyOf(list.targets()), List.copyOf(back.targets()));

		StringBuilder empty = new StringBuilder();
		list().write(empty);
		assertTrue(empty.toString().endsWith("\"spreadExclude\": []"), empty.toString());
	}
}
