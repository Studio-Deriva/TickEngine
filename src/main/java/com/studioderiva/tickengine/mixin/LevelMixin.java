package com.studioderiva.tickengine.mixin;

import com.studioderiva.tickengine.throttle.BlockEntityThrottle;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prepares block entity throttling (chunks near players) once per level per tick. */
@Mixin(Level.class)
abstract class LevelMixin {
	@Inject(method = "tickBlockEntities", at = @At("HEAD"))
	private void tickengine$prepareThrottle(CallbackInfo ci) {
		if ((Object) this instanceof ServerLevel level) {
			BlockEntityThrottle.prepare(level);
		}
	}
}
