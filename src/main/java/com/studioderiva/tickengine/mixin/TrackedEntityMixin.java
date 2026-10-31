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
 * animations...) with its type, before they reach the players' connections. The method names
 * changed in 1.21.9.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
abstract class TrackedEntityMixin {
	@Shadow
	@Final
	Entity entity;

	//? if >=1.21.9 {
	@Inject(method = { "sendToTrackingPlayers", "sendToTrackingPlayersAndSelf" }, at = @At("HEAD"))
	private void tickengine$tag(Packet<?> packet, CallbackInfo ci) {
		if (NetworkProfiler.active) {
			NetworkProfiler.tagEntityPacket(packet, entity);
		}
	}

	@Inject(method = "sendToTrackingPlayersFiltered", at = @At("HEAD"))
	private void tickengine$tagFiltered(Packet<?> packet, java.util.function.Predicate<?> filter, CallbackInfo ci) {
		if (NetworkProfiler.active) {
			NetworkProfiler.tagEntityPacket(packet, entity);
		}
	}
	//?} else {
	/*@Inject(method = { "broadcast", "broadcastAndSend" }, at = @At("HEAD"))
	private void tickengine$tag(Packet<?> packet, CallbackInfo ci) {
		if (NetworkProfiler.active) {
			NetworkProfiler.tagEntityPacket(packet, entity);
		}
	}
	*///?}

	//? if >=1.21.5 <1.21.9 {
	/*@Inject(method = "broadcastIgnorePlayers", at = @At("HEAD"))
	private void tickengine$tagIgnoring(Packet<?> packet, java.util.List<?> ignored, CallbackInfo ci) {
		if (NetworkProfiler.active) {
			NetworkProfiler.tagEntityPacket(packet, entity);
		}
	}
	*///?}
}
