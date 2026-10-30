package com.studioderiva.tickengine.tuning;

import com.studioderiva.tickengine.TickEngine;
import com.studioderiva.tickengine.config.TickEngineConfig;
import com.studioderiva.tickengine.notice.AdminNotices;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Emergency mode: the last line of defence when the server is about to fall over.
 *
 * <p>Once per 20 ticks (or per second, when ticks are slower) the average tick time of those ticks is compared with
 * {@code emergency.triggerMspt}, and the old generation of the heap after the last garbage
 * collection with {@code emergency.memoryPercent}. When either stays critical for
 * {@code triggerSeconds} of real time (ticks are slow then, so tick counts would stretch it),
 * the mode starts; it ends after {@code exitSeconds} below {@code exitMspt} with memory fine.
 *
 * <p>While active: auto-tuning goes to its highest level and stays there, throttled entities and
 * block entities tick at most once every {@code emergency.interval} ticks (see
 * {@link #adjustInterval}), natural spawning pauses, and stacking runs when its mode allows.
 * Nothing is changed for good: all of it is runtime only, and auto-tuning goes back to its level
 * from before when the mode ends. Stacks made during it stay. Server thread only.
 */
public final class EmergencyMode {
	/** Minimum time between two saves on entering. */
	private static final long SAVE_COOLDOWN_NANOS = 10L * 60 * 1_000_000_000;

	public enum Change { NONE, ENTER, EXIT }

	private static boolean active;
	private static boolean manual;
	private static String reason = "";
	private static long activeSinceNanos;
	private static long lastSaveNanos = Long.MIN_VALUE;

	private static long tickStartNanos;
	private static long windowStartNanos;
	private static long windowNanos;
	private static int windowTicks;
	private static double lastMspt;
	/** When the load became critical (or calm, while active); -1 when it is not. */
	private static long criticalSinceNanos = -1;
	private static long calmSinceNanos = -1;

	private static MemoryPoolMXBean oldGeneration;
	private static boolean oldGenerationLooked;

	private EmergencyMode() {
	}

	public static boolean isActive() {
		return active;
	}

	/** Natural mob spawning is paused right now. */
	public static boolean pausesSpawning() {
		return active && TickEngineConfig.emergencyPauseSpawning;
	}

	/** A throttle interval during an emergency: at least emergency.interval, except 1 (never throttle) stays. */
	public static int adjustInterval(int interval) {
		return active && interval > 1 ? Math.max(interval, TickEngineConfig.emergencyInterval) : interval;
	}

	public static void onTickStart() {
		tickStartNanos = System.nanoTime();
	}

	public static void onTickEnd(MinecraftServer server) {
		long now = System.nanoTime();
		windowNanos += now - tickStartNanos;
		// Every 20 ticks, or every second when ticks are slower than that: when the heap is nearly
		// full, garbage collection can stretch every tick to seconds, and 20 of them would be minutes.
		if (++windowTicks < 20 && now - windowStartNanos < 1_000_000_000L) {
			return;
		}
		lastMspt = windowNanos / (double) windowTicks / 1_000_000.0;
		windowNanos = 0;
		windowTicks = 0;
		windowStartNanos = now;
		int memory = oldGenerationPercent();
		boolean memoryCritical = TickEngineConfig.emergencyMemoryPercent > 0 && memory >= TickEngineConfig.emergencyMemoryPercent;
		Change change = update(lastMspt, memoryCritical, System.nanoTime());
		if (change == Change.ENTER) {
			enter(server, memoryCritical && lastMspt <= TickEngineConfig.emergencyTriggerMspt
				? "old generation " + memory + "% full after garbage collection"
				: "average MSPT " + format(lastMspt) + " ms above " + TickEngineConfig.emergencyTriggerMspt + " for "
					+ TickEngineConfig.emergencyTriggerSeconds + " s", false);
		} else if (change == Change.EXIT) {
			exit(server, "average MSPT below " + TickEngineConfig.emergencyExitMspt + " for "
				+ TickEngineConfig.emergencyExitSeconds + " s");
		}
	}

	/**
	 * Decides whether the mode starts or ends, from one 20-tick measurement. Package-private for
	 * tests; does not change {@link #active} itself.
	 */
	static Change update(double mspt, boolean memoryCritical, long nowNanos) {
		boolean critical = mspt > TickEngineConfig.emergencyTriggerMspt || memoryCritical;
		if (!active) {
			calmSinceNanos = -1;
			if (!TickEngineConfig.emergencyEnabled || !critical) {
				criticalSinceNanos = -1;
				return Change.NONE;
			}
			if (criticalSinceNanos < 0) {
				criticalSinceNanos = nowNanos;
			}
			return nowNanos - criticalSinceNanos >= TickEngineConfig.emergencyTriggerSeconds * 1_000_000_000L
				? Change.ENTER : Change.NONE;
		}
		criticalSinceNanos = -1;
		if (!TickEngineConfig.emergencyEnabled && !manual) {
			return Change.EXIT;
		}
		// An exit threshold at or above the trigger (rejected by commands, possible in the file) would
		// leave right away: then only below the trigger counts as calm.
		if (mspt >= Math.min(TickEngineConfig.emergencyExitMspt, TickEngineConfig.emergencyTriggerMspt) || memoryCritical) {
			calmSinceNanos = -1;
			return Change.NONE;
		}
		if (calmSinceNanos < 0) {
			calmSinceNanos = nowNanos;
		}
		return nowNanos - calmSinceNanos >= TickEngineConfig.emergencyExitSeconds * 1_000_000_000L
			? Change.EXIT : Change.NONE;
	}

	/** Starts the mode. {@code byCommand}: started by an admin, also when emergency.enabled is false. */
	public static void enter(MinecraftServer server, String why, boolean byCommand) {
		if (active) {
			return;
		}
		active = true;
		manual = byCommand;
		reason = why;
		activeSinceNanos = System.nanoTime();
		criticalSinceNanos = -1;
		calmSinceNanos = -1;
		// Throttles cache intervals per type: make them pick up the emergency interval.
		TickEngineConfig.invalidateCaches();
		TickEngine.LOGGER.warn("Emergency mode ON ({}): auto-tuning to its highest level, far entities and block entities "
			+ "at most every {} ticks{}.", why, TickEngineConfig.emergencyInterval,
			TickEngineConfig.emergencyPauseSpawning ? ", natural mob spawning paused" : "");
		if (server != null) {
			AutoTuner.emergency(server);
			AdminNotices.sendToAdmins(server, Component.literal("Emergency mode ON (" + why + "). TickEngine throttles as "
				+ "hard as it can until the load is gone. '/tickengine emergency' shows what it changed.")
				.withStyle(ChatFormatting.RED));
			long now = System.nanoTime();
			if (TickEngineConfig.emergencySaveOnEnter && (lastSaveNanos == Long.MIN_VALUE || now - lastSaveNanos >= SAVE_COOLDOWN_NANOS)) {
				lastSaveNanos = now;
				TickEngine.LOGGER.info("Emergency mode: saving the world.");
				server.saveEverything(false, false, false);
			}
		}
	}

	public static void exit(MinecraftServer server, String why) {
		if (!active) {
			return;
		}
		long seconds = (System.nanoTime() - activeSinceNanos) / 1_000_000_000L;
		active = false;
		manual = false;
		calmSinceNanos = -1;
		TickEngineConfig.invalidateCaches();
		TickEngine.LOGGER.info("Emergency mode OFF ({}) after {} s. Auto-tuning goes back to its level from before.", why, seconds);
		AutoTuner.emergencyOver(server);
		if (server != null) {
			AdminNotices.sendToAdmins(server, Component.literal("Emergency mode OFF (" + why + ") after " + seconds
				+ " s. Auto-tuning is back at its level from before; stacks made during it stay.").withStyle(ChatFormatting.GREEN));
		}
	}

	/**
	 * How full the old generation of the heap was after the last garbage collection that covered it,
	 * in percent of its maximum (of the whole heap when it has none); -1 when the JVM does not report it.
	 */
	public static int oldGenerationPercent() {
		if (!oldGenerationLooked) {
			oldGenerationLooked = true;
			for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
				String name = pool.getName().toLowerCase(Locale.ROOT);
				if (pool.getType() == MemoryType.HEAP && pool.isCollectionUsageThresholdSupported()
					&& (name.contains("old") || name.contains("tenured"))) {
					oldGeneration = pool;
					break;
				}
			}
		}
		if (oldGeneration == null) {
			return -1;
		}
		MemoryUsage usage = oldGeneration.getCollectionUsage();
		if (usage == null) {
			return -1;
		}
		long max = usage.getMax() > 0 ? usage.getMax() : Runtime.getRuntime().maxMemory();
		return (int) (usage.getUsed() * 100 / max);
	}

	public static void shutdown() {
		active = false;
		manual = false;
		reason = "";
		windowNanos = 0;
		windowTicks = 0;
		windowStartNanos = 0;
		lastMspt = 0;
		criticalSinceNanos = -1;
		calmSinceNanos = -1;
	}

	public static boolean isManual() {
		return manual;
	}

	public static String reason() {
		return reason;
	}

	public static long activeSeconds() {
		return active ? (System.nanoTime() - activeSinceNanos) / 1_000_000_000L : 0;
	}

	/** Seconds the load has been critical while waiting to enter; -1 when it is not critical. */
	public static long criticalSeconds() {
		return criticalSinceNanos < 0 ? -1 : (System.nanoTime() - criticalSinceNanos) / 1_000_000_000L;
	}

	/** Seconds the load has been low while waiting to leave; -1 when it is not low. */
	public static long calmSeconds() {
		return calmSinceNanos < 0 ? -1 : (System.nanoTime() - calmSinceNanos) / 1_000_000_000L;
	}

	public static double lastMspt() {
		return lastMspt;
	}

	private static String format(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}
}
