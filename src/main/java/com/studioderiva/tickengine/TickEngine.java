package com.studioderiva.tickengine;

import com.studioderiva.tickengine.command.TickEngineCommand;
import com.studioderiva.tickengine.compat.ServerCoreCompat;
import com.studioderiva.tickengine.config.TickEngineConfig;
import com.studioderiva.tickengine.network.NetworkProfiler;
import com.studioderiva.tickengine.notice.AdminNotices;
import com.studioderiva.tickengine.profiler.TickProfiler;
import com.studioderiva.tickengine.stacking.EntityStacker;
import com.studioderiva.tickengine.throttle.BlockEntityThrottle;
import com.studioderiva.tickengine.throttle.EntityThrottle;
import com.studioderiva.tickengine.tuning.AutoTuner;
import com.studioderiva.tickengine.tuning.EmergencyMode;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TickEngine implements DedicatedServerModInitializer {
	public static final String MOD_ID = "tickengine";
	public static final Logger LOGGER = LoggerFactory.getLogger("TickEngine");

	@Override
	public void onInitializeServer() {
		TickEngineConfig.load();

		ServerTickEvents.START_SERVER_TICK.register(server -> {
			TickProfiler.onTickStart(server);
			AutoTuner.onTickStart();
			EmergencyMode.onTickStart();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			TickProfiler.onTickEnd(server);
			NetworkProfiler.onTickEnd();
			BlockEntityThrottle.onTickEnd();
			EntityThrottle.onTickEnd();
			ServerCoreCompat.tick();
			// Before auto-tuning, so a decision in the same tick already sees the emergency.
			EmergencyMode.onTickEnd(server);
			AutoTuner.onTickEnd(server);
			EntityStacker.onTickEnd(server);
			AdminNotices.tick(server);
		});
		ServerLifecycleEvents.SERVER_STARTED.register(server -> ServerCoreCompat.refresh());
		// Before worlds save, so the original distances are back in place.
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			EmergencyMode.shutdown();
			AutoTuner.shutdown();
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			TickProfiler.reset();
			NetworkProfiler.reset();
			BlockEntityThrottle.reset();
			EntityThrottle.reset();
			EntityStacker.reset();
			AdminNotices.reset();
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> AdminNotices.onJoin(handler.getPlayer()));
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			TickEngineCommand.register(dispatcher));

		LOGGER.info("TickEngine initialized (light profiler {}, block entity throttling {}, entity throttling {}, "
			+ "tick spreading {}, auto-tuning {}, emergency mode {}, entity stacking {})", TickEngineConfig.profilerEnabled ? "on" : "off",
			TickEngineConfig.blockEntityThrottle ? "on" : "off", TickEngineConfig.entityThrottle ? "on" : "off",
			TickEngineConfig.spreadEntityTicks ? "on" : "off", TickEngineConfig.autoTuning ? "on" : "off",
			TickEngineConfig.emergencyEnabled ? "on" : "off", TickEngineConfig.stackingMode);
	}
}
