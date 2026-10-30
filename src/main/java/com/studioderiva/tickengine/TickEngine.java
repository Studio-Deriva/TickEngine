package com.studioderiva.tickengine;

import com.studioderiva.tickengine.command.TickEngineCommand;
import com.studioderiva.tickengine.compat.ServerCoreCompat;
import com.studioderiva.tickengine.config.TickEngineConfig;
import com.studioderiva.tickengine.notice.AdminNotices;
import com.studioderiva.tickengine.permission.TickEnginePermissions;
import com.studioderiva.tickengine.profiler.TickProfiler;
import com.studioderiva.tickengine.stacking.EntityStacker;
import com.studioderiva.tickengine.throttle.BlockEntityThrottle;
import com.studioderiva.tickengine.throttle.EntityThrottle;
import com.studioderiva.tickengine.tuning.AutoTuner;
import com.studioderiva.tickengine.tuning.EmergencyMode;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(TickEngine.MOD_ID)
public final class TickEngine {
	public static final String MOD_ID = "tickengine";
	public static final Logger LOGGER = LoggerFactory.getLogger("TickEngine");

	public TickEngine() {
		// Dedicated servers only, like the Fabric version: the mixins are not applied on clients.
		if (!FMLEnvironment.dist.isDedicatedServer()) {
			LOGGER.info("TickEngine is server-side only: nothing to do on the client.");
			return;
		}
		TickEngineConfig.load();

		IEventBus bus = MinecraftForge.EVENT_BUS;
		bus.addListener((TickEvent.ServerTickEvent event) -> {
			if (event.phase == TickEvent.Phase.START) {
				TickProfiler.onTickStart(event.getServer());
				AutoTuner.onTickStart();
				EmergencyMode.onTickStart();
			} else {
				TickProfiler.onTickEnd(event.getServer());
				BlockEntityThrottle.onTickEnd();
				EntityThrottle.onTickEnd();
				ServerCoreCompat.tick();
				// Before auto-tuning, so a decision in the same tick already sees the emergency.
				EmergencyMode.onTickEnd(event.getServer());
				AutoTuner.onTickEnd(event.getServer());
				EntityStacker.onTickEnd(event.getServer());
				AdminNotices.tick(event.getServer());
			}
		});
		bus.addListener((ServerStartedEvent event) -> ServerCoreCompat.refresh());
		// Before worlds save, so the original distances are back in place.
		bus.addListener((ServerStoppingEvent event) -> {
			EmergencyMode.shutdown();
			AutoTuner.shutdown();
		});
		bus.addListener((ServerStoppedEvent event) -> {
			TickProfiler.reset();
			BlockEntityThrottle.reset();
			EntityThrottle.reset();
			EntityStacker.reset();
			AdminNotices.reset();
		});
		bus.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
			if (event.getEntity() instanceof ServerPlayer player) {
				AdminNotices.onJoin(player);
			}
		});
		bus.addListener((RegisterCommandsEvent event) -> TickEngineCommand.register(event.getDispatcher()));
		bus.addListener(TickEnginePermissions::onGatherNodes);

		LOGGER.info("TickEngine initialized (light profiler {}, block entity throttling {}, entity throttling {}, "
			+ "tick spreading {}, auto-tuning {}, emergency mode {}, entity stacking {})", TickEngineConfig.profilerEnabled ? "on" : "off",
			TickEngineConfig.blockEntityThrottle ? "on" : "off", TickEngineConfig.entityThrottle ? "on" : "off",
			TickEngineConfig.spreadEntityTicks ? "on" : "off", TickEngineConfig.autoTuning ? "on" : "off",
			TickEngineConfig.emergencyEnabled ? "on" : "off", TickEngineConfig.stackingMode);
	}
}
