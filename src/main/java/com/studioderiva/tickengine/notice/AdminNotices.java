package com.studioderiva.tickengine.notice;

import com.studioderiva.tickengine.TickEngine;
import com.studioderiva.tickengine.compat.MinecraftCompat;
import com.studioderiva.tickengine.compat.ServerCoreCompat;
import com.studioderiva.tickengine.config.TickEngineConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Tells admins about configuration problems: when they join, then every
 * {@code notices.intervalMinutes} while the problem lasts. Admins are operators at
 * {@code permissions.commandOpLevel} or players with the {@code tickengine.notify} node.
 */
public final class AdminNotices {
	private static final String PERMISSION = TickEngine.MOD_ID + ".notify";
	private static final String CONFIG_PERMISSION = TickEngine.MOD_ID + ".config";

	private static long ticksUntilRepeat;
	private static boolean conflictLogged;

	private AdminNotices() {
	}

	/** Current problems, as chat lines. Empty when everything is fine. */
	public static List<Component> problems() {
		List<Component> problems = new ArrayList<>();
		if (serverCoreConflict()) {
			problems.add(Component.literal("Entity throttling is paused: ServerCore's entity activation range is "
				+ (ServerCoreCompat.assumed() ? "assumed to be on (its config could not be read)" : "on")
				+ ", and throttling entities twice would freeze them. Use only one: disable ServerCore's "
				+ "activation range, or set compatibility.disableEntityThrottleWithServerCore to false.")
				.withStyle(ChatFormatting.YELLOW));
		}
		if (TickEngineConfig.autoTuning && (TickEngineConfig.tuneSimulationDistance || TickEngineConfig.tuneViewDistance)
			&& ServerCoreCompat.dynamicSettingsEnabled()) {
			problems.add(Component.literal("Auto-tuning leaves simulation and view distance to ServerCore's dynamic settings"
				+ (ServerCoreCompat.assumed() ? " (assumed on: its config could not be read)" : "")
				+ " and only adjusts TickEngine's throttles. To let TickEngine manage the distances, disable ServerCore's "
				+ "dynamic settings; to hide this, set autoTuning.tuneSimulationDistance and tuneViewDistance to false.")
				.withStyle(ChatFormatting.YELLOW));
		}
		return problems;
	}

	/** Sends a message to every online admin, if notices are enabled. */
	public static void sendToAdmins(MinecraftServer server, Component message) {
		if (!TickEngineConfig.noticesEnabled) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (isAdmin(player)) {
				player.sendSystemMessage(Component.literal("[TickEngine] ").withStyle(ChatFormatting.DARK_AQUA).append(message));
			}
		}
	}

	private static boolean serverCoreConflict() {
		return TickEngineConfig.entityThrottle && TickEngineConfig.disableEntityThrottleWithServerCore
			&& ServerCoreCompat.activationRangeEnabled();
	}

	public static void onJoin(ServerPlayer player) {
		if (TickEngineConfig.noticesEnabled && isAdmin(player)) {
			send(player, problems());
		}
	}

	/** Called every server tick. */
	public static void tick(MinecraftServer server) {
		boolean conflict = serverCoreConflict();
		if (conflict != conflictLogged) {
			conflictLogged = conflict;
			if (conflict) {
				TickEngine.LOGGER.warn("Entity throttling paused: ServerCore's entity activation range is enabled. "
					+ "Use only one of the two, or set compatibility.disableEntityThrottleWithServerCore to false.");
			} else {
				TickEngine.LOGGER.info("Entity throttling resumed: no conflict with ServerCore anymore.");
			}
		}

		if (--ticksUntilRepeat > 0) {
			return;
		}
		ticksUntilRepeat = TickEngineConfig.noticeIntervalMinutes * 60L * 20L;
		if (!TickEngineConfig.noticesEnabled) {
			return;
		}
		List<Component> problems = problems();
		if (problems.isEmpty()) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (isAdmin(player)) {
				send(player, problems);
			}
		}
	}

	private static boolean isAdmin(ServerPlayer player) {
		return MinecraftCompat.check(player, PERMISSION, TickEngineConfig.commandOpLevel);
	}

	private static void send(ServerPlayer player, List<Component> problems) {
		// The link runs a config command: players with only tickengine.notify could not use it.
		boolean canHide = MinecraftCompat.check(player, CONFIG_PERMISSION, TickEngineConfig.commandOpLevel);
		for (Component problem : problems) {
			MutableComponent line = Component.literal("[TickEngine] ").withStyle(ChatFormatting.DARK_AQUA).append(problem);
			if (canHide) {
				line.append(Component.literal(" [hide notices]").withStyle(style -> style
					.withColor(ChatFormatting.GRAY)
					.withClickEvent(MinecraftCompat.suggestCommand("/tickengine config set notices.enabled false"))
					.withHoverEvent(MinecraftCompat.showText(Component.literal("Turn these notices off")))));
			}
			player.sendSystemMessage(line);
		}
	}

	public static void reset() {
		ticksUntilRepeat = 0;
		conflictLogged = false;
	}
}
