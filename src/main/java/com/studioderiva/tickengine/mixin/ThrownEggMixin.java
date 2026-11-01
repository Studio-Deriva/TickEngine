package com.studioderiva.tickengine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.studioderiva.tickengine.limit.EntityLimit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Chicks hatching from thrown eggs (chicken farms with dispensers) count as breeding for the entity
 * limit. Named by text because the class moved in 1.21.11.
 */
//? if >=1.21.11 {
@Mixin(targets = "net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg")
//?} else {
/*@Mixin(targets = "net.minecraft.world.entity.projectile.ThrownEgg")
*///?}
abstract class ThrownEggMixin {
	@WrapOperation(
		method = "onHit",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z")
	)
	private boolean tickengine$limitEntities(Level level, Entity chick, Operation<Boolean> original) {
		if (level instanceof ServerLevel serverLevel
			&& !EntityLimit.allows(serverLevel, chick.getType(), chick.blockPosition(), EntityLimit.Source.BREEDING)) {
			return false;
		}
		return original.call(level, chick);
	}
}
