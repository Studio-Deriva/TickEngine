package com.studioderiva.tickengine.throttle;

import com.studioderiva.tickengine.config.TickEngineConfig;
import com.studioderiva.tickengine.tuning.AutoTuner;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Decides whether a block entity skips this tick.
 *
 * <p>Once per level per tick, {@link #prepare} collects the chunks near players. Block entities
 * in those chunks always tick. Every other block entity ticks only on game times divisible by
 * its interval (from the rules), so a beacon checking {@code gameTime % 80 == 0} still runs
 * when the interval divides 80. Server thread only.
 */
public final class BlockEntityThrottle {
	private static final LongSet activeChunks = new LongOpenHashSet();
	private static ServerLevel preparedLevel;
	private static long preparedGameTime = Long.MIN_VALUE;
	private static LongSet forcedChunks = LongSet.of();
	private static boolean useInterval;

	/** Resolved interval per type, per dimension. Cleared when the config version changes. */
	private static final Map<ResourceKey<Level>, Reference2IntOpenHashMap<BlockEntityType<?>>> intervals = new HashMap<>();
	private static int intervalsVersion = -1;

	// Statistics for the last full second (20 game ticks), shown by /tickengine status.
	private static long tickedNow;
	private static long skippedNow;
	private static long statsTicks;
	private static long tickedLastSecond;
	private static long skippedLastSecond;

	private BlockEntityThrottle() {
	}

	/** Called at the start of block entity ticking for each server level. */
	public static void prepare(ServerLevel level) {
		preparedLevel = level;
		preparedGameTime = level.getGameTime();
		useInterval = TickEngineConfig.blockEntityThrottle;
		if (!useInterval) {
			return;
		}
		if (intervalsVersion != TickEngineConfig.version) {
			intervals.clear();
			intervalsVersion = TickEngineConfig.version;
		}
		activeChunks.clear();
		int radius = AutoTuner.values().blockEntityRadius();
		for (ServerPlayer player : level.players()) {
			ChunkPos center = player.chunkPosition();
			for (int x = center.x - radius; x <= center.x + radius; x++) {
				for (int z = center.z - radius; z <= center.z + radius; z++) {
					activeChunks.add(ChunkPos.asLong(x, z));
				}
			}
		}
		forcedChunks = level.getForceLoadedChunks();
	}

	/** True when the block entity must not tick this game tick. */
	public static boolean shouldSkip(BlockEntity blockEntity) {
		if (!useInterval || blockEntity.getLevel() != preparedLevel) {
			return false;
		}
		BlockPos pos = blockEntity.getBlockPos();
		long chunk = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
		if (activeChunks.contains(chunk)) {
			tickedNow++;
			return false;
		}
		int interval = interval(blockEntity.getType());
		if (interval > 1 && forcedChunks.contains(chunk)) {
			interval = Math.min(interval, TickEngineConfig.forcedChunkMaxInterval);
		}
		if (interval <= 1) {
			tickedNow++;
			return false;
		}
		long time = preparedGameTime;
		if (TickEngineConfig.stagger) {
			// Same offset for a whole chunk keeps neighbouring machines in step with each other.
			time += Long.hashCode(chunk) & 0x7FFFFFFF;
		}
		if (time % interval == 0) {
			tickedNow++;
			return false;
		}
		skippedNow++;
		return true;
	}

	private static int interval(BlockEntityType<?> type) {
		Reference2IntOpenHashMap<BlockEntityType<?>> cache = intervals.computeIfAbsent(preparedLevel.dimension(), key -> {
			Reference2IntOpenHashMap<BlockEntityType<?>> map = new Reference2IntOpenHashMap<>();
			map.defaultReturnValue(-1);
			return map;
		});
		int interval = cache.getInt(type);
		if (interval < 0) {
			Identifier id = BlockEntityType.getKey(type);
			// Unregistered types cannot be matched by rules: leave them alone.
			interval = id == null ? 1 : TickEngineConfig.BLOCK_ENTITY_RULES.resolve(id,
				preparedLevel.dimension().identifier(), TickEngineConfig.dimensionRulesEnabled,
				AutoTuner.values().blockEntityInterval());
			cache.put(type, interval);
		}
		return interval;
	}

	/** Called at the end of every server tick. */
	public static void onTickEnd() {
		preparedLevel = null;
		if (++statsTicks >= 20) {
			tickedLastSecond = tickedNow;
			skippedLastSecond = skippedNow;
			tickedNow = 0;
			skippedNow = 0;
			statsTicks = 0;
		}
	}

	public static long tickedLastSecond() {
		return tickedLastSecond;
	}

	public static long skippedLastSecond() {
		return skippedLastSecond;
	}

	public static void reset() {
		preparedLevel = null;
		activeChunks.clear();
		forcedChunks = LongSet.of();
		intervals.clear();
		tickedNow = skippedNow = statsTicks = tickedLastSecond = skippedLastSecond = 0;
	}
}
