package com.studioderiva.tickengine;

import com.studioderiva.tickengine.command.TickEngineCommand;
import com.studioderiva.tickengine.compat.ServerCoreCompat;
import com.studioderiva.tickengine.config.TickEngineConfig;
import com.studioderiva.tickengine.notice.AdminNotices;
import com.studioderiva.tickengine.profiler.TickProfiler;
import com.studioderiva.tickengine.throttle.BlockEntityThrottle;
import com.studioderiva.tickengine.throttle.EntityThrottle;
import com.studioderiva.tickengine.tuning.AutoTuner;
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
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			TickProfiler.onTickEnd(server);
			BlockEntityThrottle.onTickEnd();
			EntityThrottle.onTickEnd();
			ServerCoreCompat.tick();
			AutoTuner.onTickEnd(server);
			AdminNotices.tick(server);
		});
		ServerLifecycleEvents.SERVER_STARTED.register(server -> ServerCoreCompat.refresh());
		// Before worlds save, so the original distances are back in place.
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> AutoTuner.shutdown());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			TickProfiler.reset();
			BlockEntityThrottle.reset();
			EntityThrottle.reset();
			AdminNotices.reset();
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> AdminNotices.onJoin(handler.getPlayer()));
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			TickEngineCommand.register(dispatcher));

		LOGGER.info("TickEngine initialized (light profiler {}, block entity throttling {}, entity throttling {}, "
			+ "auto-tuning {})", TickEngineConfig.profilerEnabled ? "on" : "off",
			TickEngineConfig.blockEntityThrottle ? "on" : "off", TickEngineConfig.entityThrottle ? "on" : "off",
			TickEngineConfig.autoTuning ? "on" : "off");
	}
}
