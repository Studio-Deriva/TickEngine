package com.studioderiva.tickengine.report;

import com.studioderiva.tickengine.TickEngine;
import com.studioderiva.tickengine.command.TickEngineCommand;
import com.studioderiva.tickengine.compat.MinecraftCompat;
import com.studioderiva.tickengine.compat.ServerCoreCompat;
import com.studioderiva.tickengine.config.ThrottleRules;
import com.studioderiva.tickengine.config.TickEngineConfig;
import com.studioderiva.tickengine.notice.AdminNotices;
import com.studioderiva.tickengine.profiler.ProfileSnapshot;
import com.studioderiva.tickengine.profiler.TickProfiler;
import com.studioderiva.tickengine.stacking.EntityStacker;
import com.studioderiva.tickengine.throttle.BlockEntityThrottle;
import com.studioderiva.tickengine.throttle.EntityThrottle;
import com.studioderiva.tickengine.tuning.AutoTuner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalEntityTypeTags;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * The text of a problem report, and the GitHub link that opens it as a new issue.
 *
 * <p>Nothing is sent from the server: the report is shown to the admin as a link that opens a
 * prefilled draft in their browser, where they describe the problem and press Submit. The same
 * report, without the length limit of a link, is written to a text file to attach to the issue.
 *
 * <p>The report holds versions, TickEngine's settings, the rule that applies to the target, profiler
 * numbers and the mod list. It never holds player names, addresses or coordinates; the only path in it
 * is the output.reportDirectory setting, which is relative to the server folder.
 */
public final class DiagnosticReport {
	/** What the problem is about. */
	public enum Kind {
		GENERAL("general"),
		BLOCK_ENTITY("blockentity"),
		ENTITY("entity"),
		MOD("mod");

		public final String word;

		Kind(String word) {
			this.word = word;
		}
	}

	/**
	 * @param title    issue title
	 * @param fileName name of the file written by {@link #writeAsync}
	 * @param fileText whole report, for the file
	 * @param issueUrl link with the title and the report filled in; the report is shortened to fit
	 * @param shortUrl link with only the title, for consoles where a long link cannot be clicked
	 * @param trimmed  whether lines were left out of the link to fit
	 */
	public record Result(String title, String fileName, String fileText, String issueUrl, String shortUrl, boolean trimmed) {
	}

	/** A heading with lines under it. Code sections are fenced; lines of trimmable ones can be dropped to fit a link. */
	record Section(String heading, List<String> lines, boolean code, boolean trimmable, int hidden) {
		static Section list(String heading, List<String> lines) {
			return new Section(heading, lines, false, false, 0);
		}

		static Section code(String heading, List<String> lines) {
			return new Section(heading, lines, true, true, 0);
		}

		Section withoutLastLine() {
			return new Section(heading, lines.subList(0, lines.size() - 1), code, trimmable, hidden + 1);
		}

		String render() {
			StringBuilder out = new StringBuilder("### ").append(heading).append('\n');
			if (code) {
				out.append("```\n");
			}
			lines.forEach(line -> out.append(line).append('\n'));
			if (hidden > 0) {
				out.append(code ? "... " : "- ").append(hidden).append(" more in the attached file\n");
			}
			if (code) {
				out.append("```\n");
			}
			return out.toString();
		}
	}

	private static final String ISSUES_URL = "https://github.com/Studio-Deriva/TickEngine/issues/new";
	/**
	 * GitHub rejects very long links. Signed out, it sends the browser to its login page with the whole
	 * link encoded again in return_to, which makes it about a quarter longer; on 2026-10-30 that page
	 * answered 500 from links of about 5700 characters (5575 still worked). This leaves room under that.
	 * The limit when signed in was not checked.
	 */
	static final int MAX_URL_LENGTH = 5000;
	private static final int TOP_ROWS = 5;
	// Milliseconds, so two reports in the same second do not overwrite each other's file.
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")
		.withZone(ZoneId.systemDefault());

	private DiagnosticReport() {
	}

	// --- targets ---

	/** Returns an error message when the target does not fit the kind, or null when it is valid. */
	public static String check(Kind kind, String target) {
		if (kind == Kind.GENERAL) {
			return null;
		}
		if (kind == Kind.MOD) {
			return Identifier.isValidNamespace(target) && (FabricLoader.getInstance().isModLoaded(target) || registersAnything(target))
				? null : "'" + target + "' is not a loaded mod. Use tab completion to list them.";
		}
		Identifier id = Identifier.tryParse(target);
		boolean registered = id != null && (kind == Kind.ENTITY ? BuiltInRegistries.ENTITY_TYPE.containsKey(id)
			: BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(id));
		return registered ? null : "'" + target + "' is not a registered "
			+ (kind == Kind.ENTITY ? "entity" : "block entity") + " type. Use tab completion to list them.";
	}

	/** What can be typed after the kind. */
	public static Stream<String> suggestions(Kind kind) {
		return switch (kind) {
			case GENERAL -> Stream.empty();
			case ENTITY -> BuiltInRegistries.ENTITY_TYPE.keySet().stream().map(Identifier::toString);
			case BLOCK_ENTITY -> BuiltInRegistries.BLOCK_ENTITY_TYPE.keySet().stream().map(Identifier::toString);
			case MOD -> Stream.concat(
				Stream.concat(BuiltInRegistries.ENTITY_TYPE.keySet().stream(), BuiltInRegistries.BLOCK_ENTITY_TYPE.keySet().stream())
					.map(Identifier::getNamespace),
				loadedMods().map(mod -> mod.getMetadata().getId())).distinct();
		};
	}

	private static boolean registersAnything(String namespace) {
		return BuiltInRegistries.ENTITY_TYPE.keySet().stream().anyMatch(id -> id.getNamespace().equals(namespace))
			|| BuiltInRegistries.BLOCK_ENTITY_TYPE.keySet().stream().anyMatch(id -> id.getNamespace().equals(namespace));
	}

	// --- building ---

	/** Builds the report. Server thread only; the target must have passed {@link #check}. */
	public static Result build(MinecraftServer server, Kind kind, String target) {
		ProfileSnapshot snapshot = TickProfiler.lightSnapshot();
		String fileName = "diagnostic-" + FILE_TIME.format(Instant.now()) + ".txt";
		String title = kind == Kind.GENERAL ? "[Report] general problem" : "[Report] " + kind.word + " " + target;

		List<Section> sections = new ArrayList<>();
		sections.add(environment());
		if (kind != Kind.GENERAL) {
			sections.add(targetDetails(server, snapshot, kind, target));
		}
		sections.add(serverState(server, snapshot));
		if (kind == Kind.GENERAL) {
			sections.add(heaviest(snapshot));
		}
		sections.add(changedSettings());
		sections.add(mods());

		List<Section> fileSections = new ArrayList<>(sections);
		fileSections.add(allSettings());
		fileSections.add(allRules());
		if (kind != Kind.GENERAL) {
			fileSections.add(heaviest(snapshot));
		}
		StringBuilder file = new StringBuilder("# TickEngine diagnostic report\n\n")
			.append("Open a new issue at ").append(ISSUES_URL).append(", describe the problem and attach or paste this report.\n\n")
			.append("Subject: ").append(title).append("\n\n");
		fileSections.forEach(section -> file.append(section.render()).append('\n'));

		String footer = "---\n_Made by TickEngine's `/tickengine report`. No player names, addresses or coordinates are included._\n"
			+ "Full report: attach `" + TickEngineConfig.reportDirectory + "/" + fileName + "` to this issue "
			+ "(drag it into this box).\n";
		Fit fit = fit(title, sections, footer);
		return new Result(title, fileName, file.toString(), fit.url(), titleUrl(title), fit.trimmed());
	}

	/** A link that fits, and whether lines had to be left out for that. */
	record Fit(String url, boolean trimmed) {
	}

	/**
	 * Drops lines from the end of the last trimmable section (the mod list first) until the link is short
	 * enough. When the sections that cannot be trimmed are too long on their own, the link carries only
	 * the title and the whole report is left to the file.
	 */
	static Fit fit(String title, List<Section> sections, String footer) {
		List<Section> shown = new ArrayList<>(sections);
		String url = issueUrl(title, body(shown, footer));
		boolean trimmed = false;
		while (url.length() > MAX_URL_LENGTH) {
			int last = lastTrimmable(shown);
			if (last < 0) {
				return new Fit(titleUrl(title), true);
			}
			shown.set(last, shown.get(last).withoutLastLine());
			trimmed = true;
			url = issueUrl(title, body(shown, footer));
		}
		return new Fit(url, trimmed);
	}

	private static String body(List<Section> sections, String footer) {
		StringBuilder body = new StringBuilder("### What is going wrong?\n")
			.append("<!-- Describe the problem: what you saw, what you expected, since when, and how to make it happen. -->\n\n");
		sections.forEach(section -> body.append(section.render()).append('\n'));
		return body.append(footer).toString();
	}

	private static int lastTrimmable(List<Section> sections) {
		for (int i = sections.size() - 1; i >= 0; i--) {
			if (sections.get(i).trimmable() && !sections.get(i).lines().isEmpty()) {
				return i;
			}
		}
		return -1;
	}

	private static String issueUrl(String title, String body) {
		return titleUrl(title) + "&body=" + encode(body);
	}

	private static String titleUrl(String title) {
		return ISSUES_URL + "?title=" + encode(title);
	}

	private static String encode(String text) {
		// URLEncoder writes a space as '+', which not every reader of a link takes as a space; a real '+' stays %2B.
		return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
	}

	// --- sections ---

	private static Section environment() {
		List<String> lines = new ArrayList<>();
		lines.add("- TickEngine: " + modVersion(TickEngine.MOD_ID));
		lines.add("- Minecraft: " + modVersion("minecraft"));
		lines.add("- Fabric Loader: " + modVersion("fabricloader"));
		lines.add("- Fabric API: " + modVersion("fabric-api"));
		lines.add("- Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
		lines.add("- System: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
		return Section.list("Environment", lines);
	}

	private static Section serverState(MinecraftServer server, ProfileSnapshot snapshot) {
		List<String> lines = new ArrayList<>();
		double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
		lines.add("- MSPT: " + format(mspt) + " ms (TPS " + format(Math.min(20.0, 1000.0 / Math.max(mspt, 0.001))) + ")");
		lines.add("- Players online: " + server.getPlayerCount() + ", simulation distance "
			+ server.getPlayerList().getSimulationDistance() + ", view distance " + server.getPlayerList().getViewDistance());
		for (ServerLevel level : server.getAllLevels()) {
			lines.add("- " + level.dimension().identifier() + ": " + MinecraftCompat.forceLoadedChunks(level).size()
				+ " force-loaded chunks");
		}
		lines.add("- Block entity throttling: " + (TickEngineConfig.blockEntityThrottle ? "on, last second ticked "
			+ BlockEntityThrottle.tickedLastSecond() + ", skipped " + BlockEntityThrottle.skippedLastSecond() : "off"));
		lines.add("- Entity throttling: " + (EntityThrottle.isActive() ? "on, last second " + EntityThrottle.fullLastSecond()
			+ " full, " + EntityThrottle.aiSkippedLastSecond() + " without AI, " + EntityThrottle.minimalLastSecond() + " minimal"
			: TickEngineConfig.entityThrottle ? "paused (ServerCore activation range)" : "off"));
		lines.add("- Tick spreading: " + (TickEngineConfig.spreadEntityTicks ? "on" : "off"));
		lines.add("- Auto-tuning: " + TickEngineCommand.tuningSummary(server));
		lines.add("- Emergency mode: " + TickEngineCommand.emergencySummary());
		int[] stacks = EntityStacker.loadedStacks(server);
		lines.add("- Entity stacking: mode " + TickEngineConfig.stackingMode + (EntityStacker.isActive() ? ", running" : "")
			+ ", " + stacks[0] + " loaded stacks holding " + stacks[1] + " mobs");
		AutoTuner.Values values = AutoTuner.values();
		lines.add("- Effective now: block entities every " + values.blockEntityInterval() + " ticks beyond " + values.blockEntityRadius()
			+ " chunks; entities AI every " + values.entityAiInterval() + ", far tick every " + values.entityTickInterval()
			+ ", full radius " + values.entityFullRadius() + ", AI radius " + values.entityAiRadius()
			+ "; force-loaded chunks at most every " + values.blockEntityForcedMax() + " / " + values.entityForcedMax());
		if (ServerCoreCompat.installed()) {
			lines.add("- ServerCore: activation range " + (ServerCoreCompat.activationRangeEnabled() ? "on" : "off")
				+ ", dynamic settings " + (ServerCoreCompat.dynamicSettingsEnabled() ? "on" : "off")
				+ (ServerCoreCompat.assumed() ? " (assumed: its config could not be read)" : ""));
		}
		lines.add("- Profiler data: " + (snapshot == null ? "none" : snapshot.label + ", " + snapshot.sampledTicks
			+ " ticks sampled, average " + format(snapshot.averageTickMs()) + " ms"));
		AdminNotices.problems().forEach(problem -> lines.add("- Notice: " + problem.getString()));
		return Section.list("Server state", lines);
	}

	private static Section targetDetails(MinecraftServer server, ProfileSnapshot snapshot, Kind kind, String target) {
		List<String> lines = new ArrayList<>();
		switch (kind) {
			case BLOCK_ENTITY -> blockEntityDetails(lines, server, snapshot, Identifier.parse(target));
			case ENTITY -> entityDetails(lines, server, snapshot, Identifier.parse(target));
			case MOD -> modDetails(lines, snapshot, target);
			default -> {
			}
		}
		return Section.list("About " + kind.word + " " + target, lines);
	}

	private static void blockEntityDetails(List<String> lines, MinecraftServer server, ProfileSnapshot snapshot, Identifier id) {
		lines.add("- Type: " + id + " (" + modName(id.getNamespace()) + ")");
		if (!TickEngineConfig.blockEntityThrottle) {
			lines.add("- Block entity throttling is off, so this type always ticks normally");
		} else {
			AutoTuner.Values values = AutoTuner.values();
			ruleLines(lines, server, TickEngineConfig.BLOCK_ENTITY_RULES, id, TickEngineConfig.dimensionRulesEnabled,
				values.blockEntityInterval(), "Interval beyond " + values.blockEntityRadius() + " chunks from a player");
			lines.add("- Within that radius, and in loaded chunks of players, it always ticks; in force-loaded chunks it ticks at least every "
				+ values.blockEntityForcedMax() + " ticks");
		}
		lines.add("- " + profilerLine(snapshot, ProfileSnapshot.Category.BLOCK_ENTITY, id.toString()));
	}

	private static void entityDetails(List<String> lines, MinecraftServer server, ProfileSnapshot snapshot, Identifier id) {
		lines.add("- Type: " + id + " (" + modName(id.getNamespace()) + ")");
		if (!EntityThrottle.isActive()) {
			lines.add("- Entity throttling is " + (TickEngineConfig.entityThrottle ? "paused (ServerCore activation range)" : "off")
				+ ", so this type ticks normally");
		} else {
			AutoTuner.Values values = AutoTuner.values();
			ruleLines(lines, server, TickEngineConfig.ENTITY_RULES, id, TickEngineConfig.entityDimensionRulesEnabled,
				new ThrottleRules.EntityIntervals(values.entityAiInterval(), values.entityTickInterval()),
				"Intervals (AI zone " + values.entityFullRadius() + "-" + values.entityAiRadius() + " chunks, far beyond)");
			EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
			if (type != null && TickEngineConfig.excludeBosses && type.is(ConventionalEntityTypeTags.BOSSES)) {
				lines.add("- Tagged as a boss and entities.excludeBosses is on: never throttled");
			}
			ThrottleRules.EntityIntervals defaults = new ThrottleRules.EntityIntervals(values.entityAiInterval(),
				values.entityTickInterval());
			if (!TickEngineConfig.entityDimensionRulesEnabled) {
				lines.add("- Tick spreading: " + spreadLine(id, type, defaults, Level.OVERWORLD.identifier(), false));
			} else {
				for (ServerLevel level : server.getAllLevels()) {
					Identifier dimension = level.dimension().identifier();
					lines.add("- Tick spreading in " + dimension + ": " + spreadLine(id, type, defaults, dimension, true));
				}
			}
		}
		lines.add("- " + profilerLine(snapshot, ProfileSnapshot.Category.ENTITY, id.toString()));
	}

	/** Mirrors EntityThrottle.canSpread, which works on an entity; this works on the type (projectiles are not known by type). */
	private static String spreadLine(Identifier id, EntityType<?> type, ThrottleRules.EntityIntervals defaults,
		Identifier dimension, boolean dimensionRules) {
		if (!TickEngineConfig.spreadEntityTicks) {
			return "off";
		}
		if (TickEngineConfig.SPREAD_EXCLUDE.matches(id)) {
			return "on, but this type is in entities.spreadExclude";
		}
		if (type != null && TickEngineConfig.excludeBosses && type.is(ConventionalEntityTypeTags.BOSSES)) {
			return "on, but not applied: bosses are never throttled";
		}
		ThrottleRules.EntityIntervals rule = TickEngineConfig.ENTITY_RULES.resolve(id, dimension, dimensionRules, defaults);
		return rule.ai() > 1 || rule.tick() > 1 ? "on, applies to this type" : "on, but not applied: this type is never throttled";
	}

	private static void modDetails(List<String> lines, ProfileSnapshot snapshot, String namespace) {
		lines.add("- Mod: " + modName(namespace) + (FabricLoader.getInstance().isModLoaded(namespace)
			? " " + modVersion(namespace) : " (no loaded mod has this id; only a registry namespace)"));
		long entityTypes = BuiltInRegistries.ENTITY_TYPE.keySet().stream().filter(id -> id.getNamespace().equals(namespace)).count();
		long blockEntityTypes = BuiltInRegistries.BLOCK_ENTITY_TYPE.keySet().stream().filter(id -> id.getNamespace().equals(namespace)).count();
		lines.add("- Registers " + entityTypes + " entity types and " + blockEntityTypes + " block entity types");
		int before = lines.size();
		modRules(lines, "Block entity", TickEngineConfig.BLOCK_ENTITY_RULES, namespace, TickEngineConfig.dimensionRulesEnabled);
		modRules(lines, "Entity", TickEngineConfig.ENTITY_RULES, namespace, TickEngineConfig.entityDimensionRulesEnabled);
		TickEngineConfig.SPREAD_EXCLUDE.targets().stream().filter(target -> target.startsWith(namespace + ":"))
			.forEach(target -> lines.add("- Excluded from tick spreading: " + target));
		if (lines.size() == before) {
			lines.add("- No rule names this mod: the defaults apply");
		}
		if (snapshot == null) {
			lines.add("- Profiler: no data (light profiler is off or has not sampled yet)");
			return;
		}
		snapshot.mods().stream().filter(mod -> mod.namespace().equals(namespace)).findFirst().ifPresentOrElse(
			mod -> lines.add("- Profiler: " + format(snapshot.msPerTick(mod.totalNanos())) + " ms/tick ("
				+ format(snapshot.percentOfTick(mod.totalNanos())) + "% of the sampled tick) over " + mod.types() + " types"),
			() -> lines.add("- Profiler: nothing from this mod ticked in the last window"));
		for (ProfileSnapshot.Category category : ProfileSnapshot.Category.values()) {
			snapshot.entries(category).stream().filter(entry -> entry.namespace().equals(namespace)).limit(TOP_ROWS)
				.forEach(entry -> lines.add("  - " + entry.id() + ": " + format(snapshot.msPerTick(entry.totalNanos()))
					+ " ms/tick, " + format(entry.calls() / (double) snapshot.sampledTicks) + " calls/tick"));
		}
	}

	/** The rule that applies to a type, in each dimension when dimension rules are on. */
	private static <V> void ruleLines(List<String> lines, MinecraftServer server, ThrottleRules<V> rules, Identifier id,
		boolean dimensionRules, V defaultValue, String label) {
		if (!dimensionRules) {
			lines.add("- " + label + ": " + describe(rules, rules.explain(id, Level.OVERWORLD.identifier(), false), defaultValue));
			return;
		}
		for (ServerLevel level : server.getAllLevels()) {
			Identifier dimension = level.dimension().identifier();
			lines.add("- " + label + " in " + dimension + ": " + describe(rules, rules.explain(id, dimension, true), defaultValue));
		}
	}

	private static <V> String describe(ThrottleRules<V> rules, ThrottleRules.Match<V> match, V defaultValue) {
		return match == null ? rules.type().describe(defaultValue) + " (default)"
			: rules.type().describe(match.value()) + " (" + match.source() + ")";
	}

	private static <V> void modRules(List<String> lines, String label, ThrottleRules<V> rules, String namespace,
		boolean dimensionRulesEnabled) {
		String prefix = namespace + ":";
		rules.rules().forEach((target, value) -> {
			if (target.startsWith(prefix)) {
				lines.add("- " + label + " rule " + target + ": " + rules.type().describe(value));
			}
		});
		rules.dimensionRules().forEach((dimension, map) -> map.forEach((target, value) -> {
			if (target.startsWith(prefix) || target.equals(ThrottleRules.ALL)) {
				lines.add("- " + label + " rule " + target + " in " + dimension + ": " + rules.type().describe(value)
					+ (dimensionRulesEnabled ? "" : " (not used: dimension rules are off)"));
			}
		}));
	}

	private static String profilerLine(ProfileSnapshot snapshot, ProfileSnapshot.Category category, String id) {
		if (snapshot == null) {
			return "Profiler: no data (light profiler is off or has not sampled yet)";
		}
		List<ProfileSnapshot.Entry> entries = snapshot.entries(category);
		for (int i = 0; i < entries.size(); i++) {
			ProfileSnapshot.Entry entry = entries.get(i);
			if (entry.id().equals(id)) {
				return "Profiler: " + format(snapshot.msPerTick(entry.totalNanos())) + " ms/tick ("
					+ format(snapshot.percentOfTick(entry.totalNanos())) + "% of the sampled tick), "
					+ format(entry.calls() / (double) snapshot.sampledTicks) + " calls/tick, rank " + (i + 1) + " of "
					+ entries.size() + " " + category.displayName.toLowerCase(Locale.ROOT) + " types";
			}
		}
		return "Profiler: this type did not tick in the last window";
	}

	private static Section heaviest(ProfileSnapshot snapshot) {
		List<String> lines = new ArrayList<>();
		if (snapshot == null) {
			lines.add("- no profiler data");
			return Section.list("Heaviest in the last profiler window", lines);
		}
		lines.add("- Mods:");
		snapshot.mods().stream().limit(TOP_ROWS).forEach(mod -> lines.add("  - " + mod.displayName() + ": "
			+ format(snapshot.msPerTick(mod.totalNanos())) + " ms/tick"));
		for (ProfileSnapshot.Category category : ProfileSnapshot.Category.values()) {
			lines.add("- " + category.displayName + " types:");
			snapshot.entries(category).stream().limit(TOP_ROWS).forEach(entry -> lines.add("  - " + entry.id() + ": "
				+ format(snapshot.msPerTick(entry.totalNanos())) + " ms/tick, "
				+ format(entry.calls() / (double) snapshot.sampledTicks) + " calls/tick"));
		}
		return Section.list("Heaviest in the last profiler window", lines);
	}

	private static Section changedSettings() {
		List<String> lines = new ArrayList<>();
		TickEngineConfig.options().values().stream().filter(option -> !option.isDefault())
			.forEach(option -> lines.add(option.key + " = " + option.format() + " (default " + option.defaultText() + ")"));
		if (lines.isEmpty()) {
			lines.add("all settings have their default value");
		}
		return Section.code("Settings changed from the defaults", lines);
	}

	private static Section allSettings() {
		List<String> lines = new ArrayList<>();
		TickEngineConfig.options().values().forEach(option -> lines.add(option.key + " = " + option.format()));
		return Section.code("All settings", lines);
	}

	private static Section allRules() {
		List<String> lines = new ArrayList<>();
		ruleDump(lines, "blockEntities", TickEngineConfig.BLOCK_ENTITY_RULES);
		ruleDump(lines, "entities", TickEngineConfig.ENTITY_RULES);
		lines.add("entities.spreadExclude = " + TickEngineConfig.SPREAD_EXCLUDE.targets());
		return Section.code("Rules", lines);
	}

	private static <V> void ruleDump(List<String> lines, String section, ThrottleRules<V> rules) {
		rules.rules().forEach((target, value) -> lines.add(section + " " + target + ": " + rules.type().describe(value)));
		rules.dimensionRules().forEach((dimension, map) -> map.forEach((target, value) ->
			lines.add(section + " " + target + " in " + dimension + ": " + rules.type().describe(value))));
	}

	private static Section mods() {
		List<String> lines = loadedMods()
			.sorted(Comparator.comparing(mod -> mod.getMetadata().getId()))
			.map(mod -> mod.getMetadata().getId() + " " + mod.getMetadata().getVersion().getFriendlyString())
			.toList();
		return Section.code("Mods (" + lines.size() + ")", new ArrayList<>(lines));
	}

	/** Mods the admin installed: not the ones bundled inside another mod, nor the platform itself. */
	private static Stream<ModContainer> loadedMods() {
		return FabricLoader.getInstance().getAllMods().stream()
			.filter(mod -> mod.getContainingMod().isEmpty())
			.filter(mod -> !List.of("java", "minecraft", "fabricloader").contains(mod.getMetadata().getId()));
	}

	private static String modVersion(String id) {
		return FabricLoader.getInstance().getModContainer(id)
			.map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("not installed");
	}

	private static String modName(String namespace) {
		return FabricLoader.getInstance().getModContainer(namespace)
			.map(mod -> mod.getMetadata().getName()).orElse(namespace);
	}

	private static String format(double value) {
		return String.format(Locale.ROOT, value >= 100 ? "%.0f" : value >= 10 ? "%.1f" : "%.2f", value);
	}

	// --- file ---

	/**
	 * Writes the full report in the report folder on the IO pool, so a slow disk never stalls a tick.
	 * The future completes off the server thread.
	 */
	public static CompletableFuture<Path> writeAsync(MinecraftServer server, Result result) {
		Path serverDirectory = server.getServerDirectory().toAbsolutePath().normalize();
		Path directory = serverDirectory.resolve(TickEngineConfig.reportDirectory).normalize();
		if (!directory.startsWith(serverDirectory)) {
			// The config already rejects such paths; this guards against anything that slips through.
			return CompletableFuture.failedFuture(new IOException("Report directory is outside the server folder: "
				+ TickEngineConfig.reportDirectory));
		}
		Path file = directory.resolve(result.fileName());
		return CompletableFuture.supplyAsync(() -> {
			try {
				Files.createDirectories(directory);
				Files.writeString(file, result.fileText(), StandardCharsets.UTF_8);
				return file;
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}, Util.ioPool());
	}
}
