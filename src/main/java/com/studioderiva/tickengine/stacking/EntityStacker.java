package com.studioderiva.tickengine.stacking;

import com.studioderiva.tickengine.TickEngine;
import com.studioderiva.tickengine.compat.MinecraftCompat;
import com.studioderiva.tickengine.config.TickEngineConfig;
import com.studioderiva.tickengine.mixin.LivingEntityAccessor;
import com.studioderiva.tickengine.throttle.EntityThrottle;
import com.studioderiva.tickengine.tuning.AutoTuner;
import com.studioderiva.tickengine.tuning.EmergencyMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalEntityTypeTags;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.item.ItemStack;

/**
 * Entity stacking: mobs of the same kind standing close together become one mob that counts
 * for several.
 *
 * <p>The count is kept in a scoreboard tag ({@code tickengine.stack.7}, usable in selectors), which Minecraft saves
 * with the entity in every version, and shown as the custom name ("Zombie x7"). Two mobs stack
 * only when their saved data is the same apart from what differs between any two mobs anyway
 * (position, health, timers, see {@link #VOLATILE_KEYS}): a red and a white sheep, a sheared and
 * a woolly one, two horses with different stats or two mobs with different scoreboard tags never
 * stack, and modded data is compared too.
 *
 * <p>A stack behaves like separate mobs where it matters:
 * <ul>
 *   <li>Dying kills one mob: it drops its own loot and XP, and a copy with one less in the count
 *   takes its place ({@link #afterDeath}), with the health the rest of the stack has: damage that
 *   hit all of them (a fall, lava) counts, an attack on the stack hit only the one that died.
 *   {@code /kill} removes the whole stack.</li>
 *   <li>A player interaction that does something (feeding, shearing, a name tag, a lead) applies
 *   to one mob: the rest moves to a copy first ({@link #afterInteract}). So does a dispenser shearing
 *   a stack, and a stack of chickens lays eggs as often as its chickens would.</li>
 *   <li>Natural spawning counts a stack as all its mobs ({@link #expandStacks}), so the mob cap and
 *   mob farms work as without stacking.</li>
 *   <li>What a stack picks up (an item, a block for an enderman) belongs to one mob: the copies made
 *   from a stack never carry it ({@link #dropAcquired}), and mobs already holding such things never
 *   stack, since they would then hold one for all of them.</li>
 * </ul>
 * Server thread only.
 */
public final class EntityStacker {
	public static final String TAG_PREFIX = "tickengine.stack.";
	/** Saved data that differs between any two otherwise identical mobs, ignored when comparing. */
	static final Set<String> VOLATILE_KEYS = Set.of(
		"Pos", "Motion", "Rotation", "UUID", "Health", "AbsorptionAmount", "FallDistance", "fall_distance", "Fire", "Air",
		"OnGround", "PortalCooldown", "HurtTime", "HurtByTimestamp", "DeathTime", "FallFlying",
		"CustomName", "CustomNameVisible", "Brain", "attributes", "Attributes", "LeftHanded", "CanPickUpLoot",
		"Age", "InLove", "LoveCause", "love_cause", "active_effects", "ActiveEffects", "sleeping_pos", "SleepingX",
		"SleepingY", "SleepingZ", "id",
		// Random for every chicken, so no two would ever stack; a stack lays for all its chickens anyway.
		"EggLayTime");
	/**
	 * Data that keeps a mob out of stacking: it belongs to someone, is tied to something, or is a
	 * block an enderman picked up (see {@link #holdsAcquiredItems} for items).
	 */
	private static final Set<String> BLOCKING_KEYS = Set.of("Owner", "leash", "Leash", "Passengers", "carriedBlockState");
	/** The name {@link #stackName} gives, as it reads: " x7" after the translated type name. */
	private static final Pattern STACK_NAME_COUNT = Pattern.compile(" x\\d+");
	/** Time spent merging per tick, so a big entity count never makes a long tick. */
	private static final long BUDGET_NANOS = 500_000;
	private static final long EMERGENCY_BUDGET_NANOS = 3_000_000;

	private static int ticksSinceRound;
	private static long rounds;
	/** Groups of the current round still to work through. */
	private static final ArrayDeque<Group> groups = new ArrayDeque<>();
	private static final Map<EntityType<?>, Boolean> excluded = new HashMap<>();
	private static int excludedVersion = -1;

	/** Stacks about to die or be used, with their data from before (a map in case one dies inside another's death). */
	private static final Map<Mob, Pending> pending = new IdentityHashMap<>();
	/**
	 * Per stack, the damage that hit only its front mob (attacks), which the rest of the stack did not
	 * take. Weak keys: an unloaded stack forgets it, and its copies then start at full health.
	 */
	private static final Map<Mob, Float> frontDamage = new WeakHashMap<>();
	/** Per stack, its health when the hit it is taking now started, and the tick of that hit. */
	private static final Map<Mob, HitStart> hitStart = new WeakHashMap<>();
	private static boolean loadErrorLogged;

	private static long mergesTotal;

	private EntityStacker() {
	}

	/** Stacking runs right now: stacking.mode allows it in the current state. */
	public static boolean isActive() {
		return switch (TickEngineConfig.stackingMode) {
			case TickEngineConfig.STACKING_ALWAYS -> true;
			case TickEngineConfig.STACKING_AUTO_TUNING -> AutoTuner.values().stackEntities() || EmergencyMode.isActive();
			case TickEngineConfig.STACKING_EMERGENCY -> EmergencyMode.isActive();
			default -> false;
		};
	}

	/** How many mobs this entity stands for: 1 for anything that is not a stack. */
	public static int count(Entity entity) {
		Set<String> tags = entity.getTags();
		return tags.isEmpty() ? 1 : readCount(tags);
	}

	static int readCount(Iterable<String> tags) {
		for (String tag : tags) {
			if (tag.startsWith(TAG_PREFIX)) {
				try {
					return Math.max(1, Integer.parseInt(tag.substring(TAG_PREFIX.length())));
				} catch (NumberFormatException e) {
					return 1;
				}
			}
		}
		return 1;
	}

	public static boolean isStack(Entity entity) {
		return count(entity) > 1;
	}

	/** Sets the count, its tag and its name. A count of 1 makes it a normal mob again. */
	public static void setCount(Mob mob, int count) {
		if (count <= 1) {
			frontDamage.remove(mob);
			hitStart.remove(mob);
		}
		for (String tag : new ArrayList<>(mob.getTags())) {
			if (tag.startsWith(TAG_PREFIX)) {
				mob.removeTag(tag);
			}
		}
		if (count > 1) {
			mob.addTag(TAG_PREFIX + count);
			mob.setCustomName(stackName(mob.getType(), count));
			mob.setCustomNameVisible(TickEngineConfig.stackAlwaysShowCount);
		} else if (isStackName(mob.getCustomName())) {
			// Only our own name goes: a name tag used on the mob just now stays. Checked by its shape
			// rather than its type, since a converted mob keeps the name of what it was.
			mob.setCustomName(null);
			mob.setCustomNameVisible(false);
		}
	}

	static Component stackName(EntityType<?> type, int count) {
		return Component.translatable(type.getDescriptionId()).append(Component.literal(" x" + count));
	}

	/** A name made by {@link #stackName}; a name tag or a command gives other names. */
	static boolean isStackName(Component name) {
		return name != null && name.getContents() instanceof TranslatableContents && name.getSiblings().size() == 1
			&& STACK_NAME_COUNT.matcher(name.getSiblings().get(0).getString()).matches();
	}

	// --- merging ---

	/**
	 * Called at the end of every server tick. Every second (or as soon as the previous round is
	 * done, when it took longer) the mobs that may stack are grouped by level, type and cube of
	 * stacking.areaBlocks; the groups are then worked through over the next ticks within a time
	 * budget per tick, so even a crowd of thousands never makes one long tick. Each mob is saved
	 * once per round to compare its data, and mobs alone in their cube are never saved.
	 */
	public static void onTickEnd(MinecraftServer server) {
		ticksSinceRound++;
		if (!isActive()) {
			groups.clear();
			return;
		}
		if (groups.isEmpty()) {
			if (ticksSinceRound < 20) {
				return;
			}
			ticksSinceRound = 0;
			collectGroups(server);
		}
		// Merging is what brings the load down in an emergency: spend more time on it then.
		long deadline = System.nanoTime() + (EmergencyMode.isActive() ? EMERGENCY_BUDGET_NANOS : BUDGET_NANOS);
		while (!groups.isEmpty() && System.nanoTime() < deadline) {
			Group group = groups.peek();
			if (group.next >= group.mobs.size()) {
				groups.poll();
				continue;
			}
			group.mergeNext();
		}
	}

	private record CubeKey(ServerLevel level, EntityType<?> type, int x, int y, int z) {
	}

	private static void collectGroups(MinecraftServer server) {
		int size = TickEngineConfig.stackAreaBlocks;
		// Every other round the grid moves by half a cube, so two mobs on either side of a cube edge
		// share a cube in the next round.
		int offset = rounds++ % 2 == 0 ? 0 : size / 2;
		Map<CubeKey, List<Mob>> cubes = new HashMap<>();
		for (ServerLevel level : server.getAllLevels()) {
			for (Entity entity : level.getAllEntities()) {
				if (!(entity instanceof Mob mob)) {
					continue;
				}
				if (mob.hasCustomName() && !isStack(mob) && isStackName(mob.getCustomName())) {
					// A single mob still named like a stack (released from a bucket, for example): the name goes.
					setCount(mob, 1);
				}
				if (count(mob) < TickEngineConfig.stackMaxSize && canStack(mob)) {
					CubeKey key = new CubeKey(level, mob.getType(), Math.floorDiv(mob.getBlockX() + offset, size),
						Math.floorDiv(mob.getBlockY() + offset, size), Math.floorDiv(mob.getBlockZ() + offset, size));
					cubes.computeIfAbsent(key, k -> new ArrayList<>()).add(mob);
				}
			}
		}
		for (List<Mob> mobs : cubes.values()) {
			if (mobs.size() > 1) {
				groups.add(new Group(mobs));
			}
		}
	}

	/** Mobs of one type in one cube; merged one at a time into the first one with the same data. */
	private static final class Group {
		final List<Mob> mobs;
		int next;
		/** For each kind of data seen so far, the stack that still has room. */
		final Map<CompoundTag, Mob> open = new HashMap<>();

		Group(List<Mob> mobs) {
			this.mobs = mobs;
		}

		void mergeNext() {
			Mob mob = mobs.get(next++);
			// Checked again: ticks went by since the round started.
			if (count(mob) >= TickEngineConfig.stackMaxSize || !canStack(mob) || holdsAcquiredItems(mob)) {
				return;
			}
			CompoundTag signature = signature(mob);
			if (signature.isEmpty()) {
				return;
			}
			Mob target = open.get(signature);
			if (target == null || !target.isAlive() || count(target) >= TickEngineConfig.stackMaxSize || !canStack(target)) {
				open.put(signature, mob);
				return;
			}
			int targetCount = count(target);
			int mobCount = count(mob);
			int moved = Math.min(mobCount, TickEngineConfig.stackMaxSize - targetCount);
			setCount(target, targetCount + moved);
			if (moved == mobCount) {
				mob.discard();
			} else {
				setCount(mob, mobCount - moved);
				// The target is full now: the rest is the next one to fill.
				open.put(signature, mob);
			}
			mergesTotal++;
		}
	}

	/** Mobs that may be stacked at all, before their data is compared. */
	static boolean canStack(Mob mob) {
		if (!mob.isAlive() || mob.isRemoved() || mob.isPassenger() || mob.isVehicle() || mob.isLeashed()) {
			return false;
		}
		// A name nobody but a player gives it.
		if (mob.hasCustomName() && !isStack(mob)) {
			return false;
		}
		if (mob.isBaby() || mob instanceof Animal animal && animal.isInLove()) {
			return false;
		}
		// Tamed horses and other owned mobs have an Owner in their data, see BLOCKING_KEYS.
		if (mob instanceof TamableAnimal tamable && tamable.isTame()) {
			return false;
		}
		if (mob.getType().is(ConventionalEntityTypeTags.BOSSES) || EntityThrottle.inCombat(mob)) {
			return false;
		}
		return !isExcluded(mob.getType());
	}

	/**
	 * Items a mob picked up or was given: equipment that always drops (vanilla marks picked-up and
	 * dispensed items so), or an inventory (piglins, allays, pillagers). One of them belongs to one mob,
	 * so a stack must never start out holding them.
	 */
	private static boolean holdsAcquiredItems(Mob mob) {
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			if (!mob.getItemBySlot(slot).isEmpty() && MinecraftCompat.isGuaranteedDrop(mob, slot)) {
				return true;
			}
		}
		return mob instanceof InventoryCarrier carrier && !carrier.getInventory().isEmpty();
	}

	/**
	 * Takes from a copy made from a stack what the stack picked up: one mob picked it up, and that one
	 * keeps it. Stacks never start out holding such things (see {@link #holdsAcquiredItems}).
	 */
	private static void dropAcquired(Mob copy) {
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			if (!copy.getItemBySlot(slot).isEmpty() && MinecraftCompat.isGuaranteedDrop(copy, slot)) {
				copy.setItemSlot(slot, ItemStack.EMPTY);
			}
		}
		if (copy instanceof InventoryCarrier carrier) {
			carrier.getInventory().clearContent();
		}
	}

	private static boolean isExcluded(EntityType<?> type) {
		if (excludedVersion != TickEngineConfig.version) {
			excluded.clear();
			excludedVersion = TickEngineConfig.version;
		}
		return excluded.computeIfAbsent(type, key -> {
			Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(key);
			return id == null || TickEngineConfig.STACK_EXCLUDE.matches(id);
		});
	}

	/** The saved data that must match for two mobs to stack; empty when the mob must never stack. */
	private static CompoundTag signature(Mob mob) {
		CompoundTag signature = signature(MinecraftCompat.saveEntity(mob));
		if (!signature.isEmpty()) {
			CompoundTag bases = attributeBases(mob);
			if (!bases.isEmpty()) {
				signature.put("tickengine:attribute_bases", bases);
			}
		}
		return signature;
	}

	/**
	 * The base value of every attribute the mob has, read from the mob so that two mobs of a type
	 * always list the same ones (saved data holds only those used so far). Horse speed, jump and
	 * health are base values; the modifiers mobs get at random when they spawn (follow range) are not
	 * compared. Zombies also get a random base for their reinforcement chance, which is left out:
	 * comparing it would keep every two zombies apart.
	 */
	private static CompoundTag attributeBases(Mob mob) {
		CompoundTag bases = new CompoundTag();
		AttributeMap attributes = mob.getAttributes();
		for (Attribute attribute : BuiltInRegistries.ATTRIBUTE) {
			var holder = BuiltInRegistries.ATTRIBUTE.wrapAsHolder(attribute);
			if (attribute == Attributes.SPAWN_REINFORCEMENTS_CHANCE.value() || !attributes.hasAttribute(holder)) {
				continue;
			}
			Identifier id = BuiltInRegistries.ATTRIBUTE.getKey(attribute);
			if (id != null) {
				bases.putDouble(id.toString(), attributes.getBaseValue(holder));
			}
		}
		return bases;
	}

	static CompoundTag signature(CompoundTag data) {
		for (String key : BLOCKING_KEYS) {
			if (data.contains(key)) {
				return new CompoundTag();
			}
		}
		Tag tags = data.get("Tags");
		data.remove("Tags");
		for (String key : VOLATILE_KEYS) {
			data.remove(key);
		}
		// Scoreboard tags from commands and datapacks must match; the stack count is not one of them.
		String otherTags = otherTags(tags);
		if (!otherTags.isEmpty()) {
			data.putString("Tags", otherTags);
		}
		if (data.isEmpty()) {
			// Nothing left to compare would make every mob of the type match: keep something.
			data.putBoolean("tickengine", true);
		}
		return data;
	}

	/** The scoreboard tags in saved data apart from the stack count, sorted, as one text; empty when none. */
	static String otherTags(Tag tags) {
		if (!(tags instanceof ListTag list)) {
			return "";
		}
		List<String> names = new ArrayList<>();
		for (Tag tag : list) {
			String name = tag.toString();
			if (!name.contains(TAG_PREFIX)) {
				names.add(name);
			}
		}
		names.sort(null);
		return String.join(",", names);
	}

	// --- damage ---

	private record HitStart(float health, int tick) {
	}

	/** Before {@code LivingEntity.actuallyHurt}: the health a stack has before this hit. */
	public static void beforeHurt(LivingEntity entity) {
		if (entity instanceof Mob mob && mob.level() instanceof ServerLevel && isStack(mob)) {
			hitStart.put(mob, new HitStart(mob.getHealth(), mob.tickCount));
		}
	}

	/**
	 * After {@code LivingEntity.actuallyHurt}: an attack that the stack survived hit only its front mob,
	 * so the rest keeps that health. Other damage (a fall, lava, cramming, an explosion) hit all of them.
	 */
	public static void afterHurt(LivingEntity entity, DamageSource source) {
		if (!(entity instanceof Mob mob)) {
			return;
		}
		HitStart start = hitStart.get(mob);
		if (start == null || start.tick() != mob.tickCount) {
			return;
		}
		float taken = start.health() - mob.getHealth();
		if (taken > 0 && mob.getHealth() > 0 && hitsOneMob(source)) {
			frontDamage.merge(mob, taken, Float::sum);
		}
	}

	/** Damage from an entity (a player, a mob, an arrow) hits one mob of a stack; explosions hit them all. */
	static boolean hitsOneMob(DamageSource source) {
		return (source.getEntity() != null || source.getDirectEntity() != null) && !source.is(DamageTypeTags.IS_EXPLOSION);
	}

	/**
	 * Health of the mobs still in the stack, when its front mob has {@code health}: the front mob's
	 * health plus what only it took. Negative when not known (no hit was seen this tick).
	 */
	private static float restHealth(Mob mob, float health) {
		if (health < 0) {
			return -1;
		}
		return Math.min(mob.getMaxHealth(), health + frontDamage.getOrDefault(mob, 0.0F));
	}

	// --- dying and using a stack ---

	/**
	 * Before {@code LivingEntity.die}: remembers a stack that is about to lose one mob. Not for
	 * {@code /kill} (the only source of generic_kill damage): an admin clearing mobs means all of them.
	 */
	public static void beforeDeath(LivingEntity entity, DamageSource source) {
		if (source.is(DamageTypes.GENERIC_KILL)) {
			return;
		}
		// Same guard as die itself: a second call on a dead mob does nothing.
		if (entity instanceof Mob mob && mob.level() instanceof ServerLevel && !mob.isRemoved()
			&& !((LivingEntityAccessor) mob).tickengine$isDead() && count(mob) > 1) {
			// The health before the killing hit; unknown when the mob dies without one (a mod calling die).
			HitStart start = hitStart.get(mob);
			remember(mob, restHealth(mob, start != null && start.tick() == mob.tickCount ? start.health() : -1));
		}
	}

	/** After {@code LivingEntity.die}: the rest of the stack takes the dead mob's place. */
	public static void afterDeath(LivingEntity entity) {
		Pending before = entity instanceof Mob mob ? pending.remove(mob) : null;
		if (before == null || !((LivingEntityAccessor) entity).tickengine$isDead()) {
			return;
		}
		Mob mob = (Mob) entity;
		CompoundTag data = before.data();
		for (String key : new String[] {"Motion", "HurtTime", "HurtByTimestamp", "DeathTime", "active_effects", "ActiveEffects",
			"AbsorptionAmount"}) {
			data.remove(key);
		}
		if (before.restHealth() <= 0) {
			// The hit that killed it was not seen: the rest starts afresh, at full health, not falling or burning.
			data.remove("Fire");
			data.remove("FallDistance");
			data.remove("fall_distance");
		}
		// Otherwise the rest keeps its fall and its fire, which it shares with the dead mob: in a farm
		// that kills by falling, the next mob takes the same fall and dies the same way.
		spawnRest(mob, data, before.count() - 1, before.restHealth() > 0 ? before.restHealth() : mob.getMaxHealth());
		// The dead one lies there as a single mob, so its loot and name read right.
		setCount(mob, 1);
	}

	/** Before {@code Player.interactOn}: remembers a stack a player is using. */
	public static void beforeInteract(Mob mob) {
		if (mob.level() instanceof ServerLevel && count(mob) > 1) {
			remember(mob, restHealth(mob, mob.getHealth()));
		}
	}

	/**
	 * After {@code Player.interactOn}: if it did something, it did it to one mob and the rest moves to a
	 * copy. Also when the mob is gone: a bucket took one fish, or shearing turned one mooshroom into a cow.
	 */
	public static void afterInteract(Mob mob, InteractionResult result) {
		Pending before = pending.remove(mob);
		if (before == null || result == null || !result.consumesAction()) {
			return;
		}
		spawnRest(mob, before.data(), before.count() - 1, before.restHealth());
		if (!mob.isRemoved()) {
			setCount(mob, 1);
		}
	}

	/**
	 * After {@code Mob.convertTo}: a stack that turns into another mob (a zombie drowning, a pig struck by
	 * lightning) stays a stack with the same count and gets the new mob's name. One that converts while a
	 * player uses it (shearing a mooshroom) or while it dies is one mob: the rest stays as it was.
	 */
	public static void afterConversion(Mob from, Mob to) {
		if (to == null || !(from.level() instanceof ServerLevel)) {
			return;
		}
		int count = count(from);
		if (count > 1) {
			setCount(to, pending.containsKey(from) ? 1 : count);
		}
	}

	/** {@code restHealth}: health of the mobs that stay in the stack, negative when not known. */
	private record Pending(int count, CompoundTag data, float restHealth) {
	}

	private static void remember(Mob mob, float restHealth) {
		pending.put(mob, new Pending(count(mob), MinecraftCompat.saveEntity(mob), restHealth));
	}

	/**
	 * Adds a copy of the mob, made from its data from before, holding {@code count} mobs, with
	 * {@code health} (the saved health when it is not positive).
	 */
	private static void spawnRest(Mob mob, CompoundTag data, int count, float health) {
		if (count < 1 || !(mob.level() instanceof ServerLevel level)) {
			return;
		}
		if (health > 0) {
			data.putFloat("Health", health);
		}
		data.remove("UUID");
		data.remove("carriedBlockState");
		Entity copy = MinecraftCompat.loadEntity(data, level);
		if (!(copy instanceof Mob rest)) {
			if (!loadErrorLogged) {
				loadErrorLogged = true;
				TickEngine.LOGGER.warn("Entity stacking: could not copy a {} stack; {} mobs of it are gone. Please report "
					+ "this with '/tickengine report entity {}'.", EntityType.getKey(mob.getType()), count,
					EntityType.getKey(mob.getType()));
			}
			return;
		}
		dropAcquired(rest);
		setCount(rest, count);
		level.addFreshEntity(rest);
	}

	// --- mob cap ---

	/**
	 * The entities natural spawning counts, with every stack repeated once per mob in it, so a
	 * stack of 10 fills the mob cap like 10 mobs.
	 */
	public static Iterable<Entity> expandStacks(Iterable<Entity> entities) {
		return () -> new Iterator<>() {
			private final Iterator<Entity> source = entities.iterator();
			private Entity current;
			private int left;

			@Override
			public boolean hasNext() {
				return left > 0 || source.hasNext();
			}

			@Override
			public Entity next() {
				if (left > 0) {
					left--;
					return current;
				}
				if (!source.hasNext()) {
					throw new NoSuchElementException();
				}
				current = source.next();
				left = current instanceof Mob ? count(current) - 1 : 0;
				return current;
			}
		};
	}

	// --- unstacking ---

	/** Splits every loaded stack back into single mobs. Returns how many mobs were added. */
	public static int unstackAll(MinecraftServer server) {
		int added = 0;
		for (ServerLevel level : server.getAllLevels()) {
			List<Mob> stacks = new ArrayList<>();
			for (Entity entity : level.getAllEntities()) {
				if (entity instanceof Mob mob && mob.isAlive() && isStack(mob)) {
					stacks.add(mob);
				}
			}
			for (Mob stack : stacks) {
				int count = count(stack);
				float restHealth = restHealth(stack, stack.getHealth());
				setCount(stack, 1);
				CompoundTag data = MinecraftCompat.saveEntity(stack);
				for (int i = 1; i < count; i++) {
					spawnRest(stack, data.copy(), 1, restHealth);
					added++;
				}
			}
		}
		return added;
	}

	/** Loaded stacks and the mobs they hold, for status lines. */
	public static int[] loadedStacks(MinecraftServer server) {
		int stacks = 0;
		int mobs = 0;
		for (ServerLevel level : server.getAllLevels()) {
			for (Entity entity : level.getAllEntities()) {
				int count = entity instanceof Mob ? count(entity) : 1;
				if (count > 1) {
					stacks++;
					mobs += count;
				}
			}
		}
		return new int[] {stacks, mobs};
	}

	public static long mergesTotal() {
		return mergesTotal;
	}

	public static void reset() {
		ticksSinceRound = 0;
		rounds = 0;
		groups.clear();
		excluded.clear();
		pending.clear();
		frontDamage.clear();
		hitStart.clear();
		mergesTotal = 0;
	}
}
