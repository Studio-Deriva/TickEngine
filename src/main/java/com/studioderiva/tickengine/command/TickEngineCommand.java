package com.studioderiva.tickengine.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.studioderiva.tickengine.TickEngine;
import com.studioderiva.tickengine.compat.MinecraftCompat;
import com.studioderiva.tickengine.compat.ServerCoreCompat;
import com.studioderiva.tickengine.config.ThrottleRules;
import com.studioderiva.tickengine.config.TickEngineConfig;
import com.studioderiva.tickengine.history.HistoryEntry;
import com.studioderiva.tickengine.history.HistoryStore;
import com.studioderiva.tickengine.limit.EntityLimit;
import com.studioderiva.tickengine.network.NetworkProfiler;
import com.studioderiva.tickengine.network.NetworkSnapshot;
import com.studioderiva.tickengine.network.NetworkSource;
import com.studioderiva.tickengine.notice.AdminNotices;
import com.studioderiva.tickengine.profiler.ChunkActivity;
import com.studioderiva.tickengine.profiler.ProfileSnapshot;
import com.studioderiva.tickengine.profiler.TickProfiler;
import com.studioderiva.tickengine.report.DiagnosticReport;
import com.studioderiva.tickengine.report.HistoryHtmlReport;
import com.studioderiva.tickengine.report.HtmlReport;
import com.studioderiva.tickengine.report.NetworkHtmlReport;
import com.studioderiva.tickengine.report.ReportWebServer;
import com.studioderiva.tickengine.stacking.EntityStacker;
import com.studioderiva.tickengine.throttle.BlockEntityThrottle;
import com.studioderiva.tickengine.throttle.EntityThrottle;
import com.studioderiva.tickengine.tuning.AutoTuner;
import com.studioderiva.tickengine.tuning.EmergencyMode;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * {@code /tickengine} command tree.
 *
 * <pre>
 * /tickengine status
 * /tickengine top [mods|blockentities|entities|blocks|chunks|worldgen]
 * /tickengine profile start [seconds] | stop | report
 * /tickengine history [hours] | report [hours] | peak &lt;time&gt;
 * /tickengine network [start [seconds] | stop | top [mods|sources|packets|chunks] | report]
 * /tickengine config list | get &lt;key&gt; | set &lt;key&gt; &lt;value&gt; | reload
 * /tickengine rules blockentities list | set &lt;interval&gt; &lt;target&gt; | set-in &lt;dimension&gt; &lt;interval&gt; &lt;target&gt;
 *                                  | remove &lt;target&gt; | remove-in &lt;dimension&gt; &lt;target&gt;
 * /tickengine rules entities list | set &lt;ai&gt; &lt;tick&gt; &lt;target&gt; | set-in &lt;dimension&gt; &lt;ai&gt; &lt;tick&gt; &lt;target&gt;
 *                            | remove &lt;target&gt; | remove-in &lt;dimension&gt; &lt;target&gt;
 * /tickengine tuning status | pause | resume | level &lt;n&gt;
 * /tickengine spread [enable | confirm &lt;phrase&gt; | disable]
 * /tickengine spread exclude list | add &lt;target&gt; | remove &lt;target&gt;
 * /tickengine emergency [enable | disable | start | stop]
 * /tickengine stacking [mode &lt;mode&gt; | confirm &lt;phrase&gt; | unstack]
 * /tickengine stacking exclude list | add &lt;target&gt; | remove &lt;target&gt;
 * /tickengine limit [enable | disable]
 * /tickengine limit rules list | set &lt;limit&gt; &lt;target&gt; | set-in &lt;dimension&gt; &lt;limit&gt; &lt;target&gt;
 *                       | remove &lt;target&gt; | remove-in &lt;dimension&gt; &lt;target&gt;
 * /tickengine limit exclude list | add &lt;target&gt; | remove &lt;target&gt;
 * /tickengine report [general | blockentity &lt;type&gt; | entity &lt;type&gt; | mod &lt;mod id&gt;]
 * </pre>
 *
 * <p>Rule targets go last as greedy text because ids contain ':' which single-word
 * arguments do not accept.
 */
public final class TickEngineCommand {
	private static final int DEFAULT_SESSION_SECONDS = 60;
	private static final int DEFAULT_HISTORY_HOURS = 24;
	/** How far after a peak a detailed session counts as its own. */
	private static final long HISTORY_SESSION_AFTER_MILLIS = 3 * 60_000L;
	private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

	private TickEngineCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("tickengine")
			.requires(permission("status").or(permission("top")).or(permission("profile")).or(permission("config"))
				.or(permission("report")))
			.executes(TickEngineCommand::root)
			.then(Commands.literal("status")
				.requires(permission("status"))
				.executes(TickEngineCommand::status))
			.then(Commands.literal("top")
				.requires(permission("top"))
				.executes(context -> top(context, View.ALL))
				.then(Commands.literal("mods").executes(context -> top(context, View.MODS)))
				.then(Commands.literal("blockentities").executes(context -> top(context, View.BLOCK_ENTITIES)))
				.then(Commands.literal("entities").executes(context -> top(context, View.ENTITIES)))
				.then(Commands.literal("blocks").executes(context -> top(context, View.BLOCKS)))
				.then(Commands.literal("chunks").executes(context -> top(context, View.CHUNKS)))
				.then(Commands.literal("worldgen").executes(context -> top(context, View.WORLDGEN))))
			.then(Commands.literal("history")
				.requires(permission("top"))
				.executes(context -> history(context, DEFAULT_HISTORY_HOURS))
				.then(hoursArgument().executes(context -> history(context, IntegerArgumentType.getInteger(context, "hours"))))
				.then(Commands.literal("report")
					.executes(context -> historyReport(context, DEFAULT_HISTORY_HOURS))
					.then(hoursArgument().executes(context -> historyReport(context, IntegerArgumentType.getInteger(context, "hours")))))
				.then(Commands.literal("peak")
					.then(Commands.argument("time", LongArgumentType.longArg(0))
						.executes(TickEngineCommand::historyPeak))))
			.then(Commands.literal("profile")
				.requires(permission("profile"))
				.then(Commands.literal("start")
					.executes(context -> startSession(context, DEFAULT_SESSION_SECONDS))
					.then(Commands.argument("seconds", IntegerArgumentType.integer(5))
						.executes(context -> startSession(context, IntegerArgumentType.getInteger(context, "seconds")))))
				.then(Commands.literal("stop").executes(TickEngineCommand::stopSession))
				.then(Commands.literal("report").executes(TickEngineCommand::lightReport)))
			.then(Commands.literal("network")
				.requires(permission("profile"))
				.executes(TickEngineCommand::networkStatus)
				.then(Commands.literal("start")
					.executes(context -> networkStart(context, DEFAULT_SESSION_SECONDS))
					.then(Commands.argument("seconds", IntegerArgumentType.integer(5))
						.executes(context -> networkStart(context, IntegerArgumentType.getInteger(context, "seconds")))))
				.then(Commands.literal("stop").executes(TickEngineCommand::networkStop))
				.then(Commands.literal("top")
					.executes(context -> networkTop(context, NetworkView.ALL))
					.then(Commands.literal("mods").executes(context -> networkTop(context, NetworkView.MODS)))
					.then(Commands.literal("sources").executes(context -> networkTop(context, NetworkView.SOURCES)))
					.then(Commands.literal("packets").executes(context -> networkTop(context, NetworkView.PACKETS)))
					.then(Commands.literal("chunks").executes(context -> networkTop(context, NetworkView.CHUNKS))))
				.then(Commands.literal("report").executes(TickEngineCommand::networkReport)))
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
							.executes(TickEngineCommand::spreadExcludeRemove)))))
			.then(Commands.literal("emergency")
				.requires(permission("config"))
				.executes(TickEngineCommand::emergencyStatus)
				.then(Commands.literal("enable").executes(context -> emergencySet(context, true)))
				.then(Commands.literal("disable").executes(context -> emergencySet(context, false)))
				.then(Commands.literal("start").executes(TickEngineCommand::emergencyStart))
				.then(Commands.literal("stop").executes(TickEngineCommand::emergencyStop)))
			.then(Commands.literal("stacking")
				.requires(permission("config"))
				.executes(TickEngineCommand::stackingStatus)
				.then(Commands.literal("mode")
					.then(Commands.argument("mode", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(Stream.of(TickEngineConfig.STACKING_OFF,
							TickEngineConfig.STACKING_EMERGENCY, TickEngineConfig.STACKING_AUTO_TUNING, TickEngineConfig.STACKING_ALWAYS),
							builder))
						.executes(TickEngineCommand::stackingMode)))
				.then(Commands.literal("confirm")
					.then(Commands.argument("phrase", StringArgumentType.greedyString())
						.executes(TickEngineCommand::stackingConfirm)))
				.then(Commands.literal("unstack").executes(TickEngineCommand::stackingUnstack))
				.then(Commands.literal("exclude")
					.then(Commands.literal("list").executes(TickEngineCommand::stackExcludeList))
					.then(Commands.literal("add")
						.then(targetArgument(BuiltInRegistries.ENTITY_TYPE.keySet(), false)
							.executes(TickEngineCommand::stackExcludeAdd)))
					.then(Commands.literal("remove")
						.then(Commands.argument("target", StringArgumentType.greedyString())
							.suggests((context, builder) -> SharedSuggestionProvider.suggest(
								TickEngineConfig.STACK_EXCLUDE.targets(), builder))
							.executes(TickEngineCommand::stackExcludeRemove)))))
			.then(Commands.literal("limit")
				.requires(permission("config"))
				.executes(TickEngineCommand::limitStatus)
				.then(Commands.literal("enable").executes(context -> limitSet(context, true)))
				.then(Commands.literal("disable").executes(context -> limitSet(context, false)))
				.then(limitRules())
				.then(Commands.literal("exclude")
					.then(Commands.literal("list").executes(TickEngineCommand::limitExcludeList))
					.then(Commands.literal("add")
						.then(targetArgument(BuiltInRegistries.ENTITY_TYPE.keySet(), false)
							.executes(TickEngineCommand::limitExcludeAdd)))
					.then(Commands.literal("remove")
						.then(Commands.argument("target", StringArgumentType.greedyString())
							.suggests((context, builder) -> SharedSuggestionProvider.suggest(
								TickEngineConfig.LIMIT_EXCLUDE.targets(), builder))
							.executes(TickEngineCommand::limitExcludeRemove)))))
			.then(Commands.literal("report")
				.requires(permission("report"))
				.executes(TickEngineCommand::reportMenu)
				.then(Commands.literal(DiagnosticReport.Kind.GENERAL.word)
					.executes(context -> report(context, DiagnosticReport.Kind.GENERAL, "")))
				.then(reportKind(DiagnosticReport.Kind.BLOCK_ENTITY))
				.then(reportKind(DiagnosticReport.Kind.ENTITY))
				.then(reportKind(DiagnosticReport.Kind.MOD))));
	}

	/** Permission node {@code tickengine.<name>}; falls back to the configured operator level. */
	private static Predicate<CommandSourceStack> permission(String name) {
		String node = TickEngine.MOD_ID + "." + name;
		return source -> MinecraftCompat.check(source, node, TickEngineConfig.commandOpLevel);
	}

	// --- status ---

	/** Bare /tickengine: status for those allowed to see it, otherwise the subcommands they can use. */
	private static int root(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (permission("status").test(source)) {
			return status(context);
		}
		String available = Stream.of("top", "profile", "config", "report")
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
		source.sendSuccess(() -> line("Emergency mode", emergencySummary(), EmergencyMode.isActive() ? ChatFormatting.RED
			: TickEngineConfig.emergencyEnabled ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
		if (!TickEngineConfig.stackingMode.equals(TickEngineConfig.STACKING_OFF) || EntityStacker.loadedStacks(server)[0] > 0) {
			source.sendSuccess(() -> line("Entity stacking", stackingSummary(server), EntityStacker.isActive()
				? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
		}
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
		long now = System.currentTimeMillis();
		// Requests nobody confirmed would otherwise stay in the map until the server stops.
		pendingSpreadConfirmations.values().removeIf(deadline -> deadline < now);
		pendingSpreadConfirmations.put(source.getTextName(), now + SPREAD_CONFIRM_MILLIS);
		source.sendSuccess(() -> header("Tick spreading: read before turning it on"), false);
		spreadExplanation(source);
		String command = "/tickengine spread confirm " + SPREAD_PHRASE;
		source.sendSuccess(() -> Component.literal(" To turn it on, within " + SPREAD_CONFIRM_MILLIS / 1000 + " s type: ")
			.withStyle(ChatFormatting.GOLD)
			.append(Component.literal(command).withStyle(style -> style
				.withColor(ChatFormatting.WHITE)
				.withUnderlined(true)
				.withClickEvent(MinecraftCompat.suggestCommand(command))
				.withHoverEvent(MinecraftCompat.showText(Component.literal("Click to put it in the chat box"))))),
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

	// --- emergency ---

	/** One-line emergency mode state. */
	public static String emergencySummary() {
		if (EmergencyMode.isActive()) {
			return "ACTIVE for " + EmergencyMode.activeSeconds() + " s (" + EmergencyMode.reason() + ")";
		}
		if (!TickEngineConfig.emergencyEnabled) {
			return "off";
		}
		long critical = EmergencyMode.criticalSeconds();
		return "ready, enters above " + TickEngineConfig.emergencyTriggerMspt + " ms for " + TickEngineConfig.emergencyTriggerSeconds
			+ " s" + (critical >= 0 ? " (critical for " + critical + " s now)" : "");
	}

	private static int emergencyStatus(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		source.sendSuccess(() -> header("Emergency mode"), false);
		source.sendSuccess(() -> line("State", emergencySummary(), EmergencyMode.isActive() ? ChatFormatting.RED
			: TickEngineConfig.emergencyEnabled ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
		int memory = EmergencyMode.oldGenerationPercent();
		source.sendSuccess(() -> line("Load", "last 20 ticks " + format(EmergencyMode.lastMspt()) + " ms, old generation "
			+ (memory < 0 ? "not reported by this JVM" : memory + "% full after the last collection"), ChatFormatting.WHITE), false);
		source.sendSuccess(() -> line("Enters", "average MSPT above " + TickEngineConfig.emergencyTriggerMspt + " ms"
			+ (TickEngineConfig.emergencyMemoryPercent > 0 ? " or memory at " + TickEngineConfig.emergencyMemoryPercent + "%" : "")
			+ " for " + TickEngineConfig.emergencyTriggerSeconds + " s", ChatFormatting.WHITE), false);
		long calm = EmergencyMode.calmSeconds();
		source.sendSuccess(() -> line("Leaves", "average MSPT below " + TickEngineConfig.emergencyExitMspt + " ms for "
			+ TickEngineConfig.emergencyExitSeconds + " s" + (EmergencyMode.isActive() && calm >= 0 ? " (low for " + calm + " s now)" : ""),
			ChatFormatting.WHITE), false);
		source.sendSuccess(() -> line("While active", "auto-tuning at its highest level" + (TickEngineConfig.autoTuning ? "" : " (off now)")
			+ ", far entities and block entities at most every " + TickEngineConfig.emergencyInterval + " ticks"
			+ (TickEngineConfig.emergencyPauseSpawning ? ", natural mob spawning paused" : "")
			+ (TickEngineConfig.stackingMode.equals(TickEngineConfig.STACKING_OFF) ? "" : ", entity stacking on")
			+ (TickEngineConfig.emergencySaveOnEnter ? ", world saved on entering" : ""), ChatFormatting.WHITE), false);
		source.sendSuccess(() -> Component.literal(TickEngineConfig.emergencyEnabled
			? " Turn it off with '/tickengine emergency disable'. '/tickengine emergency start' starts it now."
			: " Turn it on with '/tickengine emergency enable'. '/tickengine emergency start' starts it now.")
			.withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	private static int emergencySet(CommandContext<CommandSourceStack> context, boolean enabled) {
		TickEngineConfig.emergencyEnabled = enabled;
		TickEngineConfig.changed();
		context.getSource().sendSuccess(() -> Component.literal(enabled
			? "Emergency mode is on: it starts by itself when the average MSPT stays above " + TickEngineConfig.emergencyTriggerMspt
				+ " ms for " + TickEngineConfig.emergencyTriggerSeconds + " s."
			: "Emergency mode is off" + (EmergencyMode.isActive() && !EmergencyMode.isManual() ? " and ends within a second." : ".")
		).withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	private static int emergencyStart(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (EmergencyMode.isActive()) {
			source.sendFailure(Component.literal("Emergency mode is already active."));
			return 0;
		}
		EmergencyMode.enter(source.getServer(), "started by " + source.getTextName(), true);
		source.sendSuccess(() -> Component.literal("Emergency mode started. It ends by itself after the average MSPT stays below "
			+ TickEngineConfig.emergencyExitMspt + " ms for " + TickEngineConfig.emergencyExitSeconds
			+ " s, or with '/tickengine emergency stop'.").withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	private static int emergencyStop(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (!EmergencyMode.isActive()) {
			source.sendFailure(Component.literal("Emergency mode is not active."));
			return 0;
		}
		EmergencyMode.exit(source.getServer(), "stopped by " + source.getTextName());
		source.sendSuccess(() -> Component.literal("Emergency mode stopped. Auto-tuning is back at its level from before.")
			.withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	// --- stacking ---

	/** What has to be typed after '/tickengine stacking confirm' to turn stacking on. */
	static final String STACKING_PHRASE = "I understand the risks";
	/** Who asked for which stacking mode, and until when they may confirm. */
	private static final Map<String, Long> pendingStackingDeadlines = new HashMap<>();
	private static final Map<String, String> pendingStackingModes = new HashMap<>();

	private static String stackingSummary(MinecraftServer server) {
		int[] loaded = EntityStacker.loadedStacks(server);
		String when = switch (TickEngineConfig.stackingMode) {
			case TickEngineConfig.STACKING_ALWAYS -> "always";
			case TickEngineConfig.STACKING_AUTO_TUNING -> "at the last auto-tuning level and in emergencies";
			case TickEngineConfig.STACKING_EMERGENCY -> "only in emergencies";
			default -> "off";
		};
		return when + (EntityStacker.isActive() ? ", running" : ", not running now") + "; " + loaded[0]
			+ " loaded stacks holding " + loaded[1] + " mobs";
	}

	private static int stackingStatus(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		source.sendSuccess(() -> header("Entity stacking (experimental)"), false);
		source.sendSuccess(() -> line("State", stackingSummary(source.getServer()), EntityStacker.isActive()
			? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
		source.sendSuccess(() -> line("Stacks", "up to " + TickEngineConfig.stackMaxSize + " mobs, from cubes of "
			+ TickEngineConfig.stackAreaBlocks + " blocks", ChatFormatting.WHITE), false);
		Set<String> excluded = TickEngineConfig.STACK_EXCLUDE.targets();
		source.sendSuccess(() -> line("Excluded", (excluded.isEmpty() ? "nothing" : String.join(", ", excluded))
			+ "; change with '/tickengine stacking exclude'.", ChatFormatting.WHITE), false);
		stackingExplanation(source);
		source.sendSuccess(() -> Component.literal(" Modes: off, emergency, autoTuning, always. Change with "
			+ "'/tickengine stacking mode <mode>'.").withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	private static void stackingExplanation(CommandSourceStack source) {
		source.sendSuccess(() -> line("What it does", "mobs of the same kind with the same data (colour, sheared or not, "
			+ "equipment...) standing close together become one mob that counts for several, shown as \"Zombie x7\". "
			+ "Killing it kills one mob at a time with its normal loot and XP; using it (feeding, shearing, a name tag, a lead) "
			+ "separates the mob you clicked. Natural spawning counts every mob in a stack. Never stacked: named, tamed or "
			+ "leashed mobs, babies, animals in love, mobs in combat, riders and their mounts, bosses, and the excluded types.",
			ChatFormatting.WHITE), false);
		source.sendSuccess(() -> line("Gain", "one mob ticks instead of up to " + TickEngineConfig.stackMaxSize
			+ ": in farms and crowded places, most of the entity cost goes away.", ChatFormatting.GREEN), false);
		source.sendSuccess(() -> line("Risk", "a stack moves, thinks and despawns as one mob, so ten zombies become one "
			+ "zombie with ten lives; mobs that produce something over time (wool regrowth, for example) produce for one. "
			+ "Mods that keep their own data about single mobs may not expect the copies made when a stack is killed or used. "
			+ "If TickEngine is removed, each stack stays one single mob: run '/tickengine stacking unstack' first.",
			ChatFormatting.YELLOW), false);
	}

	private static int stackingMode(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		TickEngineConfig.Option<?> option = TickEngineConfig.options().get("stacking.mode");
		String previous = option.format();
		if (option.set(StringArgumentType.getString(context, "mode")) != null) {
			source.sendFailure(Component.literal("Unknown mode. Use off, emergency, autoTuning or always."));
			return 0;
		}
		String requested = option.format();
		option.set(previous);
		if (requested.equals(previous)) {
			source.sendSuccess(() -> Component.literal("Entity stacking is already '" + requested + "'.")
				.withStyle(ChatFormatting.GRAY), false);
			return 1;
		}
		if (requested.equals(TickEngineConfig.STACKING_OFF)) {
			pendingStackingDeadlines.remove(source.getTextName());
			pendingStackingModes.remove(source.getTextName());
			return applyStackingMode(source, requested);
		}
		long now = System.currentTimeMillis();
		pendingStackingDeadlines.values().removeIf(deadline -> deadline < now);
		pendingStackingModes.keySet().retainAll(pendingStackingDeadlines.keySet());
		pendingStackingDeadlines.put(source.getTextName(), now + SPREAD_CONFIRM_MILLIS);
		pendingStackingModes.put(source.getTextName(), requested);
		source.sendSuccess(() -> header("Entity stacking: read before turning it on"), false);
		stackingExplanation(source);
		String command = "/tickengine stacking confirm " + STACKING_PHRASE;
		source.sendSuccess(() -> Component.literal(" To set the mode to '" + requested + "', within "
			+ SPREAD_CONFIRM_MILLIS / 1000 + " s type: ").withStyle(ChatFormatting.GOLD)
			.append(Component.literal(command).withStyle(style -> style
				.withColor(ChatFormatting.WHITE)
				.withUnderlined(true)
				.withClickEvent(MinecraftCompat.suggestCommand(command))
				.withHoverEvent(MinecraftCompat.showText(Component.literal("Click to put it in the chat box"))))),
			false);
		return 1;
	}

	private static int stackingConfirm(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		Long deadline = pendingStackingDeadlines.get(source.getTextName());
		String mode = pendingStackingModes.get(source.getTextName());
		if (deadline == null || mode == null || System.currentTimeMillis() > deadline) {
			pendingStackingDeadlines.remove(source.getTextName());
			pendingStackingModes.remove(source.getTextName());
			source.sendFailure(Component.literal("Nothing to confirm. Run '/tickengine stacking mode <mode>' first and read the warning."));
			return 0;
		}
		if (!StringArgumentType.getString(context, "phrase").trim().equalsIgnoreCase(STACKING_PHRASE)) {
			source.sendFailure(Component.literal("The phrase does not match. Type exactly: /tickengine stacking confirm "
				+ STACKING_PHRASE));
			return 0;
		}
		pendingStackingDeadlines.remove(source.getTextName());
		pendingStackingModes.remove(source.getTextName());
		return applyStackingMode(source, mode);
	}

	private static int applyStackingMode(CommandSourceStack source, String mode) {
		TickEngineConfig.stackingMode = mode;
		TickEngineConfig.changed();
		TickEngine.LOGGER.info("Entity stacking mode set to '{}' by {}.", mode, source.getTextName());
		String detail = switch (mode) {
			case TickEngineConfig.STACKING_ALWAYS -> "Mobs start stacking within a second.";
			case TickEngineConfig.STACKING_AUTO_TUNING -> "Stacking is now the last auto-tuning level"
				+ (TickEngineConfig.autoTuning ? "" : " (auto-tuning is off: then only emergencies turn it on)")
				+ ", and runs in emergencies.";
			case TickEngineConfig.STACKING_EMERGENCY -> "Stacking runs only while emergency mode is active"
				+ (TickEngineConfig.emergencyEnabled ? "." : " (emergency mode is off: turn it on with '/tickengine emergency enable').");
			default -> "No new stacks are made. Stacks already there stay until they are killed or used; "
				+ "'/tickengine stacking unstack' splits them now.";
		};
		source.sendSuccess(() -> Component.literal("Entity stacking mode is '" + mode + "' and saved to the config file. "
			+ detail).withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	private static int stackingUnstack(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		int added = EntityStacker.unstackAll(source.getServer());
		TickEngine.LOGGER.info("{} split all loaded stacks: {} mobs added.", source.getTextName(), added);
		source.sendSuccess(() -> Component.literal("Split every loaded stack: " + added + " mobs added. Stacks in unloaded chunks "
			+ "stay until those chunks load." + (EntityStacker.isActive()
			? " Stacking is still running and will merge them again: set the mode to off first." : ""))
			.withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	private static int stackExcludeList(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		source.sendSuccess(() -> header("Excluded from entity stacking"), false);
		Set<String> excluded = TickEngineConfig.STACK_EXCLUDE.targets();
		if (excluded.isEmpty()) {
			source.sendSuccess(() -> Component.literal(" nothing listed").withStyle(ChatFormatting.GRAY), false);
		}
		excluded.forEach(target -> source.sendSuccess(() -> Component.literal(" " + target).withStyle(ChatFormatting.WHITE), false));
		return 1;
	}

	private static int stackExcludeAdd(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String target = StringArgumentType.getString(context, "target").trim();
		String error = TickEngineConfig.STACK_EXCLUDE.add(target);
		if (error != null) {
			source.sendFailure(Component.literal(error));
			return 0;
		}
		TickEngineConfig.changed();
		source.sendSuccess(() -> Component.literal("Excluded from entity stacking: " + target + ". Stacks already there stay; "
			+ "'/tickengine stacking unstack' splits them.").withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	private static int stackExcludeRemove(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String target = StringArgumentType.getString(context, "target").trim();
		if (!TickEngineConfig.STACK_EXCLUDE.remove(target)) {
			source.sendFailure(Component.literal("'" + target + "' is not excluded from entity stacking."));
			return 0;
		}
		TickEngineConfig.changed();
		source.sendSuccess(() -> Component.literal("No longer excluded from entity stacking: " + target + ".")
			.withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	// --- entity limit ---

	private static int limitStatus(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		source.sendSuccess(() -> header("Entity limit per chunk"), false);
		source.sendSuccess(() -> line("State", TickEngineConfig.entityLimitEnabled ? "on" : "off",
			TickEngineConfig.entityLimitEnabled ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
		source.sendSuccess(() -> line("Limit", "at most " + TickEngineConfig.entityLimitPerChunk + " mobs of one type per chunk; "
			+ TickEngineConfig.LIMIT_RULES.rules().size() + " rules ('/tickengine limit rules list')", ChatFormatting.WHITE), false);
		String stops = Stream.of(EntityLimit.Source.values()).filter(EntityLimit::limits)
			.map(limited -> limited.displayName).collect(Collectors.joining(", "));
		source.sendSuccess(() -> line("Stops", stops.isEmpty() ? "nothing (every source is off in the config)" : stops,
			ChatFormatting.WHITE), false);
		Set<String> excluded = TickEngineConfig.LIMIT_EXCLUDE.targets();
		source.sendSuccess(() -> line("Excluded", (excluded.isEmpty() ? "nothing" : String.join(", ", excluded))
			+ "; change with '/tickengine limit exclude'.", ChatFormatting.WHITE), false);
		for (EntityLimit.Source limited : EntityLimit.Source.values()) {
			long blocked = EntityLimit.blocked(limited);
			EntityLimit.Blocked last = EntityLimit.lastBlocked(limited);
			if (blocked == 0 || last == null) {
				continue;
			}
			MutableComponent where = teleportLink(source, Component.literal(last.type().toString()).withStyle(ChatFormatting.WHITE),
				last.dimension(), last.pos(), false, false, "Last stopped here, at the limit of " + last.limit());
			source.sendSuccess(() -> Component.literal(" Stopped " + limited.displayName + ": ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(blocked + " times").withStyle(ChatFormatting.WHITE))
				.append(Component.literal(", last ").withStyle(ChatFormatting.GRAY)).append(where), false);
		}
		source.sendSuccess(() -> Component.literal(" Mobs already in a chunk are never removed, and a stack counts as one. "
			+ "Spawn eggs, dispensers and commands are not stopped.").withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.literal(TickEngineConfig.entityLimitEnabled
			? " Turn it off with '/tickengine limit disable'."
			: " Turn it on with '/tickengine limit enable'.").withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	private static int limitSet(CommandContext<CommandSourceStack> context, boolean enabled) {
		TickEngineConfig.entityLimitEnabled = enabled;
		TickEngineConfig.changed();
		context.getSource().sendSuccess(() -> Component.literal(enabled
			? "Entity limit is on: a chunk with " + TickEngineConfig.entityLimitPerChunk + " mobs of one type (or its rule's "
				+ "limit) gets no more of that type from spawning or breeding."
			: "Entity limit is off.").withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	private static LiteralArgumentBuilder<CommandSourceStack> limitRules() {
		ThrottleRules<Integer> rules = TickEngineConfig.LIMIT_RULES;
		return Commands.literal("rules")
			.then(Commands.literal("list").executes(context -> rulesList(context, "Entity limit rules", rules,
				"Default", rules.type().describe(TickEngineConfig.entityLimitPerChunk), true)))
			.then(Commands.literal("set")
				.then(limitArgument()
					.then(targetArgument(BuiltInRegistries.ENTITY_TYPE.keySet(), false)
						.executes(context -> rulesSet(context, rules, IntegerArgumentType.getInteger(context, "limit"),
							false, null, true)))))
			.then(Commands.literal("set-in")
				.then(Commands.argument("dimension", DimensionArgument.dimension())
					.then(limitArgument()
						.then(targetArgument(BuiltInRegistries.ENTITY_TYPE.keySet(), true)
							.executes(context -> rulesSet(context, rules, IntegerArgumentType.getInteger(context, "limit"),
								true, null, true))))))
			.then(removeRule(rules, false))
			.then(removeRule(rules, true));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, Integer> limitArgument() {
		return Commands.argument("limit", IntegerArgumentType.integer(1, ThrottleRules.MAX_LIMIT));
	}

	private static int limitExcludeList(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		source.sendSuccess(() -> header("Excluded from the entity limit"), false);
		Set<String> excluded = TickEngineConfig.LIMIT_EXCLUDE.targets();
		if (excluded.isEmpty()) {
			source.sendSuccess(() -> Component.literal(" nothing listed").withStyle(ChatFormatting.GRAY), false);
		}
		excluded.forEach(target -> source.sendSuccess(() -> Component.literal(" " + target).withStyle(ChatFormatting.WHITE), false));
		return 1;
	}

	private static int limitExcludeAdd(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String target = StringArgumentType.getString(context, "target").trim();
		String error = TickEngineConfig.LIMIT_EXCLUDE.add(target);
		if (error != null) {
			source.sendFailure(Component.literal(error));
			return 0;
		}
		TickEngineConfig.changed();
		source.sendSuccess(() -> Component.literal("Excluded from the entity limit: " + target + ".")
			.withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	private static int limitExcludeRemove(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String target = StringArgumentType.getString(context, "target").trim();
		if (!TickEngineConfig.LIMIT_EXCLUDE.remove(target)) {
			source.sendFailure(Component.literal("'" + target + "' is not excluded from the entity limit."));
			return 0;
		}
		TickEngineConfig.changed();
		source.sendSuccess(() -> Component.literal("No longer excluded from the entity limit: " + target + ".")
			.withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	// --- history ---

	private static RequiredArgumentBuilder<CommandSourceStack, Integer> hoursArgument() {
		// Up to the most days the history can keep.
		return Commands.argument("hours", IntegerArgumentType.integer(1, 90 * 24));
	}

	/** Reads the history on the IO pool, then runs {@code show} on the server thread. */
	private static void readHistory(CommandSourceStack source, long fromMillis, long toMillis,
		Consumer<List<HistoryEntry>> show) {
		afterIo(source, true, HistoryStore.read(fromMillis, toMillis), (entries, error) -> {
			if (error != null) {
				TickEngine.LOGGER.error("Could not read the history", error);
				source.sendFailure(Component.literal("Could not read the history, see the server log."));
			} else {
				show.accept(entries);
			}
		});
	}

	private static int history(CommandContext<CommandSourceStack> context, int hours) {
		CommandSourceStack source = context.getSource();
		long now = System.currentTimeMillis();
		readHistory(source, now - hours * 3_600_000L, now, entries -> printHistory(source, entries, hours));
		return 1;
	}

	private static void printHistory(CommandSourceStack source, List<HistoryEntry> entries, int hours) {
		List<HistoryEntry> periods = entries.stream().filter(entry -> entry.kind().equals(HistoryEntry.PERIOD)).toList();
		source.sendSuccess(() -> header("History, last " + hours + " h"), false);
		if (periods.isEmpty()) {
			source.sendSuccess(() -> Component.literal(TickEngineConfig.historyEnabled
				? " Nothing recorded yet: the first line is written after a minute."
				: " The history is off. Turn it on with '/tickengine config set history.enabled true'.")
				.withStyle(ChatFormatting.GRAY), false);
			return;
		}
		long ticks = periods.stream().mapToLong(HistoryEntry::ticks).sum();
		double average = ticks == 0 ? 0 : periods.stream().mapToDouble(entry -> entry.avgMs() * entry.ticks()).sum() / ticks;
		HistoryEntry worst = periods.stream().max((a, b) -> Double.compare(a.avgMs(), b.avgMs())).orElseThrow();
		HistoryEntry longest = periods.stream().max((a, b) -> Double.compare(a.maxMs(), b.maxMs())).orElseThrow();
		source.sendSuccess(() -> line("Recorded", periods.size() + " periods, " + historyTime(periods.get(0).startMillis())
			+ " to " + historyTime(periods.get(periods.size() - 1).endMillis()), ChatFormatting.WHITE), false);
		source.sendSuccess(() -> line("Average tick", format(average) + " ms", msptColor(average)), false);
		source.sendSuccess(() -> Component.literal(" Worst: ").withStyle(ChatFormatting.GRAY)
			.append(peakLink(worst, "average " + format(worst.avgMs()) + " ms at " + historyTime(worst.endMillis())))
			.append(Component.literal(", longest tick ").withStyle(ChatFormatting.GRAY))
			.append(peakLink(longest, format(longest.maxMs()) + " ms at " + historyTime(longest.endMillis()))), false);
		List<HistoryEntry> peaks = periods.stream().filter(HistoryEntry::isPeak).toList().reversed();
		source.sendSuccess(() -> Component.literal("Peaks: " + peaks.size()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
			.append(Component.literal(peaks.isEmpty() ? "" : " (newest first, click one for what took the time)")
				.withStyle(ChatFormatting.GRAY)), false);
		for (HistoryEntry peak : peaks.subList(0, Math.min(TickEngineConfig.chatTopEntries, peaks.size()))) {
			String mostly = peak.detail() == null || peak.detail().mods().isEmpty() ? ""
				: "  mostly " + peak.detail().mods().get(0).name();
			source.sendSuccess(() -> Component.literal(" ").append(peakLink(peak, historyTime(peak.endMillis())))
				.append(Component.literal("  avg " + format(peak.avgMs()) + " ms, longest " + format(peak.maxMs())
					+ " ms, auto-tuning " + peak.tuningLevel() + (peak.emergency() ? ", emergency" : ""))
					.withStyle(msptColor(peak.avgMs())))
				.append(Component.literal(mostly).withStyle(ChatFormatting.DARK_GRAY)), false);
		}
		long sessions = entries.stream().filter(entry -> entry.kind().equals(HistoryEntry.SESSION)).count();
		if (sessions > 0) {
			source.sendSuccess(() -> line("Detailed sessions during peaks", Long.toString(sessions), ChatFormatting.WHITE), false);
		}
		String command = "/tickengine history report " + hours;
		source.sendSuccess(() -> Component.literal(" Chart and every peak: ").withStyle(ChatFormatting.GRAY)
			.append(Component.literal(command).withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true)
				.withClickEvent(MinecraftCompat.runCommand(command)))), false);
	}

	private static MutableComponent peakLink(HistoryEntry entry, String text) {
		String command = "/tickengine history peak " + entry.endMillis();
		return Component.literal(text).withStyle(style -> style.withColor(ChatFormatting.WHITE).withUnderlined(true)
			.withClickEvent(MinecraftCompat.runCommand(command))
			.withHoverEvent(MinecraftCompat.showText(Component.literal("Click for what took the time"))));
	}

	private static String historyTime(long millis) {
		return HISTORY_TIME.format(Instant.ofEpochMilli(millis));
	}

	private static int historyPeak(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		long time = LongArgumentType.getLong(context, "time");
		// The period itself, and the detailed sessions that ran during it or up to a few minutes after it.
		readHistory(source, time - HISTORY_SESSION_AFTER_MILLIS - 3_600_000L, time + HISTORY_SESSION_AFTER_MILLIS, entries -> {
			HistoryEntry period = entries.stream().filter(entry -> entry.endMillis() == time).findFirst().orElse(null);
			if (period == null) {
				source.sendFailure(Component.literal("Nothing in the history at that time (it may have been deleted)."));
				return;
			}
			printHistoryEntry(source, period);
			entries.stream().filter(entry -> entry.kind().equals(HistoryEntry.SESSION) && entry != period
					&& entry.endMillis() > period.startMillis() && entry.startMillis() < time + HISTORY_SESSION_AFTER_MILLIS)
				.forEach(session -> printHistoryEntry(source, session));
		});
		return 1;
	}

	private static void printHistoryEntry(CommandSourceStack source, HistoryEntry entry) {
		String title = (entry.kind().equals(HistoryEntry.SESSION) ? "Detailed session ending " : "Period ending ")
			+ historyTime(entry.endMillis()) + " (" + entry.seconds() + " s)";
		source.sendSuccess(() -> header(title), false);
		source.sendSuccess(() -> line("Ticks", "average " + format(entry.avgMs()) + " ms, longest " + format(entry.maxMs())
			+ " ms, TPS " + format(entry.tps()), msptColor(entry.avgMs())), false);
		source.sendSuccess(() -> line("Server", entry.players() + " players, " + entry.entities() + " entities, "
			+ entry.chunks() + " chunks, auto-tuning " + entry.tuningLevel() + (entry.emergency() ? ", emergency mode" : ""),
			ChatFormatting.WHITE), false);
		if (entry.isPeak()) {
			source.sendSuccess(() -> line("Peak because of", String.join(", ", entry.peaks()), ChatFormatting.YELLOW), false);
		}
		HistoryEntry.Detail detail = entry.detail();
		if (detail == null) {
			source.sendSuccess(() -> Component.literal(entry.isPeak()
				? " No profiler data for this period (the light profiler was off or had not finished a window)."
				: " Not a peak: only peaks keep what took the time.").withStyle(ChatFormatting.GRAY), false);
			return;
		}
		source.sendSuccess(() -> Component.literal(" From the " + detail.source()
			+ (detail.source().equals("light profiler") ? " (one tick in 20)" : "")).withStyle(ChatFormatting.GRAY), false);
		title(source, "Mods");
		detail.mods().forEach(mod -> source.sendSuccess(() -> Component.literal("  " + mod.name()).withStyle(ChatFormatting.WHITE)
			.append(Component.literal("  " + format(mod.msPerTick()) + " ms/tick").withStyle(ChatFormatting.GRAY)), false));
		title(source, "Types");
		for (HistoryEntry.Type type : detail.types()) {
			MutableComponent name = Component.literal(type.id()).withStyle(ChatFormatting.WHITE);
			if (type.dimension() != null) {
				name = teleportLink(source, name, dimensionKey(type.dimension()), new BlockPos(type.x(), type.y(), type.z()),
					!type.category().equals(ProfileSnapshot.Category.ENTITY.displayName), false, "Slowest instance in that period");
			}
			MutableComponent finalName = name;
			source.sendSuccess(() -> Component.literal("  ").append(finalName)
				.append(Component.literal(" " + type.category().toLowerCase(Locale.ROOT)).withStyle(ChatFormatting.DARK_AQUA))
				.append(Component.literal("  " + format(type.msPerTick()) + " ms/tick").withStyle(ChatFormatting.GRAY)), false);
		}
		title(source, "Chunks");
		for (HistoryEntry.Chunk chunk : detail.chunks()) {
			MutableComponent name = teleportLink(source,
				Component.literal(chunk.dimension() + " " + chunk.x() + " " + chunk.z()).withStyle(ChatFormatting.WHITE),
				dimensionKey(chunk.dimension()), new BlockPos(chunk.x(), chunk.y(), chunk.z()), chunk.onTop(), false,
				"Chunk " + chunk.chunkX() + ", " + chunk.chunkZ() + "\nMostly: " + chunk.top());
			source.sendSuccess(() -> Component.literal("  ").append(name)
				.append(Component.literal(" " + chunk.top()).withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal("  " + format(chunk.msPerTick()) + " ms/tick").withStyle(ChatFormatting.GRAY)), false);
		}
	}

	private static ResourceKey<Level> dimensionKey(String id) {
		return ResourceKey.create(Registries.DIMENSION, Identifier.parse(id));
	}

	private static int historyReport(CommandContext<CommandSourceStack> context, int hours) {
		CommandSourceStack source = context.getSource();
		MinecraftServer server = source.getServer();
		long now = System.currentTimeMillis();
		readHistory(source, now - hours * 3_600_000L, now, entries -> afterIo(source, true,
			HistoryHtmlReport.writeAsync(server, entries, hours, now), (file, error) -> {
				if (error != null) {
					TickEngine.LOGGER.error("Could not write the history report", error);
					source.sendFailure(Component.literal("Could not write the HTML report, see the server log."));
				} else {
					sendReportSaved(server, source, file);
				}
			}));
		return 1;
	}

	// --- top ---

	private enum View { ALL, MODS, BLOCK_ENTITIES, ENTITIES, BLOCKS, CHUNKS, WORLDGEN }

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
			printEntries(source, snapshot, snapshot.entries(ProfileSnapshot.Category.BLOCK_ENTITY), "Block entities", false);
		}
		if (view == View.ALL || view == View.ENTITIES) {
			printEntries(source, snapshot, snapshot.entries(ProfileSnapshot.Category.ENTITY), "Entities", false);
		}
		if (view == View.ALL || view == View.BLOCKS) {
			printEntries(source, snapshot, snapshot.blockEntries(),
				"Blocks (scheduled ticks, random ticks, block events)", true);
		}
		if (view == View.ALL || view == View.CHUNKS) {
			printChunkTimes(source, snapshot);
		}
		if (view == View.ALL || view == View.WORLDGEN) {
			printChunkLoading(source, snapshot, view == View.WORLDGEN);
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

	/** {@code showKind}: the list mixes categories (the block list), so each row says which one it is. */
	private static void printEntries(CommandSourceStack source, ProfileSnapshot snapshot,
		List<ProfileSnapshot.Entry> entries, String title, boolean showKind) {
		source.sendSuccess(() -> Component.literal(title).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), false);
		if (entries.isEmpty()) {
			source.sendSuccess(() -> Component.literal("  no data").withStyle(ChatFormatting.GRAY), false);
		}
		for (int i = 0; i < Math.min(TickEngineConfig.chatTopEntries, entries.size()); i++) {
			ProfileSnapshot.Entry entry = entries.get(i);
			int rank = i + 1;
			MutableComponent name = Component.literal(entry.id()).withStyle(ChatFormatting.WHITE);
			if (entry.worstPos() != null) {
				// Land on top of a block (entity) instead of inside it, where a player would suffocate.
				boolean block = entry.category() != ProfileSnapshot.Category.ENTITY;
				name = teleportLink(source, name, entry.worstDimension(), entry.worstPos(), block, false,
					"Slowest instance: " + format(entry.worstNanos() / 1_000_000.0) + " ms in one tick");
			}
			MutableComponent finalName = name;
			double perTick = entry.calls() / (double) snapshot.sampledTicks;
			MutableComponent line = Component.literal(" " + rank + ". ").withStyle(ChatFormatting.GRAY).append(finalName);
			if (showKind) {
				line.append(Component.literal(" " + entry.category().displayName.toLowerCase(Locale.ROOT))
					.withStyle(ChatFormatting.DARK_AQUA));
			}
			line.append(Component.literal(" x" + format(perTick)).withStyle(ChatFormatting.DARK_GRAY))
				.append(timing(snapshot, entry.totalNanos()));
			source.sendSuccess(() -> line, false);
		}
	}

	/**
	 * Underlines {@code name} and makes a click teleport to {@code pos}, on free ground. {@code surface}:
	 * land on the surface above (worldgen, whose positions are chunk corners at the bottom of the world).
	 */
	private static MutableComponent teleportLink(CommandSourceStack source, MutableComponent name,
		ResourceKey<Level> dimensionKey, BlockPos pos, boolean onTop, boolean surface, String hover) {
		String dimension = dimensionKey.identifier().toString();
		String coordinates = pos.getX() + " " + pos.getY() + " " + pos.getZ();
		ServerLevel level = source.getServer().getLevel(dimensionKey);
		String landingY;
		if (surface) {
			// Reading blocks of an unloaded chunk would load it; keep the player's height then.
			landingY = level == null || !level.isLoaded(pos) ? "~"
				: Integer.toString(level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()));
		} else {
			landingY = Integer.toString(landingY(level, onTop ? pos.above() : pos));
		}
		String landing = pos.getX() + " " + landingY + " " + pos.getZ();
		return name.withStyle(style -> style
			.withClickEvent(MinecraftCompat.runCommand("/execute in " + dimension + " run tp @s " + landing))
			.withHoverEvent(MinecraftCompat.showText(Component.literal(
				hover + "\n" + dimension + " " + coordinates + "\nClick to teleport")))
			.withUnderlined(true));
	}

	/**
	 * Chunks by tick time spent in them. A click goes to the slowest single call in the chunk, which
	 * is where its farm or machine is, unlike the chunk center.
	 */
	private static void printChunkTimes(CommandSourceStack source, ProfileSnapshot snapshot) {
		source.sendSuccess(() -> Component.literal("Chunks").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), false);
		List<ProfileSnapshot.ChunkEntry> chunks = snapshot.chunkTimes;
		if (chunks.isEmpty()) {
			source.sendSuccess(() -> Component.literal("  no data").withStyle(ChatFormatting.GRAY), false);
		}
		for (int i = 0; i < Math.min(TickEngineConfig.chatTopEntries, chunks.size()); i++) {
			ProfileSnapshot.ChunkEntry entry = chunks.get(i);
			int rank = i + 1;
			BlockPos pos = entry.worstPos();
			String dimension = entry.dimension().identifier().toString();
			StringBuilder hover = new StringBuilder("Chunk ").append(entry.x()).append(", ").append(entry.z());
			chunkPart(hover, snapshot, "Block entities", entry.nanos(ProfileSnapshot.Category.BLOCK_ENTITY));
			chunkPart(hover, snapshot, "Entities", entry.nanos(ProfileSnapshot.Category.ENTITY));
			chunkPart(hover, snapshot, "Blocks", entry.blockNanos());
			hover.append("\nMostly:");
			entry.topTypes().forEach(type -> hover.append("\n ").append(type.id()).append("  ")
				.append(format(entry.totalNanos() == 0 ? 0 : type.nanos() * 100.0 / entry.totalNanos())).append('%'));
			// Land on top of a block (entity) instead of inside it, where a player would suffocate.
			boolean block = entry.worstCategory() != ProfileSnapshot.Category.ENTITY;
			MutableComponent name = teleportLink(source,
				Component.literal(dimension + " " + pos.getX() + " " + pos.getZ()).withStyle(ChatFormatting.WHITE),
				entry.dimension(), pos, block, false, hover.toString());
			String top = entry.topTypes().isEmpty() ? "" : " " + entry.topTypes().get(0).id();
			source.sendSuccess(() -> Component.literal(" " + rank + ". ").withStyle(ChatFormatting.GRAY).append(name)
				.append(Component.literal(top).withStyle(ChatFormatting.DARK_GRAY))
				.append(timing(snapshot, entry.totalNanos())), false);
		}
	}

	private static void chunkPart(StringBuilder hover, ProfileSnapshot snapshot, String label, long nanos) {
		if (nanos > 0) {
			hover.append('\n').append(label).append(": ").append(format(snapshot.msPerTick(nanos))).append(" ms/tick");
		}
	}

	/**
	 * Chunk work on the server thread (per sampled tick), then loading and generation over the whole
	 * window. {@code detailed}: also the slowest features and structures and the worldgen mods.
	 */
	private static void printChunkLoading(CommandSourceStack source, ProfileSnapshot snapshot, boolean detailed) {
		ChunkActivity chunks = snapshot.chunks;
		List<ProfileSnapshot.Entry> work = snapshot.entries(ProfileSnapshot.Category.CHUNK);
		source.sendSuccess(() -> Component.literal("Chunk loading and worldgen").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), false);
		if (work.isEmpty() && chunks.isEmpty()) {
			source.sendSuccess(() -> Component.literal("  no chunk loaded, generated or saved").withStyle(ChatFormatting.GRAY), false);
			return;
		}
		for (ProfileSnapshot.Entry entry : work) {
			source.sendSuccess(() -> Component.literal("  " + entry.id()).withStyle(ChatFormatting.WHITE)
				.append(timing(snapshot, entry.totalNanos())), false);
		}
		if (chunks.betweenTickNanos() > 0) {
			source.sendSuccess(() -> Component.literal("  Chunk tasks between ticks: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(format(snapshot.perSecond(chunks.betweenTickNanos()) / 1_000_000.0) + " ms/s")
					.withStyle(ChatFormatting.WHITE))
				.append(Component.literal(" (spare time, not tick time)").withStyle(ChatFormatting.DARK_GRAY)), false);
		}
		source.sendSuccess(() -> Component.literal("  Loaded from disk ").withStyle(ChatFormatting.GRAY)
			.append(count(snapshot, chunks.loaded()))
			.append(Component.literal(", started generating ").withStyle(ChatFormatting.GRAY)
				.withStyle(style -> style.withHoverEvent(MinecraftCompat.showText(Component.literal(
					"Chunks with nothing saved. Around every chunk that finishes generating,\n"
						+ "its neighbours are generated part of the way, so this is higher.")))))
			.append(count(snapshot, chunks.created()))
			.append(Component.literal(", finished ").withStyle(ChatFormatting.GRAY))
			.append(count(snapshot, chunks.generated())), false);
		if (!chunks.generatedNear().isEmpty()) {
			String near = chunks.generatedNear().stream().limit(detailed ? TickEngineConfig.chatTopEntries : 3)
				.map(player -> player.player() + " " + player.chunks()).collect(Collectors.joining(", "));
			source.sendSuccess(() -> Component.literal("  Generated near: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(near).withStyle(ChatFormatting.WHITE)), false);
		}
		long features = chunks.featureNanos();
		long structures = chunks.structureNanos();
		if (features > 0 || structures > 0) {
			source.sendSuccess(() -> Component.literal("  Worldgen threads: features ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(format(snapshot.perSecond(features) / 1_000_000.0) + " ms/s").withStyle(ChatFormatting.WHITE))
				.append(Component.literal(", structures ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(format(snapshot.perSecond(structures) / 1_000_000.0) + " ms/s").withStyle(ChatFormatting.WHITE))
				.append(Component.literal(" (not tick time)").withStyle(ChatFormatting.DARK_GRAY)), false);
		}
		if (!detailed) {
			if (features > 0 || structures > 0 || chunks.generated() > 0) {
				source.sendSuccess(() -> Component.literal("  Slowest features and structures: /tickengine top worldgen")
					.withStyle(ChatFormatting.DARK_GRAY), false);
			}
			return;
		}
		printWorldgen(source, snapshot, "Worldgen by mod", null, chunks.worldgenMods());
		printWorldgen(source, snapshot, "Features", chunks.features(), null);
		printWorldgen(source, snapshot, "Structures", chunks.structures(), null);
	}

	private static void printWorldgen(CommandSourceStack source, ProfileSnapshot snapshot, String title,
		List<ChunkActivity.WorldgenEntry> entries, List<ProfileSnapshot.ModEntry> mods) {
		int size = entries != null ? entries.size() : mods.size();
		if (size == 0) {
			return;
		}
		source.sendSuccess(() -> Component.literal(" " + title).withStyle(ChatFormatting.AQUA), false);
		for (int i = 0; i < Math.min(TickEngineConfig.chatTopEntries, size); i++) {
			int rank = i + 1;
			MutableComponent name;
			long nanos;
			if (entries != null) {
				ChunkActivity.WorldgenEntry entry = entries.get(i);
				nanos = entry.totalNanos();
				name = Component.literal(entry.id()).withStyle(ChatFormatting.WHITE);
				if (entry.worstPos() != null && entry.worstDimension() != null) {
					name = teleportLink(source, name, entry.worstDimension(), entry.worstPos(), false, true,
						"Slowest placement: " + format(entry.worstNanos() / 1_000_000.0) + " ms in one chunk");
				}
				name.append(Component.literal(" x" + entry.calls()).withStyle(ChatFormatting.DARK_GRAY));
			} else {
				ProfileSnapshot.ModEntry mod = mods.get(i);
				nanos = mod.totalNanos();
				name = Component.literal(mod.displayName()).withStyle(ChatFormatting.WHITE);
			}
			MutableComponent finalName = name;
			source.sendSuccess(() -> Component.literal("  " + rank + ". ").withStyle(ChatFormatting.GRAY).append(finalName)
				.append(Component.literal("  " + format(snapshot.perSecond(nanos) / 1_000_000.0) + " ms/s")
					.withStyle(ChatFormatting.WHITE)), false);
		}
	}

	private static MutableComponent count(ProfileSnapshot snapshot, long count) {
		return Component.literal(Long.toString(count)).withStyle(ChatFormatting.WHITE)
			.append(Component.literal(" (" + format(snapshot.perSecond(count)) + "/s)").withStyle(ChatFormatting.DARK_GRAY));
	}

	/**
	 * First height at or above {@code start} with two free blocks for the player, checked when the
	 * list is printed. Falls back to {@code start} when nothing free is found nearby, or when the chunk
	 * is not loaded: reading a block there would load it from disk on the server thread.
	 */
	private static int landingY(ServerLevel level, BlockPos start) {
		if (level == null || !level.isLoaded(start)) {
			return start.getY();
		}
		BlockPos.MutableBlockPos pos = start.mutable();
		int top = Math.min(MinecraftCompat.maxY(level), start.getY() + 64);
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
		writeReport(server, source, result, false);
	}

	private static int lightReport(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ProfileSnapshot snapshot = TickProfiler.lightSnapshot();
		if (snapshot == null) {
			source.sendFailure(Component.literal("No light profiler data yet."));
			return 0;
		}
		writeReport(source.getServer(), source, snapshot, true);
		return 1;
	}

	/** {@code inCommand}: called while the command runs, not when a session ends later (see {@link #afterIo}). */
	private static void writeReport(MinecraftServer server, CommandSourceStack source, ProfileSnapshot snapshot, boolean inCommand) {
		afterIo(source, inCommand, HtmlReport.writeAsync(server, snapshot), (file, error) -> {
			if (error != null) {
				TickEngine.LOGGER.error("Could not write HTML report", error);
				source.sendFailure(Component.literal("Could not write the HTML report, see the server log."));
			} else {
				sendReportSaved(server, source, file);
			}
		});
	}

	/** Longest a console command waits for the disk; past it the result still comes, but too late for RCON. */
	private static final long IO_WAIT_SECONDS = 10;

	/**
	 * Runs {@code then} on the server thread once {@code work} (disk work on the IO pool) is done.
	 * RCON sends a command's output when the command returns and drops anything later, so a command
	 * from the console or RCON ({@code inCommand}, no player) waits for the disk before it returns: a
	 * short stall, only for those commands. Players, and results of sessions that end later, get it a
	 * moment later without any wait.
	 */
	private static <T> void afterIo(CommandSourceStack source, boolean inCommand, CompletableFuture<T> work,
		BiConsumer<T, Throwable> then) {
		MinecraftServer server = source.getServer();
		if (inCommand && source.getEntity() == null) {
			try {
				then.accept(work.get(IO_WAIT_SECONDS, TimeUnit.SECONDS), null);
				return;
			} catch (ExecutionException e) {
				then.accept(null, e.getCause());
				return;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (TimeoutException e) {
				// A slow disk: answer when the work is done, like for players.
			}
		}
		work.whenComplete((value, error) -> server.execute(() -> then.accept(value, error)));
	}

	/** The saved path, as a link to open in the browser when the report web server runs. */
	private static void sendReportSaved(MinecraftServer server, CommandSourceStack source, Path file) {
		String path = server.getServerDirectory().toAbsolutePath().relativize(file.toAbsolutePath()).toString();
		String link = ReportWebServer.link(source, file);
		if (link == null) {
			source.sendSuccess(() -> Component.literal("HTML report saved: ").withStyle(ChatFormatting.GREEN)
				.append(Component.literal(path).withStyle(ChatFormatting.WHITE)), false);
			return;
		}
		source.sendSuccess(() -> Component.literal("HTML report saved: ").withStyle(ChatFormatting.GREEN)
			.append(Component.literal("[open in browser]").withStyle(style -> style.withColor(ChatFormatting.AQUA)
				.withUnderlined(true).withClickEvent(MinecraftCompat.openUrl(link))
				.withHoverEvent(MinecraftCompat.showText(Component.literal(link)))))
			.append(Component.literal(" " + path).withStyle(ChatFormatting.GRAY)), false);
		if (source.getEntity() == null) {
			source.sendSuccess(() -> Component.literal(" " + link).withStyle(ChatFormatting.GRAY), false);
		}
	}

	// --- network ---

	private enum NetworkView { ALL, MODS, SOURCES, PACKETS, CHUNKS }

	private static int networkStatus(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		source.sendSuccess(() -> header("Network profiler"), false);
		source.sendSuccess(() -> line("Session", NetworkProfiler.isSessionRunning()
			? "running, " + NetworkProfiler.sessionSecondsLeft() + " s left" : "not running",
			NetworkProfiler.isSessionRunning() ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
		NetworkSnapshot last = NetworkProfiler.lastSnapshot();
		source.sendSuccess(() -> line("Last result", last == null ? "none"
			: NetworkSnapshot.formatBytes(last.perSecond(last.wireBytes)) + "/s over " + last.durationMillis / 1000 + " s",
			ChatFormatting.WHITE), false);
		source.sendSuccess(() -> Component.literal(" Measures what the server sends to players, by mod, entity, block, "
			+ "channel and chunk. Start with '/tickengine network start [seconds]'.").withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	private static int networkStart(CommandContext<CommandSourceStack> context, int seconds) {
		CommandSourceStack source = context.getSource();
		if (seconds > TickEngineConfig.maxSessionSeconds) {
			source.sendFailure(Component.literal("Maximum session length is " + TickEngineConfig.maxSessionSeconds
				+ " s (config: profiler.maxSessionSeconds)."));
			return 0;
		}
		MinecraftServer server = source.getServer();
		if (!NetworkProfiler.startSession(seconds, result -> finishNetworkSession(server, source, result))) {
			source.sendFailure(Component.literal("A network session is already running ("
				+ NetworkProfiler.sessionSecondsLeft() + " s left). Use '/tickengine network stop'."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Network profiling started for " + seconds
			+ " s. Every packet sent to players is measured; results and an HTML report follow when it ends.")
			.withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	private static int networkStop(CommandContext<CommandSourceStack> context) {
		if (!NetworkProfiler.stopSession()) {
			context.getSource().sendFailure(Component.literal("No network session is running."));
			return 0;
		}
		return 1;
	}

	private static void finishNetworkSession(MinecraftServer server, CommandSourceStack source, NetworkSnapshot result) {
		if (result.isEmpty()) {
			source.sendFailure(Component.literal("The network session ended without any packet sent. "
				+ "Is a player online?"));
			return;
		}
		printNetwork(source, result, NetworkView.ALL);
		writeNetworkReport(server, source, result, false);
	}

	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

	/**
	 * The last finished result. While a session runs it is the one from before, so say so: otherwise it
	 * looks like the running session found nothing.
	 */
	private static NetworkSnapshot lastNetworkSnapshot(CommandSourceStack source) {
		NetworkSnapshot snapshot = NetworkProfiler.lastSnapshot();
		boolean running = NetworkProfiler.isSessionRunning();
		if (snapshot == null || snapshot.isEmpty()) {
			source.sendFailure(Component.literal(running
				? "A network session is running (" + NetworkProfiler.sessionSecondsLeft() + " s left); its results come when it ends."
				: "No network results yet. Run '/tickengine network start [seconds]' first."));
			return null;
		}
		if (running) {
			source.sendSuccess(() -> Component.literal("A network session is running (" + NetworkProfiler.sessionSecondsLeft()
				+ " s left). This is the previous result, started at " + CLOCK.format(Instant.ofEpochMilli(snapshot.startMillis))
				+ "; the new one comes when the session ends.").withStyle(ChatFormatting.YELLOW), false);
		}
		return snapshot;
	}

	private static int networkTop(CommandContext<CommandSourceStack> context, NetworkView view) {
		NetworkSnapshot snapshot = lastNetworkSnapshot(context.getSource());
		if (snapshot == null) {
			return 0;
		}
		printNetwork(context.getSource(), snapshot, view);
		return 1;
	}

	private static int networkReport(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		NetworkSnapshot snapshot = lastNetworkSnapshot(source);
		if (snapshot == null) {
			return 0;
		}
		writeNetworkReport(source.getServer(), source, snapshot, true);
		return 1;
	}

	private static void printNetwork(CommandSourceStack source, NetworkSnapshot snapshot, NetworkView view) {
		source.sendSuccess(() -> header(snapshot.label)
			.append(Component.literal(" (" + snapshot.durationMillis / 1000 + " s, "
				+ NetworkSnapshot.formatBytes(snapshot.perSecond(snapshot.wireBytes)) + "/s sent, "
				+ format(snapshot.perSecond(snapshot.packets)) + " packets/s, compression saved "
				+ format(snapshot.compressionSaving()) + "%)").withStyle(ChatFormatting.GRAY)), false);
		if (snapshot.compressionUnmeasured > 0) {
			source.sendSuccess(() -> Component.literal(" Another mod compresses the packets (" + snapshot.compressionUnmeasured
				+ " of them): their sent size is shown before compression.").withStyle(ChatFormatting.YELLOW), false);
		}
		int limit = TickEngineConfig.chatTopEntries;
		if (view == NetworkView.ALL || view == NetworkView.MODS) {
			title(source, "Mods");
			List<NetworkSnapshot.ModEntry> mods = snapshot.mods();
			for (int i = 0; i < Math.min(limit, mods.size()); i++) {
				NetworkSnapshot.ModEntry mod = mods.get(i);
				rankLine(source, i, Component.literal(mod.displayName()).withStyle(ChatFormatting.WHITE), snapshot,
					mod.wireBytes());
			}
		}
		if (view == NetworkView.ALL || view == NetworkView.SOURCES) {
			title(source, "Sources");
			for (int i = 0; i < Math.min(limit, snapshot.sources.size()); i++) {
				NetworkSnapshot.SourceEntry entry = snapshot.sources.get(i);
				StringBuilder hover = new StringBuilder(entry.source().kind().displayName).append(", ")
					.append(format(snapshot.perSecond(entry.packets()))).append(" packets/s\nMostly as:");
				entry.topPackets().forEach(share -> hover.append("\n ").append(share.packet()).append("  ")
					.append(format(entry.wireBytes() == 0 ? 0 : share.wireBytes() * 100.0 / entry.wireBytes())).append('%'));
				MutableComponent name = Component.literal(entry.source().id()).withStyle(style -> style
					.withColor(ChatFormatting.WHITE)
					.withHoverEvent(MinecraftCompat.showText(Component.literal(hover.toString()))));
				rankLine(source, i, Component.literal(kindTag(entry.source().kind())).withStyle(ChatFormatting.DARK_GRAY)
					.append(name), snapshot, entry.wireBytes());
			}
		}
		if (view == NetworkView.ALL || view == NetworkView.PACKETS) {
			title(source, "Packet types");
			for (int i = 0; i < Math.min(limit, snapshot.packetTypes.size()); i++) {
				NetworkSnapshot.PacketEntry entry = snapshot.packetTypes.get(i);
				rankLine(source, i, Component.literal(entry.id()).withStyle(ChatFormatting.WHITE), snapshot, entry.wireBytes());
			}
		}
		if (view == NetworkView.ALL || view == NetworkView.CHUNKS) {
			title(source, "Chunks");
			if (snapshot.chunks.isEmpty()) {
				source.sendSuccess(() -> Component.literal("  no data").withStyle(ChatFormatting.GRAY), false);
			}
			for (int i = 0; i < Math.min(limit, snapshot.chunks.size()); i++) {
				NetworkSnapshot.ChunkEntry entry = snapshot.chunks.get(i);
				String dimension = entry.dimension().identifier().toString();
				ServerLevel level = source.getServer().getLevel(entry.dimension());
				// Go where the traffic happens; the chunk center can be outside a small farm or underground.
				NetworkSnapshot.Spot spot = entry.spot();
				int x = spot != null ? spot.x() : entry.x() * 16 + 8;
				int z = spot != null ? spot.z() : entry.z() * 16 + 8;
				// Reading blocks of an unloaded chunk would load it; keep the player's height then.
				String y;
				if (level == null || !level.hasChunk(entry.x(), entry.z())) {
					y = spot != null ? Integer.toString(spot.y()) : "~";
				} else if (spot != null) {
					y = Integer.toString(landingY(level, new BlockPos(x, spot.y(), z)));
				} else {
					y = Integer.toString(level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
				}
				String top = entry.topSource() == null ? "" : "\nMostly: " + entry.topSource().label();
				MutableComponent name = Component.literal(dimension + " " + x + " " + z).withStyle(style -> style
					.withColor(ChatFormatting.WHITE)
					.withClickEvent(MinecraftCompat.runCommand("/execute in " + dimension + " run tp @s " + x + " " + y + " " + z))
					.withHoverEvent(MinecraftCompat.showText(Component.literal("Chunk " + entry.x() + ", " + entry.z() + top
						+ "\nClick to teleport")))
					.withUnderlined(true));
				rankLine(source, i, name, snapshot, entry.wireBytes());
			}
		}
	}

	private static String kindTag(NetworkSource.Kind kind) {
		return switch (kind) {
			case ENTITY -> "entity ";
			case BLOCK_ENTITY -> "block entity ";
			case BLOCK -> "block ";
			case CHANNEL -> "channel ";
			case SOUND -> "sound ";
			case PARTICLE -> "particle ";
			case WORLD -> "world ";
			case OTHER -> "packet ";
		};
	}

	private static void title(CommandSourceStack source, String title) {
		source.sendSuccess(() -> Component.literal(title).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), false);
	}

	private static void rankLine(CommandSourceStack source, int index, MutableComponent name, NetworkSnapshot snapshot, long wire) {
		double percent = snapshot.percent(wire);
		source.sendSuccess(() -> Component.literal(" " + (index + 1) + ". ").withStyle(ChatFormatting.GRAY)
			.append(name)
			.append(Component.literal("  " + NetworkSnapshot.formatBytes(snapshot.perSecond(wire)) + "/s")
				.withStyle(percent >= 20 ? ChatFormatting.RED : percent >= 5 ? ChatFormatting.YELLOW : ChatFormatting.GREEN))
			.append(Component.literal(" (" + format(percent) + "%)").withStyle(ChatFormatting.GRAY)), false);
	}

	private static void writeNetworkReport(MinecraftServer server, CommandSourceStack source, NetworkSnapshot snapshot,
		boolean inCommand) {
		afterIo(source, inCommand, NetworkHtmlReport.writeAsync(server, snapshot), (file, error) -> {
			if (error != null) {
				TickEngine.LOGGER.error("Could not write the network HTML report", error);
				source.sendFailure(Component.literal("Could not write the HTML report, see the server log."));
			} else {
				sendReportSaved(server, source, file);
			}
		});
	}

	// --- report ---

	/** Bare /tickengine report: what the problem is about, as clickable choices. */
	private static int reportMenu(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		source.sendSuccess(() -> header("Report a problem"), false);
		source.sendSuccess(() -> Component.literal(" What is the problem about? Click one:").withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.literal(" ")
			.append(reportChoice("General", "/tickengine report general", true, "Nothing in particular: lag, a crash, odd behaviour"))
			.append(Component.literal("  "))
			.append(reportChoice("Block entity", "/tickengine report blockentity ", false, "A machine, chest, hopper..."))
			.append(Component.literal("  "))
			.append(reportChoice("Entity", "/tickengine report entity ", false, "A mob, item, vehicle..."))
			.append(Component.literal("  "))
			.append(reportChoice("Mod", "/tickengine report mod ", false, "Everything from one mod")), false);
		source.sendSuccess(() -> Component.literal(" For the last three, finish the command with the name (Tab lists them). "
			+ "TickEngine collects versions, its settings, the rule for that target, profiler numbers and the mod list, and "
			+ "gives you a link that opens a GitHub issue to review and submit. It sends nothing itself.")
			.withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	private static MutableComponent reportChoice(String label, String command, boolean runNow, String hint) {
		return Component.literal("[" + label + "]").withStyle(style -> style
			.withColor(ChatFormatting.AQUA)
			.withClickEvent(runNow ? MinecraftCompat.runCommand(command) : MinecraftCompat.suggestCommand(command))
			.withHoverEvent(MinecraftCompat.showText(Component.literal(hint))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> reportKind(DiagnosticReport.Kind kind) {
		return Commands.literal(kind.word)
			.then(Commands.argument("target", StringArgumentType.greedyString())
				.suggests((context, builder) -> SharedSuggestionProvider.suggest(DiagnosticReport.suggestions(kind), builder))
				.executes(context -> report(context, kind, StringArgumentType.getString(context, "target").trim())));
	}

	private static int report(CommandContext<CommandSourceStack> context, DiagnosticReport.Kind kind, String target) {
		CommandSourceStack source = context.getSource();
		MinecraftServer server = source.getServer();
		String error = DiagnosticReport.check(kind, target);
		if (error != null) {
			source.sendFailure(Component.literal(error));
			return 0;
		}
		DiagnosticReport.Result result = DiagnosticReport.build(server, kind, target);
		afterIo(source, true, DiagnosticReport.writeAsync(server, result), (file, writeError) -> {
			String path = null;
			if (writeError != null) {
				TickEngine.LOGGER.error("Could not write the diagnostic report", writeError);
			} else {
				path = server.getServerDirectory().toAbsolutePath().relativize(file.toAbsolutePath()).toString();
			}
			sendReport(source, result, path);
		});
		return 1;
	}

	/** The link for players, who can click it; consoles get the file and the address of the new-issue page. */
	private static void sendReport(CommandSourceStack source, DiagnosticReport.Result result, String path) {
		source.sendSuccess(() -> header("Report ready: " + result.title().substring("[Report] ".length())), false);
		source.sendSuccess(() -> Component.literal(" It holds versions, TickEngine's settings, the rule for the target, profiler "
			+ "numbers and the mod list. No player names, addresses or coordinates. TickEngine sends nothing: you review "
			+ "and submit it yourself.").withStyle(ChatFormatting.GRAY), false);
		boolean player = source.getPlayer() != null;
		if (player) {
			source.sendSuccess(() -> Component.literal(" 1. ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal("[Open the GitHub issue]").withStyle(style -> style
					.withColor(ChatFormatting.AQUA)
					.withUnderlined(true)
					.withClickEvent(MinecraftCompat.openUrl(result.issueUrl()))
					.withHoverEvent(MinecraftCompat.showText(Component.literal(
						"Opens a draft on GitHub in your browser. Nothing is posted until you press Submit.")))))
				.append(Component.literal(" and describe what is going wrong.").withStyle(ChatFormatting.GRAY)), false);
		} else {
			source.sendSuccess(() -> Component.literal(" 1. Open " + result.shortUrl()
				+ " in a browser, describe the problem and paste the file below into it.").withStyle(ChatFormatting.GRAY), false);
		}
		if (path != null) {
			// The console link has only the title, so there the file is the whole report.
			source.sendSuccess(() -> Component.literal(" 2. " + (!player ? "Attach or paste " : result.trimmed() ? "Attach "
				: "Attach (optional, has more detail) ") + "the file ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(path).withStyle(ChatFormatting.WHITE)), false);
			if (player && result.trimmed()) {
				source.sendSuccess(() -> Component.literal(" The link had to leave some lines out to stay short; the file has them all.")
					.withStyle(ChatFormatting.YELLOW), false);
			}
		} else {
			source.sendFailure(Component.literal("Could not write the report file, see the server log."
				+ (player && result.trimmed() ? " The link is missing some lines." : "")));
		}
	}

	// --- config ---

	private static int configList(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		source.sendSuccess(() -> header("TickEngine config"), false);
		TickEngineConfig.options().forEach((key, option) ->
			source.sendSuccess(() -> line(key, option.format(), ChatFormatting.WHITE)
				.withStyle(style -> style
					.withHoverEvent(MinecraftCompat.showText(Component.literal(option.comment)))
					.withClickEvent(MinecraftCompat.suggestCommand("/tickengine config set " + key + " "))), false));
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
		if (option.confirmCommand() != null && !option.format().equals(option.safeValue())) {
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
		ReportWebServer.refresh(source.getServer());
		source.sendSuccess(() -> Component.literal("Set ").withStyle(ChatFormatting.GREEN)
			.append(Component.literal(option.key).withStyle(ChatFormatting.WHITE))
			.append(Component.literal(" to ").withStyle(ChatFormatting.GREEN))
			.append(Component.literal(option.format()).withStyle(ChatFormatting.WHITE))
			.append(Component.literal(" and saved to the config file.").withStyle(ChatFormatting.GREEN)), true);
		return 1;
	}

	private static int configReload(CommandContext<CommandSourceStack> context) {
		boolean spreadBefore = TickEngineConfig.spreadEntityTicks;
		boolean loaded = TickEngineConfig.load();
		ServerCoreCompat.refresh();
		ReportWebServer.refresh(context.getSource().getServer());
		if (!loaded) {
			context.getSource().sendFailure(Component.literal("The config file has a syntax error and was not loaded;"
				+ " the previous settings stay in use. See the server log for the position."));
			return 0;
		}
		context.getSource().sendSuccess(() -> Component.literal("TickEngine config reloaded.")
			.withStyle(ChatFormatting.GREEN), true);
		if (!spreadBefore && TickEngineConfig.spreadEntityTicks) {
			// Same as '/tickengine spread confirm': entities already loaded are spread now, not on their next load.
			int moved = EntityThrottle.spreadLoadedEntities(context.getSource().getServer());
			TickEngine.LOGGER.info("Tick spreading turned on by a config reload; {} loaded entities moved.", moved);
			context.getSource().sendSuccess(() -> Component.literal("Tick spreading was turned on in the file; " + moved
				+ " loaded entities were moved.").withStyle(ChatFormatting.GRAY), false);
		}
		String inconsistency = TickEngineConfig.checkConsistency();
		if (inconsistency != null) {
			context.getSource().sendSuccess(() -> Component.literal(inconsistency).withStyle(ChatFormatting.YELLOW), false);
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
