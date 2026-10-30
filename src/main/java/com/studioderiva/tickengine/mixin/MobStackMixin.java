package com.studioderiva.tickengine.mixin;

import com.studioderiva.tickengine.stacking.EntityStacker;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A player interaction with a stack that does something (feeding, shearing, a name tag, a lead)
 * applies to one mob only; {@link EntityStacker} moves the rest to a copy.
 */
@Mixin(Mob.class)
abstract class MobStackMixin {
	@Inject(method = "interact", at = @At("HEAD"))
	private void tickengine$beforeInteract(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		EntityStacker.beforeInteract((Mob) (Object) this);
	}

	@Inject(method = "interact", at = @At("RETURN"))
	private void tickengine$afterInteract(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		EntityStacker.afterInteract((Mob) (Object) this, cir.getReturnValue());
	}
}
