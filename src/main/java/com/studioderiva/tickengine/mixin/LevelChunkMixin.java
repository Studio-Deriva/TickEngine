package com.studioderiva.tickengine.mixin;

import com.studioderiva.tickengine.profiler.TickProfiler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A chunk that finished generating becomes a full chunk through this constructor. Chunks saved
 * as full chunks load through another one, so this counts generated chunks only.
 */
@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {
	@Inject(
		method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V",
		at = @At("RETURN")
	)
	private void tickengine$countGenerated(ServerLevel level, ProtoChunk protoChunk, LevelChunk.PostLoadProcessor postLoad,
		CallbackInfo ci) {
		TickProfiler.chunkGenerated(level, protoChunk.getPos());
	}
}
