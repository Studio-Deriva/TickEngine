package com.studioderiva.tickengine.mixin;

import com.studioderiva.tickengine.network.NetworkProfiler;
import java.util.function.Consumer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Network profiler: tags the packets that show an entity to a player (spawn, data, equipment...). */
@Mixin(ServerEntity.class)
abstract class ServerEntityMixin {
	@Shadow
	@Final
	private Entity entity;

	@ModifyVariable(method = "sendPairingData", at = @At("HEAD"), argsOnly = true)
	private Consumer<Packet<?>> tickengine$tagPairing(Consumer<Packet<?>> consumer) {
		return NetworkProfiler.active ? NetworkProfiler.tagging(consumer, entity) : consumer;
	}
}
