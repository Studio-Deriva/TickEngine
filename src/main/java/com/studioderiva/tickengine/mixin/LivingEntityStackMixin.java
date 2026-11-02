package com.studioderiva.tickengine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.studioderiva.tickengine.stacking.EntityStacker;
import net.minecraft.server.level.ServerLevel;
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
 * Damage is watched around {@code actuallyHurt} (which gained its ServerLevel parameter in 1.21.2), so
 * the copy gets the health the rest of the stack has.
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityStackMixin {
	//? if >=1.21.2 {
	@Inject(method = "actuallyHurt", at = @At("HEAD"))
	private void tickengine$beforeHurt(ServerLevel level, DamageSource source, float amount, CallbackInfo ci) {
		EntityStacker.beforeHurt((LivingEntity) (Object) this);
	}

	@Inject(method = "actuallyHurt", at = @At("RETURN"))
	private void tickengine$afterHurt(ServerLevel level, DamageSource source, float amount, CallbackInfo ci) {
		EntityStacker.afterHurt((LivingEntity) (Object) this, source);
	}
	//?} else {
	/*@Inject(method = "actuallyHurt", at = @At("HEAD"))
	private void tickengine$beforeHurt(DamageSource source, float amount, CallbackInfo ci) {
		EntityStacker.beforeHurt((LivingEntity) (Object) this);
	}

	@Inject(method = "actuallyHurt", at = @At("RETURN"))
	private void tickengine$afterHurt(DamageSource source, float amount, CallbackInfo ci) {
		EntityStacker.afterHurt((LivingEntity) (Object) this, source);
	}
	*///?}

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
