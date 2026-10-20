package com.studioderiva.tickengine.tuning;

import com.studioderiva.tickengine.TickEngine;
import com.studioderiva.tickengine.compat.ServerCoreCompat;
import com.studioderiva.tickengine.config.TickEngineConfig;
import com.studioderiva.tickengine.notice.AdminNotices;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;

/**
 * MSPT-based auto-tuning.
 *
 * <p>Every {@code decisionSeconds} the average tick time over that period is compared with two
 * thresholds: above {@code raiseAboveMspt} the level goes up by one, below {@code lowerBelowMspt}
 * it goes down by one. A level is a number of steps applied in a fixed order, least visible
 * first:
 * <ol>
 *   <li>default throttle intervals, through safe values only (2, 4, 5, 10, 20)</li>
 *   <li>throttle radii, one chunk per step</li>
 *   <li>simulation distance, one per step</li>
 *   <li>view distance, one per step</li>
 * </ol>
 *
 * <p>Everything is runtime only. Distances are changed through {@link PlayerList}, which does not
 * write server.properties (DedicatedServer's setters would). Level 0 restores the original
 * distances, and so does stopping the server. Server thread only.
 */
public final class AutoTuner {
	/** Intervals that divide 20, 40 and 80, so periodic logic keeps running when throttled. */
	private static final int[] SAFE_INTERVALS = {2, 4, 5, 10, 20, 40, 60, 80, 100};

	/** Effective values the throttles use. Distances are -1 when auto-tuning does not touch them. */
	public record Values(int blockEntityInterval, int blockEntityRadius, int entityAiInterval, int entityTickInterval,
		int entityFullRadius, int entityAiRadius, int simulationDistance, int viewDistance) {
	}

	private static MinecraftServer server;
	private static int level;
	private static int maxLevel;
	private static boolean paused;
	private static Values values;
	private static int valuesConfigVersion = -1;

	/** Distances before auto-tuning changed them; -1 when untouched. */
	private static int originalSimulationDistance = -1;
	private static int originalViewDistance = -1;

	private static long tickStartNanos;
	private static long windowNanos;
	private static int windowTicks;
	private static double lastAverageMspt;

	private AutoTuner() {
	}

	/** The values the throttles use right now. */
	public static Values values() {
		if (values == null || valuesConfigVersion != TickEngineConfig.version) {
			values = compute(level);
			maxLevel = countMaxLevel();
			valuesConfigVersion = TickEngineConfig.version;
		}
		return values;
	}

	public static void onTickStart() {
		tickStartNanos = System.nanoTime();
	}

	public static void onTickEnd(MinecraftServer currentServer) {
		server = currentServer;
		windowNanos += System.nanoTime() - tickStartNanos;
		windowTicks++;
		if (windowTicks < TickEngineConfig.decisionSeconds * 20) {
			return;
		}
		lastAverageMspt = windowNanos / (double) windowTicks / 1_000_000.0;
		windowNanos = 0;
		windowTicks = 0;

		if (!TickEngineConfig.autoTuning) {
			if (level != 0) {
				setLevel(0, "auto-tuning disabled");
			}
			return;
		}
		refresh();
		if (paused) {
			return;
		}
		if (lastAverageMspt > TickEngineConfig.raiseAboveMspt && level < maxLevel) {
			setLevel(level + 1, "average MSPT " + format(lastAverageMspt) + " ms > " + TickEngineConfig.raiseAboveMspt);
		} else if (lastAverageMspt < TickEngineConfig.lowerBelowMspt && level > 0
			// Inconsistent thresholds would raise and lower on alternate decisions forever.
			&& TickEngineConfig.lowerBelowMspt < TickEngineConfig.raiseAboveMspt) {
			setLevel(level - 1, "average MSPT " + format(lastAverageMspt) + " ms < " + TickEngineConfig.lowerBelowMspt);
		}
	}

	/** Forces a level and pauses automatic changes until {@link #resume()}. Returns the level set. */
	public static int forceLevel(MinecraftServer currentServer, int requested) {
		server = currentServer;
		refresh();
		paused = true;
		setLevel(Math.max(0, Math.min(requested, maxLevel)), "set by command");
		return level;
	}

	public static void pause() {
		paused = true;
	}

	public static void resume() {
		paused = false;
	}

	/** Back to level 0 and the original distances. Called when the server stops. */
	public static void shutdown() {
		if (level != 0) {
			setLevel(0, "server stopping");
		}
		restoreDistances();
		server = null;
		paused = false;
		windowNanos = 0;
		windowTicks = 0;
		lastAverageMspt = 0;
	}

	private static void setLevel(int newLevel, String reason) {
		int oldLevel = level;
		Values before = values();
		level = newLevel;
		values = compute(level);
		applyDistances(values);
		// Throttles cache intervals per type: make them pick up the new defaults.
		TickEngineConfig.invalidateCaches();
		valuesConfigVersion = TickEngineConfig.version;

		String changes = describeChanges(before, values);
		TickEngine.LOGGER.info("Auto-tuning level {} -> {} ({}): {}", oldLevel, newLevel, reason, changes);
		if (server != null && TickEngineConfig.notifyTuningChanges) {
			AdminNotices.sendToAdmins(server, Component.literal("Auto-tuning level " + newLevel + "/" + maxLevel + " (" + reason
				+ "): " + changes).withStyle(ChatFormatting.GRAY));
		}
	}

	/**
	 * Recomputes values and the maximum level from the current config, server distances and
	 * ServerCore state, and applies the distances. Called before each decision.
	 */
	private static void refresh() {
		values = compute(level);
		maxLevel = countMaxLevel();
		valuesConfigVersion = TickEngineConfig.version;
		if (level > maxLevel) {
			setLevel(maxLevel, "fewer levels available");
		} else {
			applyDistances(values);
		}
	}

	private static boolean distancesManagedByServerCore() {
		return ServerCoreCompat.dynamicSettingsEnabled();
	}

	private static Values compute(int targetLevel) {
		int beInterval = TickEngineConfig.defaultInterval;
		int aiInterval = TickEngineConfig.defaultAiInterval;
		int tickInterval = TickEngineConfig.defaultTickInterval;
		int beRadius = TickEngineConfig.activeRadiusChunks;
		int fullRadius = TickEngineConfig.entityFullRadiusChunks;
		int aiRadius = TickEngineConfig.entityAiRadiusChunks;
		int simulation = -1;
		int view = -1;
		int remaining = targetLevel;

		if (TickEngineConfig.tuneIntervals) {
			while (remaining > 0) {
				int nextBe = nextSafe(beInterval);
				int nextAi = nextSafe(aiInterval);
				int nextTick = nextSafe(tickInterval);
				if (nextBe == beInterval && nextAi == aiInterval && nextTick == tickInterval) {
					break;
				}
				beInterval = nextBe;
				aiInterval = nextAi;
				tickInterval = nextTick;
				remaining--;
			}
		}
		if (TickEngineConfig.tuneRadii) {
			while (remaining > 0) {
				int nextBe = beRadius > TickEngineConfig.minBlockEntityRadius ? beRadius - 1 : beRadius;
				int nextFull = fullRadius > TickEngineConfig.minEntityFullRadius ? fullRadius - 1 : fullRadius;
				int nextAi = aiRadius > Math.max(TickEngineConfig.minEntityAiRadius, nextFull) ? aiRadius - 1 : aiRadius;
				if (nextBe == beRadius && nextFull == fullRadius && nextAi == aiRadius) {
					break;
				}
				beRadius = nextBe;
				fullRadius = nextFull;
				aiRadius = nextAi;
				remaining--;
			}
		}
		if (!distancesManagedByServerCore() && server != null) {
			PlayerList players = server.getPlayerList();
			if (TickEngineConfig.tuneSimulationDistance) {
				simulation = originalSimulationDistance >= 0 ? originalSimulationDistance : players.getSimulationDistance();
				while (remaining > 0 && simulation > TickEngineConfig.minSimulationDistance) {
					simulation--;
					remaining--;
				}
			}
			if (TickEngineConfig.tuneViewDistance) {
				view = originalViewDistance >= 0 ? originalViewDistance : players.getViewDistance();
				while (remaining > 0 && view > TickEngineConfig.minViewDistance) {
					view--;
					remaining--;
				}
			}
		}
		return new Values(beInterval, beRadius, aiInterval, tickInterval, fullRadius, aiRadius, simulation, view);
	}

	/** Highest level that still changes something. */
	private static int countMaxLevel() {
		int candidate = 0;
		while (candidate < 1000 && !compute(candidate + 1).equals(compute(candidate))) {
			candidate++;
		}
		return candidate;
	}

	/** Next safe interval above the current one, within the configured maximum. 1 means "off" and stays. */
	private static int nextSafe(int interval) {
		if (interval <= 1) {
			return interval;
		}
		for (int safe : SAFE_INTERVALS) {
			if (safe > interval) {
				return safe <= TickEngineConfig.maxTunedInterval ? safe : interval;
			}
		}
		return interval;
	}

	private static void applyDistances(Values target) {
		if (server == null) {
			return;
		}
		PlayerList players = server.getPlayerList();
		if (target.simulationDistance() >= 0 && target.simulationDistance() != players.getSimulationDistance()) {
			if (originalSimulationDistance < 0) {
				originalSimulationDistance = players.getSimulationDistance();
			}
			players.setSimulationDistance(target.simulationDistance());
		}
		if (target.viewDistance() >= 0 && target.viewDistance() != players.getViewDistance()) {
			if (originalViewDistance < 0) {
				originalViewDistance = players.getViewDistance();
			}
			players.setViewDistance(target.viewDistance());
		}
		if (level == 0 || distancesManagedByServerCore()) {
			restoreDistances();
		}
	}

	private static void restoreDistances() {
		if (server == null) {
			return;
		}
		PlayerList players = server.getPlayerList();
		if (originalSimulationDistance >= 0) {
			players.setSimulationDistance(originalSimulationDistance);
			originalSimulationDistance = -1;
		}
		if (originalViewDistance >= 0) {
			players.setViewDistance(originalViewDistance);
			originalViewDistance = -1;
		}
	}

	private static String describeChanges(Values before, Values after) {
		List<String> changes = new ArrayList<>();
		change(changes, "block entity interval", before.blockEntityInterval(), after.blockEntityInterval());
		change(changes, "entity AI interval", before.entityAiInterval(), after.entityAiInterval());
		change(changes, "entity tick interval", before.entityTickInterval(), after.entityTickInterval());
		change(changes, "block entity radius", before.blockEntityRadius(), after.blockEntityRadius());
		change(changes, "entity full radius", before.entityFullRadius(), after.entityFullRadius());
		change(changes, "entity AI radius", before.entityAiRadius(), after.entityAiRadius());
		change(changes, "simulation distance", before.simulationDistance(), after.simulationDistance());
		change(changes, "view distance", before.viewDistance(), after.viewDistance());
		return changes.isEmpty() ? "no change" : String.join(", ", changes);
	}

	private static void change(List<String> changes, String name, int before, int after) {
		if (before != after && before >= 0 && after >= 0) {
			changes.add(name + " " + before + " -> " + after);
		}
	}

	public static int level() {
		return level;
	}

	/** Highest level with the current config, distances and ServerCore state (computed live). */
	public static int maxLevel(MinecraftServer currentServer) {
		server = currentServer;
		return countMaxLevel();
	}

	public static boolean isPaused() {
		return paused;
	}

	public static double lastAverageMspt() {
		return lastAverageMspt;
	}

	private static String format(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}
}
