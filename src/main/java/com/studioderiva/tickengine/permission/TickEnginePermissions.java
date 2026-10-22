package com.studioderiva.tickengine.permission;

import com.studioderiva.tickengine.TickEngine;
import com.studioderiva.tickengine.config.TickEngineConfig;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;

/**
 * Permission nodes {@code tickengine.<name>} through Forge's PermissionAPI, so permission mods
 * (e.g. LuckPerms) can grant them. Without such a mod the default resolver applies: operators at
 * {@code permissions.commandOpLevel}. Non-player sources (console, command blocks) always use the
 * operator level.
 */
public final class TickEnginePermissions {
	public static final PermissionNode<Boolean> STATUS = node("status");
	public static final PermissionNode<Boolean> TOP = node("top");
	public static final PermissionNode<Boolean> PROFILE = node("profile");
	public static final PermissionNode<Boolean> CONFIG = node("config");
	public static final PermissionNode<Boolean> NOTIFY = node("notify");
	private static final List<PermissionNode<Boolean>> ALL = List.of(STATUS, TOP, PROFILE, CONFIG, NOTIFY);

	private TickEnginePermissions() {
	}

	private static PermissionNode<Boolean> node(String name) {
		return new PermissionNode<>(TickEngine.MOD_ID, name, PermissionTypes.BOOLEAN,
			(player, uuid, context) -> player != null && player.hasPermissions(TickEngineConfig.commandOpLevel));
	}

	public static PermissionNode<Boolean> byName(String name) {
		for (PermissionNode<Boolean> node : ALL) {
			if (node.getNodeName().equals(TickEngine.MOD_ID + "." + name)) {
				return node;
			}
		}
		throw new IllegalArgumentException("Unknown permission node " + name);
	}

	public static void onGatherNodes(PermissionGatherEvent.Nodes event) {
		event.addNodes(ALL.toArray(new PermissionNode<?>[0]));
	}

	public static boolean check(ServerPlayer player, PermissionNode<Boolean> node) {
		return PermissionAPI.getPermission(player, node);
	}

	public static boolean check(CommandSourceStack source, PermissionNode<Boolean> node) {
		if (source.getEntity() instanceof ServerPlayer player) {
			return check(player, node);
		}
		return source.hasPermission(TickEngineConfig.commandOpLevel);
	}
}
