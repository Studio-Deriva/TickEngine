package com.studioderiva.tickengine.mixin;

import com.studioderiva.tickengine.stacking.EntityStacker;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A stack that converts into another mob keeps its count and gets the new mob's name; see
 * {@link EntityStacker#afterConversion}. The method changed in 1.21.2: before it, conversions did not
 * copy scoreboard tags, so a converted stack lost its count.
 */
@Mixin(Mob.class)
abstract class MobConversionMixin {
	//? if >=1.21.2 {
	@Inject(method = "convertTo(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/ConversionParams;Lnet/minecraft/world/entity/EntitySpawnReason;Lnet/minecraft/world/entity/ConversionParams$AfterConversion;)Lnet/minecraft/world/entity/Mob;",
		at = @At("RETURN"))
	//?} else {
	/*@Inject(method = "convertTo(Lnet/minecraft/world/entity/EntityType;Z)Lnet/minecraft/world/entity/Mob;", at = @At("RETURN"))
	*///?}
	private void tickengine$afterConversion(CallbackInfoReturnable<Mob> cir) {
		EntityStacker.afterConversion((Mob) (Object) this, cir.getReturnValue());
	}
}
