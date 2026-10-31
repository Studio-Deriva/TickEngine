package com.studioderiva.tickengine.mixin;

import com.studioderiva.tickengine.stacking.EntityStacker;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A stack that converts into another mob keeps its count and gets the new mob's name; see
 * {@link EntityStacker#afterConversion}. On 1.20.1 a conversion does not copy scoreboard tags, so
 * without this a converted stack would lose its count.
 */
@Mixin(Mob.class)
abstract class MobConversionMixin {
	@Inject(method = "convertTo(Lnet/minecraft/world/entity/EntityType;Z)Lnet/minecraft/world/entity/Mob;", at = @At("RETURN"))
	private void tickengine$afterConversion(CallbackInfoReturnable<Mob> cir) {
		EntityStacker.afterConversion((Mob) (Object) this, cir.getReturnValue());
	}
}
