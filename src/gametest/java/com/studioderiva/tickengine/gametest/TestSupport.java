package com.studioderiva.tickengine.gametest;

import com.studioderiva.tickengine.config.TickEngineConfig;
import com.studioderiva.tickengine.tuning.AutoTuner;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;

/** Shared setup for the GameTests: config baseline, entities, furnaces, commands. */
final class TestSupport {
	/** Config values as loaded at startup (defaults, since run-gametest starts without a config file). */
	private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

	static {
		TickEngineConfig.options().forEach((key, option) -> DEFAULTS.put(key, option.format()));
	}

	/** The default of a config option, as loaded at startup. */
	static String defaultValue(String key) {
		return DEFAULTS.get(key);
	}

	private TestSupport() {
	}

	/**
	 * Puts the config back to the defaults, then applies the given key/value pairs. The baseline
	 * also turns off what would make results depend on timing or test order: auto-tuning (real
	 * MSPT spikes at startup), waking new entities, tick spreading (it moves tick counters 5 s
	 * after loading; SpreadTests turn it on), and the cap for force-loaded chunks (GameTest
	 * force-loads every test area).
	 */
	static void configure(GameTestHelper helper, String... keyValues) {
		MinecraftServer server = helper.getLevel().getServer();
		for (ServerLevel level : server.getAllLevels()) {
			for (ServerPlayer player : new ArrayList<>(level.players())) {
				level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
			}
		}
		DEFAULTS.forEach((key, value) -> set(key, value));
		set("autoTuning.enabled", "false");
		set("entities.wakeNewEntities", "false");
		set("entities.spreadTicks", "false");
		set("blockEntities.forcedChunkMaxInterval", "100");
		set("entities.forcedChunkMaxInterval", "100");
		for (int i = 0; i < keyValues.length; i += 2) {
			set(keyValues[i], keyValues[i + 1]);
		}
		TickEngineConfig.changed();
		AutoTuner.forceLevel(server, 0);
		AutoTuner.resume();
	}

	private static void set(String key, String value) {
		String error = TickEngineConfig.options().get(key).set(value);
		if (error != null) {
			throw new IllegalStateException("Cannot set " + key + " = " + value + ": " + error);
		}
	}

	static void check(boolean condition, String message) {
		if (!condition) {
			throw new GameTestAssertException(message);
		}
	}

	static void checkBetween(long value, long min, long max, String what) {
		check(value >= min && value <= max, what + " was " + value + ", expected " + min + ".." + max);
	}

	// GameTestHelper coordinates start one block below the structure: the floor is at y = 1 and
	// things standing on it go at y = 2.

	/** Adds an entity without GameTestHelper.spawn, which marks mobs persistent (that resets their idle time). */
	static <E extends Entity> E spawnPlain(GameTestHelper helper, EntityType<E> type, double x, double y, double z) {
		E entity = create(helper, type, x, y, z);
		helper.getLevel().addFreshEntity(entity);
		return entity;
	}

	/** Creates an entity in the test area without adding it to the world. */
	static <E extends Entity> E create(GameTestHelper helper, EntityType<E> type, double x, double y, double z) {
		E entity = type.create(helper.getLevel());
		Vec3 pos = helper.absoluteVec(new Vec3(x, y, z));
		entity.moveTo(pos.x, pos.y, pos.z, 0, 0);
		return entity;
	}

	/** Lit furnace smelting cobblestone, with plenty of fuel. */
	static FurnaceBlockEntity litFurnace(GameTestHelper helper, BlockPos relative) {
		helper.setBlock(relative, Blocks.FURNACE);
		FurnaceBlockEntity furnace = (FurnaceBlockEntity) helper.getBlockEntity(relative);
		furnace.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
		furnace.setItem(1, new ItemStack(Items.COAL, 64));
		return furnace;
	}

	static int burnTime(FurnaceBlockEntity furnace) {
		CompoundTag tag = furnace.saveWithoutMetadata();
		return tag.getShort("BurnTime");
	}

	/** Adds a goal that never starts and counts how often the goal selector asks whether it can. */
	static int[] countGoalChecks(Mob mob) {
		int[] count = {0};
		goalSelector(mob).addGoal(0, new Goal() {
			@Override
			public boolean canUse() {
				count[0]++;
				return false;
			}
		});
		return count;
	}

	private static GoalSelector goalSelector(Mob mob) {
		try {
			Field field = Mob.class.getDeclaredField("goalSelector");
			field.setAccessible(true);
			return (GoalSelector) field.get(mob);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * A player in the level's player list (what the throttles look at). GameTestHelper's mock
	 * player crashes on Forge 1.20.1 (its Connection has no channel), so this adds a FakePlayer,
	 * whose network handler drops every packet.
	 */
	static ServerPlayer playerAt(GameTestHelper helper, double absoluteX, double absoluteZ) {
		ServerLevel level = helper.getLevel();
		FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "tickengine-test"));
		player.moveTo(absoluteX, helper.absolutePos(BlockPos.ZERO).getY() + 2, absoluteZ, 0, 0);
		level.addNewPlayer(player);
		return player;
	}

	/** Result and chat output of a command run with full permissions (or the given source). */
	record CommandResult(int result, List<String> messages) {
		String text() {
			return String.join("\n", messages);
		}
	}

	static CommandResult run(GameTestHelper helper, String command) {
		return run(helper.getLevel().getServer().createCommandSourceStack(), command);
	}

	static CommandResult run(CommandSourceStack base, String command) {
		List<String> messages = new ArrayList<>();
		CommandSource capture = new CommandSource() {
			@Override
			public void sendSystemMessage(Component component) {
				messages.add(component.getString());
			}

			@Override
			public boolean acceptsSuccess() {
				return true;
			}

			@Override
			public boolean acceptsFailure() {
				return true;
			}

			@Override
			public boolean shouldInformAdmins() {
				return false;
			}
		};
		CommandSourceStack source = base.withSource(capture);
		int result = source.getServer().getCommands().performPrefixedCommand(source, command);
		return new CommandResult(result, messages);
	}

	static ServerLevel level(GameTestHelper helper) {
		return helper.getLevel();
	}
}
