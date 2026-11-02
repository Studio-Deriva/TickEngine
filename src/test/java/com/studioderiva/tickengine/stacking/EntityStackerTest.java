package com.studioderiva.tickengine.stacking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
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

	private static ListTag tags(String... names) {
		ListTag list = new ListTag();
		for (String name : names) {
			list.add(StringTag.valueOf(name));
		}
		return list;
	}

	@Test
	void scoreboardTagsMustMatchButNotTheCount() {
		CompoundTag plain = sheep((byte) 0, false, 8, 0);
		plain.put("Tags", tags(EntityStacker.TAG_PREFIX + "4"));
		CompoundTag single = sheep((byte) 0, false, 8, 0);
		assertEquals(EntityStacker.signature(single), EntityStacker.signature(plain), "the count tag is not compared");

		CompoundTag tagged = sheep((byte) 0, false, 8, 0);
		tagged.put("Tags", tags("arena", EntityStacker.TAG_PREFIX + "2"));
		assertNotEquals(EntityStacker.signature(sheep((byte) 0, false, 8, 0)), EntityStacker.signature(tagged));

		CompoundTag sameTags = sheep((byte) 0, false, 8, 0);
		sameTags.put("Tags", tags("team.red", "arena"));
		CompoundTag reordered = sheep((byte) 0, false, 8, 0);
		reordered.put("Tags", tags("arena", "team.red"));
		assertEquals(EntityStacker.signature(sameTags), EntityStacker.signature(reordered), "order does not matter");
	}

	@Test
	void chickensWithDifferentEggTimersMatch() {
		CompoundTag first = new CompoundTag();
		first.putString("id", "minecraft:chicken");
		first.putInt("EggLayTime", 6100);
		CompoundTag second = first.copy();
		second.putInt("EggLayTime", 11900);
		assertEquals(EntityStacker.signature(first), EntityStacker.signature(second));
	}

	@Test
	void invulnerableMobsStayApart() {
		CompoundTag invulnerable = sheep((byte) 0, false, 8, 0);
		invulnerable.putBoolean("Invulnerable", true);
		assertNotEquals(EntityStacker.signature(sheep((byte) 0, false, 8, 0)), EntityStacker.signature(invulnerable));
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

	@Test
	void endermenCarryingABlockNeverMatch() {
		CompoundTag enderman = new CompoundTag();
		enderman.putString("id", "minecraft:enderman");
		enderman.put("carriedBlockState", new CompoundTag());
		assertTrue(EntityStacker.signature(enderman).isEmpty());
	}

	@Test
	void onlyTheStackNameLooksLikeOne() {
		// Shaped like stackName(type, 7), which needs the registries for the type.
		assertTrue(EntityStacker.isStackName(Component.translatable("entity.minecraft.zombie").append(Component.literal(" x7"))));
		assertFalse(EntityStacker.isStackName(Component.literal("Zombie x7")), "a name tag");
		assertFalse(EntityStacker.isStackName(Component.translatable("entity.minecraft.zombie")));
		assertFalse(EntityStacker.isStackName(Component.translatable("entity.minecraft.zombie").append(Component.literal(" the 2nd"))));
		assertFalse(EntityStacker.isStackName(null));
	}
}
