package com.studioderiva.tickengine.mixin;

import com.studioderiva.tickengine.network.NetworkProfiler;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Network profiler: tags the packets the entity tracker sends about an entity (movement, data,
 * animations...) with its type, before they reach the players' connections.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
abstract class TrackedEntityMixin {
	@Shadow
	@Final
	Entity entity;

	@Inject(method = { "broadcast", "broadcastAndSend" }, at = @At("HEAD"))
	private void tickengine$tag(Packet<?> packet, CallbackInfo ci) {
		if (NetworkProfiler.active) {
			NetworkProfiler.tagEntityPacket(packet, entity);
		}
	}
}
