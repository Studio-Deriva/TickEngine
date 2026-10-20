package com.studioderiva.tickengine.compat;

import com.studioderiva.tickengine.TickEngine;
import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Reads whether ServerCore's entity activation range is enabled, without a compile-time
 * dependency: {@code Config.get().activationRange().enabled()} via reflection (API checked
 * against ServerCore 1.5.15+1.21.11). ServerCore can reload its config, so the value is read
 * again every {@link #CHECK_INTERVAL_TICKS} ticks.
 *
 * <p>If ServerCore is installed but its API cannot be read (for example after an update that
 * renames it), the activation range is assumed to be on, which is the safe side.
 */
public final class ServerCoreCompat {
	private static final int CHECK_INTERVAL_TICKS = 600;
	private static final boolean INSTALLED = FabricLoader.getInstance().isModLoaded("servercore");

	private static Method configGet;
	private static Method activationRange;
	private static Method enabled;
	private static boolean apiBroken;

	private static boolean cachedEnabled;
	private static int ticksUntilCheck;

	private ServerCoreCompat() {
	}

	public static boolean installed() {
		return INSTALLED;
	}

	/** True when ServerCore is installed and its entity activation range is on (or unreadable). */
	public static boolean activationRangeEnabled() {
		return cachedEnabled;
	}

	/** True when the state could not be read and is only assumed. */
	public static boolean assumed() {
		return INSTALLED && apiBroken;
	}

	/** Called every server tick; re-reads the ServerCore config periodically. */
	public static void tick() {
		if (!INSTALLED || --ticksUntilCheck > 0) {
			return;
		}
		ticksUntilCheck = CHECK_INTERVAL_TICKS;
		cachedEnabled = read();
	}

	/** Reads the value now (at startup and after a config reload). */
	public static void refresh() {
		ticksUntilCheck = CHECK_INTERVAL_TICKS;
		cachedEnabled = INSTALLED && read();
	}

	private static boolean read() {
		if (apiBroken) {
			return true;
		}
		try {
			if (configGet == null) {
				ClassLoader loader = ServerCoreCompat.class.getClassLoader();
				configGet = Class.forName("me.wesley1808.servercore.common.config.Config", true, loader).getMethod("get");
				activationRange = Class.forName("me.wesley1808.servercore.common.config.MainConfig", true, loader)
					.getMethod("activationRange");
				enabled = Class.forName("me.wesley1808.servercore.common.config.data.activation_range.ActivationRangeConfig",
					true, loader).getMethod("enabled");
			}
			Object config = configGet.invoke(null);
			if (config == null) {
				// Not loaded yet: assume on until the next check.
				return true;
			}
			return (boolean) enabled.invoke(activationRange.invoke(config));
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			apiBroken = true;
			TickEngine.LOGGER.warn("ServerCore is installed but its config could not be read; assuming its entity "
				+ "activation range is enabled.", e);
			return true;
		}
	}
}
