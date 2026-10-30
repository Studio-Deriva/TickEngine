package com.studioderiva.tickengine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.studioderiva.tickengine.stacking.EntityStacker;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A stack that dies loses one mob: {@link EntityStacker} puts a copy with the rest in its place
 * ({@code /kill} removes the whole stack).
 * The "Named entity died" log line is skipped for stacks, whose name is only their count; in a
 * mob farm it would come on every kill.
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityStackMixin {
	@Inject(method = "die", at = @At("HEAD"))
	private void tickengine$beforeDeath(DamageSource source, CallbackInfo ci) {
		EntityStacker.beforeDeath((LivingEntity) (Object) this, source);
	}

	@Inject(method = "die", at = @At("TAIL"))
	private void tickengine$afterDeath(DamageSource source, CallbackInfo ci) {
		EntityStacker.afterDeath((LivingEntity) (Object) this);
	}

	@WrapOperation(method = "die",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;hasCustomName()Z"))
	private boolean tickengine$namedDeathLog(LivingEntity entity, Operation<Boolean> original) {
		return original.call(entity) && !EntityStacker.isStack(entity);
	}
}
