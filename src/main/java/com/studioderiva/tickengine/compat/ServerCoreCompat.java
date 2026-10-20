package com.studioderiva.tickengine.compat;

import com.studioderiva.tickengine.TickEngine;
import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Reads whether ServerCore's entity activation range and dynamic settings are enabled, without a
 * compile-time dependency: {@code Config.get().activationRange().enabled()} and
 * {@code Config.get().dynamic().enabled()} via reflection (API checked against ServerCore
 * 1.5.15+1.21.11). ServerCore can reload its config, so the values are read again every
 * {@link #CHECK_INTERVAL_TICKS} ticks.
 *
 * <p>If ServerCore is installed but its API cannot be read (for example after an update that
 * renames it), the activation range is assumed to be on, which is the safe side.
 */
public final class ServerCoreCompat {
	private static final int CHECK_INTERVAL_TICKS = 600;
	private static final boolean INSTALLED = FabricLoader.getInstance().isModLoaded("servercore");

	private static Method configGet;
	private static Method activationRange;
	private static Method activationRangeEnabled;
	private static Method dynamic;
	private static Method dynamicEnabled;
	private static boolean apiBroken;

	private static boolean cachedEnabled;
	private static boolean cachedDynamicEnabled;
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

	/**
	 * True when ServerCore is installed and its dynamic settings (which change simulation and
	 * view distance by tick time) are on, or unreadable.
	 */
	public static boolean dynamicSettingsEnabled() {
		return cachedDynamicEnabled;
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
		refresh();
	}

	/** Reads the values now (at startup and after a config reload). */
	public static void refresh() {
		ticksUntilCheck = CHECK_INTERVAL_TICKS;
		if (!INSTALLED) {
			cachedEnabled = false;
			cachedDynamicEnabled = false;
			return;
		}
		read();
	}

	/** Reads both flags; on any failure both are assumed on, which is the safe side. */
	private static void read() {
		if (apiBroken) {
			cachedEnabled = true;
			cachedDynamicEnabled = true;
			return;
		}
		try {
			if (configGet == null) {
				ClassLoader loader = ServerCoreCompat.class.getClassLoader();
				configGet = Class.forName("me.wesley1808.servercore.common.config.Config", true, loader).getMethod("get");
				Class<?> mainConfig = Class.forName("me.wesley1808.servercore.common.config.MainConfig", true, loader);
				activationRange = mainConfig.getMethod("activationRange");
				dynamic = mainConfig.getMethod("dynamic");
				activationRangeEnabled = Class.forName(
					"me.wesley1808.servercore.common.config.data.activation_range.ActivationRangeConfig", true, loader)
					.getMethod("enabled");
				dynamicEnabled = Class.forName("me.wesley1808.servercore.common.config.data.dynamic.DynamicConfig", true, loader)
					.getMethod("enabled");
			}
			Object config = configGet.invoke(null);
			if (config == null) {
				// Not loaded yet: assume on until the next check.
				cachedEnabled = true;
				cachedDynamicEnabled = true;
				return;
			}
			cachedEnabled = (boolean) activationRangeEnabled.invoke(activationRange.invoke(config));
			cachedDynamicEnabled = (boolean) dynamicEnabled.invoke(dynamic.invoke(config));
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			apiBroken = true;
			cachedEnabled = true;
			cachedDynamicEnabled = true;
			TickEngine.LOGGER.warn("ServerCore is installed but its config could not be read; assuming its entity "
				+ "activation range and dynamic settings are enabled.", e);
		}
	}
}
