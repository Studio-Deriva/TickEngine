package com.studioderiva.tickengine.gametest;

import static com.studioderiva.tickengine.gametest.TestSupport.check;
import static com.studioderiva.tickengine.gametest.TestSupport.configure;
import static com.studioderiva.tickengine.gametest.TestSupport.litFurnace;
import static com.studioderiva.tickengine.gametest.TestSupport.run;
import static com.studioderiva.tickengine.gametest.TestSupport.spawnPlain;

import com.studioderiva.tickengine.TickEngine;
import com.studioderiva.tickengine.compat.ServerCoreCompat;
import com.studioderiva.tickengine.config.TickEngineConfig;
import com.studioderiva.tickengine.permission.TickEnginePermissions;
import com.studioderiva.tickengine.profiler.ProfileSnapshot;
import com.studioderiva.tickengine.profiler.TickProfiler;
import com.studioderiva.tickengine.tuning.AutoTuner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Profiler, HTML reports, commands, permissions, auto-tuning and the config file. */
@GameTestHolder(TickEngine.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ServerFeatureTests {
	private ServerFeatureTests() {
	}

	// --- profiler ---

	/** The timing mixins: block entity ticker, entity tick and passenger tick all reach the profiler. */
	@GameTest(template = "floor", batch = "profiler", timeoutTicks = 300)
	public static void profilerRecordsBlockEntitiesEntitiesAndPassengers(GameTestHelper helper) {
		configure(helper, "profiler.sampleEveryTicks", "1", "profiler.windowSeconds", "10");
		TickProfiler.reset();
		litFurnace(helper, new BlockPos(3, 2, 3));
		spawnPlain(helper, EntityType.COW, 8.5, 2, 8.5);
		Entity boat = spawnPlain(helper, EntityType.BOAT, 12.5, 2, 12.5);
		Entity chicken = spawnPlain(helper, EntityType.CHICKEN, 12.5, 2, 12.5);
		chicken.startRiding(boat, true);
		helper.runAfterDelay(40, () -> {
			ProfileSnapshot snapshot = TickProfiler.lightSnapshot();
			check(snapshot != null && snapshot.sampledTicks >= 30, "light profiler sampled too few ticks: "
				+ (snapshot == null ? "none" : snapshot.sampledTicks));
			check(has(snapshot, ProfileSnapshot.Category.BLOCK_ENTITY, "minecraft:furnace"), "furnace not profiled");
			check(has(snapshot, ProfileSnapshot.Category.ENTITY, "minecraft:cow"), "cow not profiled");
			check(has(snapshot, ProfileSnapshot.Category.ENTITY, "minecraft:boat"), "boat not profiled");
			check(chicken.isPassenger(), "chicken fell off the boat");
			check(has(snapshot, ProfileSnapshot.Category.ENTITY, "minecraft:chicken"), "passenger chicken not profiled");
			check(snapshot.mods().stream().anyMatch(mod -> mod.namespace().equals("minecraft")
				&& mod.displayName().equals("Minecraft")), "mod name not resolved through ModList: " + snapshot.mods());
			helper.succeed();
		});
	}

	private static boolean has(ProfileSnapshot snapshot, ProfileSnapshot.Category category, String id) {
		return snapshot.entries(category).stream().anyMatch(entry -> entry.id().equals(id) && entry.calls() > 0
			&& entry.worstPos() != null && entry.worstDimension() != null);
	}

	@GameTest(template = "floor", batch = "profiler_session", timeoutTicks = 400)
	public static void sessionWritesAnHtmlReport(GameTestHelper helper) {
		configure(helper);
		litFurnace(helper, new BlockPos(3, 2, 3));
		MinecraftServer server = helper.getLevel().getServer();
		Path reports = server.getServerDirectory().toPath().resolve(TickEngineConfig.reportDirectory);
		long before = countReports(reports);
		TestSupport.CommandResult start = run(helper, "/tickengine profile start 5");
		check(start.result() == 1 && TickProfiler.isSessionRunning(), "session did not start: " + start.text());
		check(run(helper, "/tickengine profile start 5").result() == 0, "a second session must be refused");
		helper.runAfterDelay(30, () -> {
			TestSupport.CommandResult stop = run(helper, "/tickengine profile stop");
			check(stop.result() == 1 && !TickProfiler.isSessionRunning(), "session did not stop: " + stop.text());
			check(start.text().contains("minecraft:furnace"), "session results not sent to the starter: " + start.text());
			check(run(helper, "/tickengine profile stop").result() == 0, "stopping twice must fail");
			helper.runAfterDelay(40, () -> {
				check(countReports(reports) == before + 1, "HTML report not written to " + reports);
				check(start.text().contains("HTML report saved"), "report path not sent: " + start.text());
				helper.succeed();
			});
		});
	}

	private static long countReports(Path directory) {
		if (!Files.isDirectory(directory)) {
			return 0;
		}
		try (Stream<Path> files = Files.list(directory)) {
			return files.filter(file -> file.getFileName().toString().endsWith(".html")).count();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	// --- commands ---

	@GameTest(template = "floor", batch = "commands", timeoutTicks = 100)
	public static void commandsWork(GameTestHelper helper) {
		configure(helper);
		TestSupport.CommandResult status = run(helper, "/tickengine status");
		check(status.result() == 1 && status.text().contains("MSPT"), "status: " + status.text());
		check(run(helper, "/tickengine").text().contains("MSPT"), "bare /tickengine shows the status to admins");
		check(run(helper, "/tickengine config list").text().contains("blockEntities.defaultInterval"), "config list");
		check(run(helper, "/tickengine config get entities.combatSeconds").text().contains("10"), "config get");

		check(run(helper, "/tickengine config set blockEntities.defaultInterval 5").result() == 1, "config set");
		check(TickEngineConfig.defaultInterval == 5, "config set did not change the value");
		check(run(helper, "/tickengine config set blockEntities.defaultInterval 500").result() == 0, "out of range accepted");
		check(run(helper, "/tickengine config set blockEntities.defaultInterval five").result() == 0, "text accepted");
		check(TickEngineConfig.defaultInterval == 5, "rejected value changed the setting");
		check(run(helper, "/tickengine config set nope.nope 1").result() == 0, "unknown key accepted");
		check(run(helper, "/tickengine config set autoTuning.lowerBelowMspt 60").result() == 0,
			"lowerBelowMspt above raiseAboveMspt accepted");
		check(TickEngineConfig.lowerBelowMspt == 30, "inconsistent value was kept");
		check(run(helper, "/tickengine config set output.reportDirectory ../../escape").result() == 0, "path escape accepted");
		check(run(helper, "/tickengine config set output.reportDirectory /tmp/escape").result() == 0, "absolute path accepted");
		check(run(helper, "/tickengine config set output.reportDirectory tickengine/../tickengine/reports").result() == 1,
			"normalised inner path refused");

		check(run(helper, "/tickengine rules blockentities set 8 minecraft:hopper").result() == 1, "rule set");
		check(run(helper, "/tickengine rules blockentities list").text().contains("minecraft:hopper"), "rule list");
		check(run(helper, "/tickengine rules blockentities set 8 hopper").result() == 0, "invalid target accepted");
		check(run(helper, "/tickengine rules blockentities remove minecraft:hopper").result() == 1, "rule remove");
		check(run(helper, "/tickengine rules blockentities remove minecraft:hopper").result() == 0, "double remove");
		check(run(helper, "/tickengine rules entities set-in minecraft:the_nether 2 8 minecraft:cow").text()
			.contains("disabled"), "dimension rule without dimensionRulesEnabled should warn");
		check(run(helper, "/tickengine rules entities remove-in minecraft:the_nether minecraft:cow").result() == 1,
			"dimension rule remove");

		TestSupport.CommandResult top = run(helper, "/tickengine top");
		check(top.result() == 1 || top.text().contains("No data yet"), "top: " + top.text());
		check(run(helper, "/tickengine tuning status").text().contains("Thresholds"), "tuning status");
		check(!ServerCoreCompat.installed() && !ServerCoreCompat.activationRangeEnabled(), "ServerCore detected without it");
		configure(helper);
		helper.succeed();
	}

	@GameTest(template = "floor", batch = "permissions", timeoutTicks = 100)
	public static void permissionsFollowOperatorLevel(GameTestHelper helper) {
		configure(helper);
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer player = TestSupport.playerAt(helper, 0, 0);
		check(!TickEnginePermissions.check(player, TickEnginePermissions.STATUS), "non-op player has tickengine.status");
		check(run(player.createCommandSourceStack(), "/tickengine status").result() == 0, "non-op ran /tickengine status");
		check(run(server.createCommandSourceStack().withPermission(2), "/tickengine status").result() == 0,
			"level 2 console source passed the level 3 check");

		// GameTestServer gives operators level 0, so granting op cannot be tested here: lower the
		// required level instead, which goes through the same default resolver.
		check(TestSupport.run(helper, "/tickengine config set permissions.commandOpLevel 0").result() == 1, "set op level 0");
		check(TickEnginePermissions.check(player, TickEnginePermissions.STATUS), "op level 0 should allow everyone");
		check(TickEnginePermissions.check(player, TickEnginePermissions.NOTIFY), "op level 0: tickengine.notify");
		check(run(player.createCommandSourceStack(), "/tickengine status").result() == 1, "player could not run /tickengine status");
		configure(helper);
		helper.succeed();
	}

	// --- auto-tuning ---

	@GameTest(template = "floor", batch = "tuning_manual", timeoutTicks = 100)
	public static void forcedLevelsChangeAndRestoreDistances(GameTestHelper helper) {
		configure(helper, "autoTuning.enabled", "true");
		MinecraftServer server = helper.getLevel().getServer();
		PlayerList players = server.getPlayerList();
		int simulation = players.getSimulationDistance();
		int view = players.getViewDistance();
		int defaultInterval = AutoTuner.values().blockEntityInterval();

		TestSupport.CommandResult result = run(helper, "/tickengine tuning level 1000");
		check(result.result() == 1, "tuning level: " + result.text());
		int max = AutoTuner.level();
		check(max > 0 && max == AutoTuner.maxLevel(server) && AutoTuner.isPaused(), "level " + max + " should be the max and paused");
		check(AutoTuner.values().blockEntityInterval() > defaultInterval, "interval not raised at the max level");
		check(players.getSimulationDistance() == Math.min(simulation, TickEngineConfig.minSimulationDistance)
			|| players.getSimulationDistance() < simulation, "simulation distance not lowered: " + players.getSimulationDistance());
		check(players.getViewDistance() <= view, "view distance raised");

		run(helper, "/tickengine tuning level 0");
		check(AutoTuner.level() == 0 && AutoTuner.values().blockEntityInterval() == defaultInterval, "level 0 not restored");
		check(players.getSimulationDistance() == simulation && players.getViewDistance() == view, "distances not restored");
		check(run(helper, "/tickengine tuning resume").result() == 1 && !AutoTuner.isPaused(), "resume");

		configure(helper, "autoTuning.enabled", "false");
		check(run(helper, "/tickengine tuning level 2").result() == 0, "forcing a level with auto-tuning off must fail");
		helper.succeed();
	}

	/** Busy-waits to push MSPT over the threshold, then lets it relax. */
	@GameTest(template = "floor", batch = "tuning_auto", timeoutTicks = 600)
	public static void tightensUnderLoadAndRelaxesAfter(GameTestHelper helper) {
		configure(helper, "autoTuning.enabled", "true", "autoTuning.decisionSeconds", "2", "autoTuning.raiseAboveMspt", "45",
			"autoTuning.lowerBelowMspt", "30", "autoTuning.relaxAfterSeconds", "0");
		boolean[] load = {true};
		int[] peak = {0};
		helper.onEachTick(() -> {
			peak[0] = Math.max(peak[0], AutoTuner.level());
			if (load[0]) {
				long until = System.nanoTime() + 60_000_000L;
				while (System.nanoTime() < until) {
					Thread.onSpinWait();
				}
				if (peak[0] >= 1) {
					load[0] = false;
				}
			} else if (AutoTuner.level() == 0) {
				check(AutoTuner.lastAverageMspt() < 30, "relaxed with average " + AutoTuner.lastAverageMspt());
				configure(helper);
				helper.succeed();
			}
		});
	}

	/**
	 * Lowering relaxAfterSeconds while tuned up must apply from the next decision, not only after
	 * ten calm minutes. Decisions every 40 ticks: with the old 60 s wait this would take 1200 ticks.
	 */
	@GameTest(template = "floor", batch = "tuning_relax_setting", timeoutTicks = 600)
	public static void loweringRelaxAfterSecondsAppliesAtOnce(GameTestHelper helper) {
		configure(helper, "autoTuning.enabled", "true", "autoTuning.decisionSeconds", "2", "autoTuning.raiseAboveMspt", "45",
			"autoTuning.lowerBelowMspt", "30", "autoTuning.relaxAfterSeconds", "60");
		int[] calmTicks = {-1};
		helper.onEachTick(() -> {
			if (calmTicks[0] < 0) {
				if (AutoTuner.level() == 0) {
					long until = System.nanoTime() + 60_000_000L;
					while (System.nanoTime() < until) {
						Thread.onSpinWait();
					}
					return;
				}
				calmTicks[0] = 0;
				return;
			}
			calmTicks[0]++;
			if (calmTicks[0] == 100) {
				check(AutoTuner.level() > 0, "relaxed before the 60 s wait was over");
				check(run(helper, "/tickengine config set autoTuning.relaxAfterSeconds 0").result() == 1, "set relaxAfterSeconds");
				check(run(helper, "/tickengine tuning status").text().contains("for 0 s"), "tuning status still shows the old wait");
			} else if (calmTicks[0] > 100 && AutoTuner.level() == 0) {
				check(calmTicks[0] <= 100 + 3 * 40, "took " + (calmTicks[0] - 100) + " ticks to relax after lowering the wait");
				configure(helper);
				helper.succeed();
			}
		});
	}

	// --- config file ---

	@GameTest(template = "floor", batch = "config_file", timeoutTicks = 100)
	public static void brokenConfigIsLeftUntouched(GameTestHelper helper) throws IOException {
		configure(helper);
		Path file = FMLPaths.CONFIGDIR.get().resolve("tickengine.json5");
		String original = Files.readString(file);
		String broken = original.replace("\"sampleEveryTicks\": 20", "\"sampleEveryTicks\": 20,,");
		check(!broken.equals(original), "could not break the config file");
		Files.writeString(file, broken, StandardCharsets.UTF_8);
		check(!TickEngineConfig.load(), "broken file reported as loaded");
		check(Files.readString(file).equals(broken), "broken file was rewritten");
		TestSupport.CommandResult reload = run(helper, "/tickengine config reload");
		check(reload.result() == 0 && Files.readString(file).equals(broken), "reload of a broken file: " + reload.text());

		String edited = original.replace("\"defaultInterval\": 4", "\"defaultInterval\": 10")
			.replace("\"rules\": {\n\t\t\t\"minecraft:piston\": 1\n\t\t}", "\"rules\": {\n\t\t\t\"minecraft:piston\": 1,\n\t\t\t\"minecraft:hopper\": 8\n\t\t}");
		Files.writeString(file, edited, StandardCharsets.UTF_8);
		check(run(helper, "/tickengine config reload").result() == 1, "reload of a valid file failed");
		check(TickEngineConfig.defaultInterval == 10, "defaultInterval not loaded from the file");
		check(Integer.valueOf(8).equals(TickEngineConfig.BLOCK_ENTITY_RULES.rules().get("minecraft:hopper")),
			"hopper rule not loaded: " + TickEngineConfig.BLOCK_ENTITY_RULES.rules());
		TickEngineConfig.BLOCK_ENTITY_RULES.remove("minecraft:hopper", null);
		configure(helper);
		check(Files.readString(file).contains("\"defaultInterval\": 4"), "config file not restored");
		helper.succeed();
	}
}
