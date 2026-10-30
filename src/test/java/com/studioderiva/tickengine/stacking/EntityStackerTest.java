package com.studioderiva.tickengine.stacking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

class EntityStackerTest {
	@Test
	void readsTheCountFromTheTag() {
		assertEquals(1, EntityStacker.readCount(List.of()));
		assertEquals(1, EntityStacker.readCount(List.of("someone.else")));
		assertEquals(7, EntityStacker.readCount(List.of("someone.else", EntityStacker.TAG_PREFIX + "7")));
		assertEquals(1, EntityStacker.readCount(List.of(EntityStacker.TAG_PREFIX + "lots")), "a broken tag is a single mob");
		assertEquals(1, EntityStacker.readCount(List.of(EntityStacker.TAG_PREFIX + "-3")));
	}

	/** Saved data shaped like a sheep's, with a few of the keys that differ between any two mobs. */
	private static CompoundTag sheep(byte color, boolean sheared, float health, int hurtTime) {
		CompoundTag tag = new CompoundTag();
		tag.putString("id", "minecraft:sheep");
		tag.putByte("Color", color);
		tag.putBoolean("Sheared", sheared);
		tag.putFloat("Health", health);
		tag.put("Pos", new ListTag());
		tag.putInt("HurtTime", hurtTime);
		return tag;
	}

	@Test
	void mobsThatDifferOnlyInPositionHealthAndTimersMatch() {
		assertEquals(EntityStacker.signature(sheep((byte) 0, false, 8, 1)), EntityStacker.signature(sheep((byte) 0, false, 3, 5)));
	}

	@Test
	void colourAndShearingKeepSheepApart() {
		CompoundTag white = EntityStacker.signature(sheep((byte) 0, false, 8, 0));
		assertNotEquals(white, EntityStacker.signature(sheep((byte) 14, false, 8, 0)));
		assertNotEquals(white, EntityStacker.signature(sheep((byte) 0, true, 8, 0)));
	}

	@Test
	void ownedOrLeashedMobsNeverMatch() {
		CompoundTag owned = sheep((byte) 0, false, 8, 0);
		owned.putString("Owner", "someone");
		assertTrue(EntityStacker.signature(owned).isEmpty());
		CompoundTag leashed = sheep((byte) 0, false, 8, 0);
		leashed.put("leash", new CompoundTag());
		assertTrue(EntityStacker.signature(leashed).isEmpty());
		assertFalse(EntityStacker.signature(sheep((byte) 0, false, 8, 0)).isEmpty());
	}
}
