package com.studioderiva.tickengine.compat;

import it.unimi.dsi.fastutil.longs.LongSet;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelHeightAccessor;
//? if >=1.21.11
import net.minecraft.server.permissions.PermissionLevel;

/**
 * Minecraft APIs that differ between the supported 1.21.x versions, switched per build by
 * Stonecutter comments ({@code //? if}). The rest of the mod calls these instead of the vanilla
 * methods, so version differences stay in this file (and in MobMixin, whose injection target
 * changes). Renames used all over the code (Identifier, ResourceKey.identifier(), Util) are
 * global replacements in build.gradle instead.
 */
public final class MinecraftCompat {
	private MinecraftCompat() {
	}

	/** Permission node check for a command source, falling back to an operator level. */
	public static boolean check(SharedSuggestionProvider source, String node, int opLevel) {
		//? if >=1.21.11 {
		return Permissions.check(source, node, PermissionLevel.byId(opLevel));
		//?} else {
		/*return Permissions.check(source, node, opLevel);
		*///?}
	}

	/** Permission node check for a player, falling back to an operator level. */
	public static boolean check(Entity entity, String node, int opLevel) {
		//? if >=1.21.11 {
		return Permissions.check(entity, node, PermissionLevel.byId(opLevel));
		//?} else {
		/*return Permissions.check(entity, node, opLevel);
		*///?}
	}

	public static ClickEvent suggestCommand(String command) {
		//? if >=1.21.5 {
		return new ClickEvent.SuggestCommand(command);
		//?} else {
		/*return new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command);
		*///?}
	}

	public static ClickEvent runCommand(String command) {
		//? if >=1.21.5 {
		return new ClickEvent.RunCommand(command);
		//?} else {
		/*return new ClickEvent(ClickEvent.Action.RUN_COMMAND, command);
		*///?}
	}

	public static ClickEvent openUrl(String url) {
		//? if >=1.21.5 {
		return new ClickEvent.OpenUrl(java.net.URI.create(url));
		//?} else {
		/*return new ClickEvent(ClickEvent.Action.OPEN_URL, url);
		*///?}
	}

	public static HoverEvent showText(Component text) {
		//? if >=1.21.5 {
		return new HoverEvent.ShowText(text);
		//?} else {
		/*return new HoverEvent(HoverEvent.Action.SHOW_TEXT, text);
		*///?}
	}

	/** Chunks kept loaded with /forceload, as packed chunk positions. */
	public static LongSet forceLoadedChunks(ServerLevel level) {
		//? if >=1.21.5 {
		return level.getForceLoadedChunks();
		//?} else {
		/*return level.getForcedChunks();
		*///?}
	}

	/** Highest Y a block can be placed at (inclusive). */
	public static int maxY(LevelHeightAccessor level) {
		//? if >=1.21.2 {
		return level.getMaxY();
		//?} else {
		/*return level.getMaxBuildHeight() - 1;
		*///?}
	}
}
