package com.studioderiva.tickengine.gametest;

import static com.studioderiva.tickengine.gametest.TestSupport.check;
import static com.studioderiva.tickengine.gametest.TestSupport.configure;
import static com.studioderiva.tickengine.gametest.TestSupport.run;

import com.studioderiva.tickengine.TickEngine;
import com.studioderiva.tickengine.config.TickEngineConfig;
import com.studioderiva.tickengine.stacking.EntityStacker;
import com.studioderiva.tickengine.tuning.AutoTuner;
import com.studioderiva.tickengine.tuning.EmergencyMode;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Emergency mode started and stopped by command: auto-tuning, intervals, spawning, stacking. */
@GameTestHolder(TickEngine.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EmergencyTests {
	private EmergencyTests() {
	}

	@GameTest(template = "floor", batch = "emergency_command", timeoutTicks = 100)
	public static void startRaisesAutoTuningToTheTopAndStopPutsItBack(GameTestHelper helper) {
		configure(helper, "autoTuning.enabled", "true", "emergency.saveOnEnter", "false");
		MinecraftServer server = helper.getLevel().getServer();
		AutoTuner.forceLevel(server, 1);
		AutoTuner.resume();
		TestSupport.CommandResult start = run(helper, "/tickengine emergency start");
		check(EmergencyMode.isActive() && EmergencyMode.isManual(), "emergency did not start: " + start.text());
		check(AutoTuner.level() == AutoTuner.maxLevel(server), "auto-tuning at " + AutoTuner.level() + ", expected the max "
			+ AutoTuner.maxLevel(server));
		check(EmergencyMode.pausesSpawning(), "natural spawning should pause");
		check(EmergencyMode.adjustInterval(2) == 20 && EmergencyMode.adjustInterval(1) == 1,
			"intervals: 2 became " + EmergencyMode.adjustInterval(2) + ", 1 became " + EmergencyMode.adjustInterval(1));
		TestSupport.CommandResult stop = run(helper, "/tickengine emergency stop");
		check(!EmergencyMode.isActive(), "emergency did not stop: " + stop.text());
		check(AutoTuner.level() == 1, "auto-tuning at " + AutoTuner.level() + " after the emergency, expected 1 as before");
		check(!EmergencyMode.pausesSpawning() && EmergencyMode.adjustInterval(2) == 2, "spawning or intervals not restored");
		helper.succeed();
	}

	/**
	 * Slow ticks for triggerSeconds start it on its own; normal ticks for exitSeconds end it. The timeout is
	 * high because the GameTest server runs faster than 20 ticks per second once nothing slows it down.
	 */
	@GameTest(template = "floor", batch = "emergency_automatic", timeoutTicks = 20000)
	public static void slowTicksStartItAndNormalTicksEndIt(GameTestHelper helper) {
		configure(helper, "emergency.enabled", "true", "emergency.triggerSeconds", "5", "emergency.exitSeconds", "5",
			"emergency.memoryPercent", "0", "emergency.saveOnEnter", "false");
		boolean[] entered = {false};
		helper.onEachTick(() -> {
			if (!entered[0]) {
				if (EmergencyMode.isActive()) {
					entered[0] = true;
					check(!EmergencyMode.isManual() && EmergencyMode.reason().contains("MSPT"), "reason: " + EmergencyMode.reason());
					return;
				}
				try {
					// 200 ms per tick: above emergency.triggerMspt (150).
					Thread.sleep(200);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			} else if (!EmergencyMode.isActive()) {
				helper.succeed();
			}
		});
	}

	@GameTest(template = "floor", batch = "emergency_stacking", timeoutTicks = 100)
	public static void stackingModeEmergencyRunsOnlyDuringAnEmergency(GameTestHelper helper) {
		configure(helper, "stacking.mode", "emergency", "emergency.saveOnEnter", "false");
		check(!EntityStacker.isActive(), "stacking ran without an emergency");
		run(helper, "/tickengine emergency start");
		check(EntityStacker.isActive(), "stacking did not run during the emergency");
		run(helper, "/tickengine emergency stop");
		check(!EntityStacker.isActive(), "stacking still ran after the emergency");
		helper.succeed();
	}

	@GameTest(template = "floor", batch = "emergency_enable", timeoutTicks = 100)
	public static void enableAndDisableChangeOnlyTheSetting(GameTestHelper helper) {
		configure(helper);
		run(helper, "/tickengine emergency enable");
		check(TickEngineConfig.emergencyEnabled && !EmergencyMode.isActive(),
			"enable should turn the setting on without starting an emergency");
		TestSupport.CommandResult status = run(helper, "/tickengine emergency");
		check(status.result() == 1 && status.text().contains("Emergency"), "status: " + status.text());
		run(helper, "/tickengine emergency disable");
		check(!TickEngineConfig.emergencyEnabled, "disable did not turn it off");
		helper.succeed();
	}
}
