package com.studioderiva.tickengine;

import com.studioderiva.tickengine.command.TickEngineCommand;
import com.studioderiva.tickengine.config.TickEngineConfig;
import com.studioderiva.tickengine.profiler.TickProfiler;
import com.studioderiva.tickengine.throttle.BlockEntityThrottle;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TickEngine implements DedicatedServerModInitializer {
	public static final String MOD_ID = "tickengine";
	public static final Logger LOGGER = LoggerFactory.getLogger("TickEngine");

	@Override
	public void onInitializeServer() {
		TickEngineConfig.load();

		ServerTickEvents.START_SERVER_TICK.register(TickProfiler::onTickStart);
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			TickProfiler.onTickEnd(server);
			BlockEntityThrottle.onTickEnd();
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			TickProfiler.reset();
			BlockEntityThrottle.reset();
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			TickEngineCommand.register(dispatcher));

		LOGGER.info("TickEngine initialized (light profiler {}, block entity throttling {})",
			TickEngineConfig.profilerEnabled ? "on" : "off", TickEngineConfig.blockEntityThrottle ? "on" : "off");
	}
}
