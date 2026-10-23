package com.studioderiva.tickengine.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.studioderiva.tickengine.TickEngine;
import com.studioderiva.tickengine.compat.ServerCoreCompat;
import com.studioderiva.tickengine.config.ThrottleRules;
import com.studioderiva.tickengine.config.TickEngineConfig;
import com.studioderiva.tickengine.notice.AdminNotices;
import com.studioderiva.tickengine.profiler.ProfileSnapshot;
import com.studioderiva.tickengine.profiler.TickProfiler;
import com.studioderiva.tickengine.report.HtmlReport;
import com.studioderiva.tickengine.throttle.BlockEntityThrottle;
import com.studioderiva.tickengine.throttle.EntityThrottle;
import com.studioderiva.tickengine.tuning.AutoTuner;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.Locale;
import java.util.function.Predicate;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.PermissionLevel;

/**
 * {@code /tickengine} command tree.
 *
 * <pre>
 * /tickengine status
 * /tickengine top [mods|blockentities|entities]
 * /tickengine profile start [seconds] | stop | report
 * /tickengine config list | get &lt;key&gt; | set &lt;key&gt; &lt;value&gt; | reload
 * /tickengine rules blockentities list | set &lt;interval&gt; &lt;target&gt; | set-in &lt;dimension&gt; &lt;interval&gt; &lt;target&gt;
 *                                  | remove &lt;target&gt; | remove-in &lt;dimension&gt; &lt;target&gt;
 * /tickengine rules entities list | set &lt;ai&gt; &lt;tick&gt; &lt;target&gt; | set-in &lt;dimension&gt; &lt;ai&gt; &lt;tick&gt; &lt;target&gt;
 *                            | remove &lt;target&gt; | remove-in &lt;dimension&gt; &lt;target&gt;
 * /tickengine tuning status | pause | resume | level &lt;n&gt;
 * /tickengine spread [enable | confirm &lt;phrase&gt; | disable]
 * /tickengine spread exclude list | add &lt;target&gt; | remove &lt;target&gt;
 * </pre>
 *
 * <p>Rule targets go last as greedy text because ids contain ':' which single-word
 * arguments do not accept.
 */
public final class TickEngineCommand {
	private static final int DEFAULT_SESSION_SECONDS = 60;

	private TickEngineCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("tickengine")
			.requires(permission("status").or(permission("top")).or(permission("profile")).or(permission("config")))
			.executes(TickEngineCommand::root)
			.then(Commands.literal("status")
				.requires(permission("status"))
				.executes(TickEngineCommand::status))
			.then(Commands.literal("top")
				.requires(permission("top"))
				.executes(context -> top(context, View.ALL))
				.then(Commands.literal("mods").executes(context -> top(context, View.MODS)))
				.then(Commands.literal("blockentities").executes(context -> top(context, View.BLOCK_ENTITIES)))
				.then(Commands.literal("entities").executes(context -> top(context, View.ENTITIES))))
			.then(Commands.literal("profile")
				.requires(permission("profile"))
				.then(Commands.literal("start")
					.executes(context -> startSession(context, DEFAULT_SESSION_SECONDS))
					.then(Commands.argument("seconds", IntegerArgumentType.integer(5))
						.executes(context -> startSession(context, IntegerArgumentType.getInteger(context, "seconds")))))
				.then(Commands.literal("stop").executes(TickEngineCommand::stopSession))
				.then(Commands.literal("report").executes(TickEngineCommand::lightReport)))
			.then(Commands.literal("config")
				.requires(permission("config"))
				.then(Commands.literal("list").executes(TickEngineCommand::configList))
				.then(Commands.literal("reload").executes(TickEngineCommand::configReload))
				.then(Commands.literal("get")
					.then(Commands.argument("key", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(TickEngineConfig.options().keySet(), builder))
						.executes(TickEngineCommand::configGet)))
				.then(Commands.literal("set")
					.then(Commands.argument("key", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(TickEngineConfig.options().keySet(), builder))
						.then(Commands.argument("value", StringArgumentType.greedyString())
							.executes(TickEngineCommand::configSet)))))
			.then(Commands.literal("rules")
				.requires(permission("config"))
				.then(blockEntityRules())
				.then(entityRules()))
			.then(Commands.literal("tuning")
				.requires(permission("config"))
				.then(Commands.literal("status").executes(TickEngineCommand::tuningStatus))
				.then(Commands.literal("pause").executes(TickEngineCommand::tuningPause))
				.then(Commands.literal("resume").executes(TickEngineCommand::tuningResume))
				.then(Commands.literal("level")
					.then(Commands.argument("level", IntegerArgumentType.integer(0))
						.executes(TickEngineCommand::tuningLevel))))
			.then(Commands.literal("spread")
				.requires(permission("config"))
				.executes(TickEngineCommand::spreadStatus)
				.then(Commands.literal("enable").executes(TickEngineCommand::spreadEnable))
				.then(Commands.literal("confirm")
					.then(Commands.argument("phrase", StringArgumentType.greedyString())
						.executes(TickEngineCommand::spreadConfirm)))
				.then(Commands.literal("disable").executes(TickEngineCommand::spreadDisable))
				.then(Commands.literal("exclude")
					.then(Commands.literal("list").executes(TickEngineCommand::spreadExcludeList))
					.then(Commands.literal("add")
						.then(targetArgument(BuiltInRegistries.ENTITY_TYPE.keySet(), false)
							.executes(TickEngineCommand::spreadExcludeAdd)))
					.then(Commands.literal("remove")
						.then(Commands.argument("target", StringArgumentType.greedyString())
							.suggests((context, builder) -> SharedSuggestionProvider.suggest(
								TickEngineConfig.SPREAD_EXCLUDE.targets(), builder))
							.executes(TickEngineCommand::spreadExcludeRemove))))));
	}

	/** Permission node {@code tickengine.<name>}; falls back to the configured operator level. */
	private static Predicate<CommandSourceStack> permission(String name) {
		String node = TickEngine.MOD_ID + "." + name;
		return source -> Permissions.check(source, node, PermissionLevel.byId(TickEngineConfig.commandOpLevel));
	}

	// --- status ---

	/** Bare /tickengine: status for those allowed to see it, otherwise the subcommands they can use. */
	private static int root(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (permission("status").test(source)) {
			return status(context);
		}
		String available = Stream.of("top", "profile", "config")
			.filter(name -> permission(name).test(source))
			.map(name -> "/tickengine " + name)
			.reduce((a, b) -> a + ", " + b)
			.orElse("none");
		source.sendSuccess(() -> header("TickEngine")
			.append(Component.literal(" Available: " + available).withStyle(ChatFormatting.GRAY)), false);
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		MinecraftServer server = source.getServer();
		double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
		double tps = Math.min(20.0, 1000.0 / Math.max(mspt, 0.001));
		source.sendSuccess(() -> header("TickEngine status"), false);
		source.sendSuccess(() -> line("MSPT", format(mspt) + " ms", msptColor(mspt))
			.append(Component.literal("  TPS ").withStyle(ChatFormatting.GRAY))
			.append(Component.literal(format(tps)).withStyle(msptColor(mspt))), false);
		source.sendSuccess(() -> line("Light profiler", TickEngineConfig.profilerEnabled
			? "on, 1 tick every " + TickEngineConfig.sampleEveryTicks + ", window " + TickEngineConfig.windowSeconds + " s"
			: "off", TickEngineConfig.profilerEnabled ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
		source.sendSuccess(() -> line("Detailed session", TickProfiler.isSessionRunning()
			? "running, " + TickProfiler.sessionSecondsLeft() + " s left"
			: "not running", TickProfiler.isSessionRunning() ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
		if (TickEngineConfig.blockEntityThrottle) {
			long ticked = BlockEntityThrottle.tickedLastSecond();
			long skipped = BlockEntityThrottle.skippedLastSecond();
			double percent = ticked + skipped == 0 ? 0 : skipped * 100.0 / (ticked + skipped);
			source.sendSuccess(() -> line("Block entity throttle", "on, skipped " + skipped + " of " + (ticked + skipped)
				+ " ticks in the last second (" + format(percent) + "%)", ChatFormatting.GREEN), false);
		} else {
			source.sendSuccess(() -> line("Block entity throttle", "off", ChatFormatting.YELLOW), false);
		}
		if (EntityThrottle.isActive()) {
			long full = EntityThrottle.fullLastSecond();
			long aiSkipped = EntityThrottle.aiSkippedLastSecond();
			long minimal = EntityThrottle.minimalLastSecond();
			source.sendSuccess(() -> line("Entity throttle", "on, last second: " + full + " full ticks, " + aiSkipped
				+ " without AI, " + minimal + " minimal", ChatFormatting.GREEN), false);
		} else {
			source.sendSuccess(() -> line("Entity throttle", TickEngineConfig.entityThrottle
				? "paused (ServerCore activation range)" : "off", ChatFormatting.YELLOW), false);
		}
		source.sendSuccess(() -> line("Auto-tuning", tuningSummary(server),
			TickEngineConfig.autoTuning ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
		if (ServerCoreCompat.installed()) {
			source.sendSuccess(() -> line("ServerCore", "installed, activation range "
				+ onOff(ServerCoreCompat.activationRangeEnabled()) + ", dynamic settings "
				+ onOff(ServerCoreCompat.dynamicSettingsEnabled()), ChatFormatting.WHITE), false);
		}
		AdminNotices.problems().forEach(problem -> source.sendSuccess(() -> Component.literal(" ").append(problem), false));
		return 1;
	}

	private static String onOff(boolean value) {
		return value ? (ServerCoreCompat.assumed() ? "assumed on" : "on") : "off";
	}

	/** One-line auto-tuning state, also used by the HTML report. */
	public static String tuningSummary(MinecraftServer server) {
		if (!TickEngineConfig.autoTuning) {
			return "off";
		}
		return "level " + AutoTuner.level() + "/" + AutoTuner.maxLevel(server)
			+ (AutoTuner.isPaused() ? " (paused)" : "")
			+ (AutoTuner.lastAverageMspt() > 0 ? ", last average " + format(AutoTuner.lastAverageMspt()) + " ms" : "");
	}

	// --- tuning ---

	private static int tuningStatus(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		MinecraftServer server = source.getServer();
		AutoTuner.Values values = AutoTuner.values();
		source.sendSuccess(() -> header("Auto-tuning"), false);
		source.sendSuccess(() -> line("State", tuningSummary(server), ChatFormatting.WHITE), false);
		source.sendSuccess(() -> line("Thresholds", "tighten above " + TickEngineConfig.raiseAboveMspt + " ms, relax below "
			+ TickEngineConfig.lowerBelowMspt + " ms for " + AutoTuner.relaxWaitSeconds() + " s, every "
			+ TickEngineConfig.decisionSeconds + " s", ChatFormatting.WHITE), false);
		source.sendSuccess(() -> line("Block entities", "interval " + values.blockEntityInterval() + ", radius "
			+ values.blockEntityRadius() + " chunks", ChatFormatting.WHITE), false);
		source.sendSuccess(() -> line("Entities", "AI every " + values.entityAiInterval() + ", far tick every "
			+ values.entityTickInterval() + ", full radius " + values.entityFullRadius() + ", AI radius "
			+ values.entityAiRadius() + " chunks", ChatFormatting.WHITE), false);
		source.sendSuccess(() -> line("Force-loaded chunks", "never slower than every " + values.blockEntityForcedMax()
			+ " ticks for block entities, " + values.entityForcedMax() + " for entities", ChatFormatting.WHITE), false);
		source.sendSuccess(() -> line("Distances", "simulation " + server.getPlayerList().getSimulationDistance()
			+ ", view " + server.getPlayerList().getViewDistance()
			+ (ServerCoreCompat.dynamicSettingsEnabled() ? " (managed by ServerCore)" : ""), ChatFormatting.WHITE), false);
		return 1;
	}

	private static int tuningPause(CommandContext<CommandSourceStack> context) {
		AutoTuner.pause();
		context.getSource().sendSuccess(() -> Component.literal("Auto-tuning paused at level " + AutoTuner.level()
			+ ". Use '/tickengine tuning resume' to continue.").withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	private static int tuningResume(CommandContext<CommandSourceStack> context) {
		AutoTuner.resume();
		context.getSource().sendSuccess(() -> Component.literal("Auto-tuning resumed.").withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	private static int tuningLevel(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (!TickEngineConfig.autoTuning) {
			source.sendFailure(Component.literal("Auto-tuning is off. Enable it with '/tickengine config set autoTuning.enabled true'."));
			return 0;
		}
		int requested = IntegerArgumentType.getInteger(context, "level");
		int set = AutoTuner.forceLevel(source.getServer(), requested);
		source.sendSuccess(() -> Component.literal("Auto-tuning set to level " + set
			+ (set != requested ? " (highest available)" : "") + " and paused. Use '/tickengine tuning resume' to let it "
			+ "adjust again.").withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	// --- spread ---

	/** What has to be typed after '/tickengine spread confirm' to turn tick spreading on. */
	static final String SPREAD_PHRASE = "I understand the risks";
	private static final long SPREAD_CONFIRM_MILLIS = 60_000;
	/** Who asked to turn spreading on, and until when they may confirm. */
	private static final Map<String, Long> pendingSpreadConfirmations = new HashMap<>();

	private static int spreadStatus(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		source.sendSuccess(() -> header("Tick spreading (experimental)"), false);
		source.sendSuccess(() -> line("State", TickEngineConfig.spreadEntityTicks ? "on" : "off",
			TickEngineConfig.spreadEntityTicks ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
		spreadExplanation(source);
		Set<String> excluded = TickEngineConfig.SPREAD_EXCLUDE.targets();
		source.sendSuccess(() -> line("Excluded", (excluded.isEmpty() ? "nothing" : String.join(", ", excluded))
			+ "; types that are never throttled are never moved. Change with '/tickengine spread exclude'.",
			ChatFormatting.WHITE), false);
		source.sendSuccess(() -> Component.literal(TickEngineConfig.spreadEntityTicks
			? " Turn it off with '/tickengine spread disable'."
			: " Turn it on with '/tickengine spread enable'.").withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	private static void spreadExplanation(CommandSourceStack source) {
		source.sendSuccess(() -> line("What it does", "moves each entity's tick counter forward once, by 0-19 ticks, "
			+ EntityThrottle.SPREAD_AT_TICK / 20 + " s after it is loaded (and right away for entities already loaded).",
			ChatFormatting.WHITE), false);
		source.sendSuccess(() -> line("Gain", "throttled entities loaded together (after a restart, in spawn or "
			+ "force-loaded chunks) stop running their full tick on the same tick. The work is spread evenly instead of "
			+ "one heavy tick every few: fewer tick spikes. In our test (1100 mobs loaded together after a restart) the "
			+ "slowest 5% of ticks went from 35 to 11 ms and the slowest tick from 55 to 21 ms; the average MSPT did not "
			+ "change.", ChatFormatting.GREEN), false);
		source.sendSuccess(() -> line("Risk", "logic that waits for one exact tick counter value in that short range can "
			+ "be skipped once per load, and timers based on the counter (like 'hurt recently') can end up to 19 ticks "
			+ "early once. Vanilla does not wait for exact values there; some mods might. If a mob or machine from a mod "
			+ "misbehaves after turning this on, turn it off and tell us which one.", ChatFormatting.YELLOW), false);
	}

	private static int spreadEnable(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (TickEngineConfig.spreadEntityTicks) {
			source.sendSuccess(() -> Component.literal("Tick spreading is already on.").withStyle(ChatFormatting.GRAY), false);
			return 1;
		}
		pendingSpreadConfirmations.put(source.getTextName(), System.currentTimeMillis() + SPREAD_CONFIRM_MILLIS);
		source.sendSuccess(() -> header("Tick spreading: read before turning it on"), false);
		spreadExplanation(source);
		String command = "/tickengine spread confirm " + SPREAD_PHRASE;
		source.sendSuccess(() -> Component.literal(" To turn it on, within " + SPREAD_CONFIRM_MILLIS / 1000 + " s type: ")
			.withStyle(ChatFormatting.GOLD)
			.append(Component.literal(command).withStyle(style -> style
				.withColor(ChatFormatting.WHITE)
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.SuggestCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to put it in the chat box"))))),
			false);
		return 1;
	}

	private static int spreadConfirm(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		Long deadline = pendingSpreadConfirmations.get(source.getTextName());
		if (deadline == null || System.currentTimeMillis() > deadline) {
			pendingSpreadConfirmations.remove(source.getTextName());
			source.sendFailure(Component.literal("Nothing to confirm. Run '/tickengine spread enable' first and read the warning."));
			return 0;
		}
		if (!StringArgumentType.getString(context, "phrase").trim().equalsIgnoreCase(SPREAD_PHRASE)) {
			source.sendFailure(Component.literal("The phrase does not match. Type exactly: /tickengine spread confirm "
				+ SPREAD_PHRASE));
			return 0;
		}
		pendingSpreadConfirmations.remove(source.getTextName());
		TickEngineConfig.spreadEntityTicks = true;
		TickEngineConfig.changed();
		int moved = EntityThrottle.spreadLoadedEntities(source.getServer());
		TickEngine.LOGGER.info("Tick spreading turned on by {}; {} loaded entities moved.", source.getTextName(), moved);
		source.sendSuccess(() -> Component.literal("Tick spreading is on and saved to the config file; " + moved
			+ " loaded entities were moved. Turn it off any time with '/tickengine spread disable'.")
			.withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	private static int spreadDisable(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		pendingSpreadConfirmations.remove(source.getTextName());
		if (!TickEngineConfig.spreadEntityTicks) {
			source.sendSuccess(() -> Component.literal("Tick spreading is already off.").withStyle(ChatFormatting.GRAY), false);
			return 1;
		}
		TickEngineConfig.spreadEntityTicks = false;
		TickEngineConfig.changed();
		source.sendSuccess(() -> Component.literal("Tick spreading is off. Counters already moved stay as they are until "
			+ "those entities are loaded again; that is harmless.").withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	private static int spreadExcludeList(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		source.sendSuccess(() -> header("Excluded from tick spreading"), false);
		Set<String> excluded = TickEngineConfig.SPREAD_EXCLUDE.targets();
		if (excluded.isEmpty()) {
			source.sendSuccess(() -> Component.literal(" nothing listed").withStyle(ChatFormatting.GRAY), false);
		}
		excluded.forEach(target -> source.sendSuccess(() -> Component.literal(" " + target).withStyle(ChatFormatting.WHITE), false));
		source.sendSuccess(() -> Component.literal(" Also never moved: types that are never throttled (a rule with ai 1 and "
			+ "tick 1, bosses and projectiles while they are excluded from throttling).").withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	private static int spreadExcludeAdd(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String target = StringArgumentType.getString(context, "target").trim();
		if (TickEngineConfig.SPREAD_EXCLUDE.targets().contains(target)) {
			source.sendSuccess(() -> Component.literal(target + " is already excluded from tick spreading.")
				.withStyle(ChatFormatting.GRAY), false);
			return 1;
		}
		String error = TickEngineConfig.SPREAD_EXCLUDE.add(target);
		if (error != null) {
			source.sendFailure(Component.literal(error));
			return 0;
		}
		TickEngineConfig.changed();
		source.sendSuccess(() -> Component.literal("Excluded from tick spreading: " + target + ". Entities already moved "
			+ "keep their counter until they are loaded again; that is harmless.").withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	private static int spreadExcludeRemove(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String target = StringArgumentType.getString(context, "target").trim();
		if (!TickEngineConfig.SPREAD_EXCLUDE.remove(target)) {
			source.sendFailure(Component.literal("'" + target + "' is not excluded from tick spreading."));
			return 0;
		}
		TickEngineConfig.changed();
		source.sendSuccess(() -> Component.literal("No longer excluded from tick spreading: " + target
			+ ". Its entities are spread from their next load.").withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	// --- top ---

	private enum View { ALL, MODS, BLOCK_ENTITIES, ENTITIES }

	private static int top(CommandContext<CommandSourceStack> context, View view) {
		CommandSourceStack source = context.getSource();
		ProfileSnapshot snapshot = TickProfiler.lightSnapshot();
		if (snapshot == null) {
			source.sendFailure(Component.literal(TickEngineConfig.profilerEnabled
				? "No data yet. The light profiler needs a few seconds after startup."
				: "The light profiler is off. Enable it with '/tickengine config set profiler.enabled true'"
					+ " or run '/tickengine profile start'."));
			return 0;
		}
		printSnapshot(source, snapshot, view);
		return 1;
	}

	private static void printSnapshot(CommandSourceStack source, ProfileSnapshot snapshot, View view) {
		source.sendSuccess(() -> header(snapshot.label)
			.append(Component.literal(" (" + snapshot.sampledTicks + " ticks, avg " + format(snapshot.averageTickMs())
				+ " ms)").withStyle(ChatFormatting.GRAY)), false);
		if (view == View.ALL || view == View.MODS) {
			printMods(source, snapshot);
		}
		if (view == View.ALL || view == View.BLOCK_ENTITIES) {
			printEntries(source, snapshot, ProfileSnapshot.Category.BLOCK_ENTITY, "Block entities");
		}
		if (view == View.ALL || view == View.ENTITIES) {
			printEntries(source, snapshot, ProfileSnapshot.Category.ENTITY, "Entities");
		}
	}

	private static void printMods(CommandSourceStack source, ProfileSnapshot snapshot) {
		List<ProfileSnapshot.ModEntry> mods = snapshot.mods();
		source.sendSuccess(() -> Component.literal("Mods").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), false);
		if (mods.isEmpty()) {
			source.sendSuccess(() -> Component.literal("  no data").withStyle(ChatFormatting.GRAY), false);
		}
		for (int i = 0; i < Math.min(TickEngineConfig.chatTopEntries, mods.size()); i++) {
			ProfileSnapshot.ModEntry mod = mods.get(i);
			int rank = i + 1;
			source.sendSuccess(() -> Component.literal(" " + rank + ". ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(mod.displayName()).withStyle(ChatFormatting.WHITE))
				.append(timing(snapshot, mod.totalNanos())), false);
		}
	}

	private static void printEntries(CommandSourceStack source, ProfileSnapshot snapshot,
		ProfileSnapshot.Category category, String title) {
		List<ProfileSnapshot.Entry> entries = snapshot.entries(category);
		source.sendSuccess(() -> Component.literal(title).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), false);
		if (entries.isEmpty()) {
			source.sendSuccess(() -> Component.literal("  no data").withStyle(ChatFormatting.GRAY), false);
		}
		for (int i = 0; i < Math.min(TickEngineConfig.chatTopEntries, entries.size()); i++) {
			ProfileSnapshot.Entry entry = entries.get(i);
			int rank = i + 1;
			MutableComponent name = Component.literal(entry.id()).withStyle(ChatFormatting.WHITE);
			if (entry.worstPos() != null) {
				String dimension = entry.worstDimension().identifier().toString();
				String coordinates = entry.worstPos().getX() + " " + entry.worstPos().getY() + " " + entry.worstPos().getZ();
				// Land on top of a block entity instead of inside it, where a player would suffocate.
				BlockPos start = category == ProfileSnapshot.Category.BLOCK_ENTITY ? entry.worstPos().above() : entry.worstPos();
				int landingY = landingY(source.getServer().getLevel(entry.worstDimension()), start);
				String landing = entry.worstPos().getX() + " " + landingY + " " + entry.worstPos().getZ();
				name = name.withStyle(style -> style
					.withClickEvent(new ClickEvent.RunCommand("/execute in " + dimension + " run tp @s " + landing))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal(
						"Slowest instance: " + format(entry.worstNanos() / 1_000_000.0) + " ms in one tick\n"
							+ dimension + " " + coordinates + "\nClick to teleport")))
					.withUnderlined(true));
			}
			MutableComponent finalName = name;
			double perTick = entry.calls() / (double) snapshot.sampledTicks;
			source.sendSuccess(() -> Component.literal(" " + rank + ". ").withStyle(ChatFormatting.GRAY)
				.append(finalName)
				.append(Component.literal(" x" + format(perTick)).withStyle(ChatFormatting.DARK_GRAY))
				.append(timing(snapshot, entry.totalNanos())), false);
		}
	}

	/**
	 * First height at or above {@code start} with two free blocks for the player, checked when the
	 * list is printed. Falls back to {@code start} when nothing free is found nearby.
	 */
	private static int landingY(ServerLevel level, BlockPos start) {
		if (level == null) {
			return start.getY();
		}
		BlockPos.MutableBlockPos pos = start.mutable();
		int top = Math.min(level.getMaxY(), start.getY() + 64);
		for (int y = start.getY(); y < top; y++) {
			if (isFree(level, pos.setY(y)) && isFree(level, pos.setY(y + 1))) {
				return y;
			}
		}
		return start.getY();
	}

	private static boolean isFree(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	private static MutableComponent timing(ProfileSnapshot snapshot, long totalNanos) {
		double percent = snapshot.percentOfTick(totalNanos);
		return Component.literal("  " + format(snapshot.msPerTick(totalNanos)) + " ms/tick")
			.withStyle(percent >= 20 ? ChatFormatting.RED : percent >= 5 ? ChatFormatting.YELLOW : ChatFormatting.GREEN)
			.append(Component.literal(" (" + format(percent) + "%)").withStyle(ChatFormatting.GRAY));
	}

	// --- profile ---

	private static int startSession(CommandContext<CommandSourceStack> context, int seconds) {
		CommandSourceStack source = context.getSource();
		if (seconds > TickEngineConfig.maxSessionSeconds) {
			source.sendFailure(Component.literal("Maximum session length is " + TickEngineConfig.maxSessionSeconds
				+ " s (config: profiler.maxSessionSeconds)."));
			return 0;
		}
		MinecraftServer server = source.getServer();
		boolean started = TickProfiler.startSession(seconds, result -> finishSession(server, source, result));
		if (!started) {
			source.sendFailure(Component.literal("A session is already running ("
				+ TickProfiler.sessionSecondsLeft() + " s left). Use '/tickengine profile stop'."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Detailed profiling started for " + seconds
			+ " s. Every tick is measured; results and an HTML report follow when it ends.")
			.withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	private static int stopSession(CommandContext<CommandSourceStack> context) {
		if (!TickProfiler.stopSession()) {
			context.getSource().sendFailure(Component.literal("No detailed session is running."));
			return 0;
		}
		return 1;
	}

	private static void finishSession(MinecraftServer server, CommandSourceStack source, ProfileSnapshot result) {
		if (result.isEmpty()) {
			source.sendFailure(Component.literal("The session ended before any tick was measured."));
			return;
		}
		printSnapshot(source, result, View.ALL);
		writeReport(server, source, result);
	}

	private static int lightReport(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ProfileSnapshot snapshot = TickProfiler.lightSnapshot();
		if (snapshot == null) {
			source.sendFailure(Component.literal("No light profiler data yet."));
			return 0;
		}
		writeReport(source.getServer(), source, snapshot);
		return 1;
	}

	private static void writeReport(MinecraftServer server, CommandSourceStack source, ProfileSnapshot snapshot) {
		HtmlReport.writeAsync(server, snapshot).whenComplete((file, error) -> server.execute(() -> {
			if (error != null) {
				TickEngine.LOGGER.error("Could not write HTML report", error);
				source.sendFailure(Component.literal("Could not write the HTML report, see the server log."));
			} else {
				String path = server.getServerDirectory().toAbsolutePath().relativize(file.toAbsolutePath()).toString();
				source.sendSuccess(() -> Component.literal("HTML report saved: ").withStyle(ChatFormatting.GREEN)
					.append(Component.literal(path).withStyle(ChatFormatting.WHITE)), false);
			}
		}));
	}

	// --- config ---

	private static int configList(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		source.sendSuccess(() -> header("TickEngine config"), false);
		TickEngineConfig.options().forEach((key, option) ->
			source.sendSuccess(() -> line(key, option.format(), ChatFormatting.WHITE)
				.withStyle(style -> style
					.withHoverEvent(new HoverEvent.ShowText(Component.literal(option.comment)))
					.withClickEvent(new ClickEvent.SuggestCommand("/tickengine config set " + key + " "))), false));
		return 1;
	}

	private static int configGet(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		TickEngineConfig.Option<?> option = option(source, StringArgumentType.getString(context, "key"));
		if (option == null) {
			return 0;
		}
		source.sendSuccess(() -> line(option.key, option.format(), ChatFormatting.WHITE), false);
		source.sendSuccess(() -> Component.literal(option.comment).withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	private static int configSet(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		TickEngineConfig.Option<?> option = option(source, StringArgumentType.getString(context, "key"));
		if (option == null) {
			return 0;
		}
		String previous = option.format();
		String error = option.set(StringArgumentType.getString(context, "value"));
		if (error != null) {
			source.sendFailure(Component.literal(option.key + ": " + error));
			return 0;
		}
		// Options with risks can be turned off here, but only turned on after their confirmation.
		if (option.confirmCommand() != null && !option.format().equals("false")) {
			option.set(previous);
			source.sendFailure(Component.literal(option.key + " has risks and can only be turned on with '"
				+ option.confirmCommand() + "', which explains them and asks for confirmation."));
			return 0;
		}
		String inconsistency = TickEngineConfig.checkConsistency();
		if (inconsistency != null) {
			option.set(previous);
			source.sendFailure(Component.literal(inconsistency));
			return 0;
		}
		TickEngineConfig.changed();
		source.sendSuccess(() -> Component.literal("Set ").withStyle(ChatFormatting.GREEN)
			.append(Component.literal(option.key).withStyle(ChatFormatting.WHITE))
			.append(Component.literal(" to ").withStyle(ChatFormatting.GREEN))
			.append(Component.literal(option.format()).withStyle(ChatFormatting.WHITE))
			.append(Component.literal(" and saved to the config file.").withStyle(ChatFormatting.GREEN)), true);
		return 1;
	}

	private static int configReload(CommandContext<CommandSourceStack> context) {
		boolean loaded = TickEngineConfig.load();
		ServerCoreCompat.refresh();
		if (!loaded) {
			context.getSource().sendFailure(Component.literal("The config file has a syntax error and was not loaded;"
				+ " the previous settings stay in use. See the server log for the position."));
			return 0;
		}
		context.getSource().sendSuccess(() -> Component.literal("TickEngine config reloaded.")
			.withStyle(ChatFormatting.GREEN), true);
		String inconsistency = TickEngineConfig.checkConsistency();
		if (inconsistency != null) {
			context.getSource().sendSuccess(() -> Component.literal(inconsistency + " Auto-tuning will not relax until"
				+ " this is fixed.").withStyle(ChatFormatting.YELLOW), false);
		}
		return 1;
	}

	private static TickEngineConfig.Option<?> option(CommandSourceStack source, String key) {
		TickEngineConfig.Option<?> option = TickEngineConfig.options().get(key);
		if (option == null) {
			source.sendFailure(Component.literal("Unknown option '" + key + "'. Use '/tickengine config list'."));
		}
		return option;
	}

	// --- rules ---

	private static LiteralArgumentBuilder<CommandSourceStack> blockEntityRules() {
		ThrottleRules<Integer> rules = TickEngineConfig.BLOCK_ENTITY_RULES;
		return Commands.literal("blockentities")
			.then(Commands.literal("list").executes(context -> rulesList(context, "Block entity rules", rules,
				"Default interval", Integer.toString(TickEngineConfig.defaultInterval), TickEngineConfig.dimensionRulesEnabled)))
			.then(Commands.literal("set")
				.then(intervalArgument("interval")
					.then(targetArgument(BuiltInRegistries.BLOCK_ENTITY_TYPE.keySet(), false)
						.executes(context -> rulesSet(context, rules, IntegerArgumentType.getInteger(context, "interval"),
							false, "blockEntities.dimensionRulesEnabled", TickEngineConfig.dimensionRulesEnabled)))))
			.then(Commands.literal("set-in")
				.then(Commands.argument("dimension", DimensionArgument.dimension())
					.then(intervalArgument("interval")
						.then(targetArgument(BuiltInRegistries.BLOCK_ENTITY_TYPE.keySet(), true)
							.executes(context -> rulesSet(context, rules, IntegerArgumentType.getInteger(context, "interval"),
								true, "blockEntities.dimensionRulesEnabled", TickEngineConfig.dimensionRulesEnabled))))))
			.then(removeRule(rules, false))
			.then(removeRule(rules, true));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> entityRules() {
		ThrottleRules<ThrottleRules.EntityIntervals> rules = TickEngineConfig.ENTITY_RULES;
		return Commands.literal("entities")
			.then(Commands.literal("list").executes(context -> rulesList(context, "Entity rules", rules,
				"Defaults", rules.type().describe(new ThrottleRules.EntityIntervals(TickEngineConfig.defaultAiInterval,
					TickEngineConfig.defaultTickInterval)), TickEngineConfig.entityDimensionRulesEnabled)))
			.then(Commands.literal("set")
				.then(intervalArgument("ai")
					.then(intervalArgument("tick")
						.then(targetArgument(BuiltInRegistries.ENTITY_TYPE.keySet(), false)
							.executes(context -> rulesSet(context, rules, entityIntervals(context),
								false, "entities.dimensionRulesEnabled", TickEngineConfig.entityDimensionRulesEnabled))))))
			.then(Commands.literal("set-in")
				.then(Commands.argument("dimension", DimensionArgument.dimension())
					.then(intervalArgument("ai")
						.then(intervalArgument("tick")
							.then(targetArgument(BuiltInRegistries.ENTITY_TYPE.keySet(), true)
								.executes(context -> rulesSet(context, rules, entityIntervals(context),
									true, "entities.dimensionRulesEnabled", TickEngineConfig.entityDimensionRulesEnabled)))))))
			.then(removeRule(rules, false))
			.then(removeRule(rules, true));
	}

	private static ThrottleRules.EntityIntervals entityIntervals(CommandContext<CommandSourceStack> context) {
		return new ThrottleRules.EntityIntervals(IntegerArgumentType.getInteger(context, "ai"),
			IntegerArgumentType.getInteger(context, "tick"));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, Integer> intervalArgument(String name) {
		return Commands.argument(name, IntegerArgumentType.integer(ThrottleRules.MIN_INTERVAL, ThrottleRules.MAX_INTERVAL));
	}

	/** Targets go last as greedy text because ids contain ':', which single-word arguments reject. */
	private static RequiredArgumentBuilder<CommandSourceStack, String> targetArgument(Set<Identifier> ids, boolean inDimension) {
		return Commands.argument("target", StringArgumentType.greedyString())
			.suggests((context, builder) -> SharedSuggestionProvider.suggest(
				Stream.concat(inDimension ? Stream.of(ThrottleRules.ALL) : Stream.empty(), targetSuggestions(ids)), builder));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> removeRule(ThrottleRules<?> rules, boolean inDimension) {
		RequiredArgumentBuilder<CommandSourceStack, String> target = Commands.argument("target", StringArgumentType.greedyString())
			.executes(context -> rulesRemove(context, rules, inDimension));
		if (!inDimension) {
			target = target.suggests((context, builder) -> SharedSuggestionProvider.suggest(rules.rules().keySet(), builder));
			return Commands.literal("remove").then(target);
		}
		return Commands.literal("remove-in").then(Commands.argument("dimension", DimensionArgument.dimension()).then(target));
	}

	private static <V> int rulesList(CommandContext<CommandSourceStack> context, String title, ThrottleRules<V> rules,
		String defaultLabel, String defaultValue, boolean dimensionRulesEnabled) {
		CommandSourceStack source = context.getSource();
		source.sendSuccess(() -> header(title), false);
		source.sendSuccess(() -> line(defaultLabel, defaultValue, ChatFormatting.WHITE), false);
		if (rules.rules().isEmpty()) {
			source.sendSuccess(() -> Component.literal(" no rules").withStyle(ChatFormatting.GRAY), false);
		}
		rules.rules().forEach((target, value) -> source.sendSuccess(() -> ruleLine(rules, target, value), false));
		for (Map.Entry<String, Map<String, V>> dimension : rules.dimensionRules().entrySet()) {
			source.sendSuccess(() -> Component.literal(" in " + dimension.getKey()
				+ (dimensionRulesEnabled ? "" : " (disabled in the config)")).withStyle(ChatFormatting.AQUA), false);
			dimension.getValue().forEach((target, value) ->
				source.sendSuccess(() -> Component.literal("  ").append(ruleLine(rules, target, value)), false));
		}
		return 1;
	}

	private static <V> MutableComponent ruleLine(ThrottleRules<V> rules, String target, V value) {
		return line(target, rules.type().describe(value), ChatFormatting.WHITE);
	}

	private static <V> int rulesSet(CommandContext<CommandSourceStack> context, ThrottleRules<V> rules, V value,
		boolean inDimension, String dimensionOption, boolean dimensionRulesEnabled) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		String target = StringArgumentType.getString(context, "target").trim();
		String dimension = inDimension ? DimensionArgument.getDimension(context, "dimension").dimension().identifier().toString() : null;
		String error = rules.set(target, value, dimension);
		if (error != null) {
			source.sendFailure(Component.literal(error));
			return 0;
		}
		TickEngineConfig.changed();
		source.sendSuccess(() -> Component.literal("Rule saved:").withStyle(ChatFormatting.GREEN)
			.append(ruleLine(rules, target, value))
			.append(Component.literal(dimension == null ? "" : " in " + dimension).withStyle(ChatFormatting.GRAY)), true);
		if (dimension != null && !dimensionRulesEnabled) {
			source.sendSuccess(() -> Component.literal("Dimension rules are disabled. Enable them with "
				+ "'/tickengine config set " + dimensionOption + " true'.").withStyle(ChatFormatting.YELLOW), false);
		}
		return 1;
	}

	private static int rulesRemove(CommandContext<CommandSourceStack> context, ThrottleRules<?> rules, boolean inDimension)
		throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		String target = StringArgumentType.getString(context, "target").trim();
		String dimension = inDimension ? DimensionArgument.getDimension(context, "dimension").dimension().identifier().toString() : null;
		if (!rules.remove(target, dimension)) {
			source.sendFailure(Component.literal("No rule for '" + target + "'" + (dimension == null ? "" : " in " + dimension) + "."));
			return 0;
		}
		TickEngineConfig.changed();
		source.sendSuccess(() -> Component.literal("Rule removed: " + target + (dimension == null ? "" : " in " + dimension))
			.withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	/** Every id, plus "namespace:*" for every mod that registers one. */
	private static Stream<String> targetSuggestions(Set<Identifier> ids) {
		return ids.stream()
			.flatMap(id -> Stream.of(id.toString(), id.getNamespace() + ":" + ThrottleRules.ALL))
			.distinct();
	}

	// --- formatting ---

	private static MutableComponent header(String title) {
		return Component.literal("[TickEngine] ").withStyle(ChatFormatting.DARK_AQUA)
			.append(Component.literal(title).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
	}

	private static MutableComponent line(String label, String value, ChatFormatting color) {
		return Component.literal(" " + label + ": ").withStyle(ChatFormatting.GRAY)
			.append(Component.literal(value).withStyle(color));
	}

	private static ChatFormatting msptColor(double mspt) {
		return mspt >= 50 ? ChatFormatting.RED : mspt >= 35 ? ChatFormatting.YELLOW : ChatFormatting.GREEN;
	}

	private static String format(double value) {
		return String.format(Locale.ROOT, value >= 100 ? "%.0f" : value >= 10 ? "%.1f" : "%.2f", value);
	}
}
