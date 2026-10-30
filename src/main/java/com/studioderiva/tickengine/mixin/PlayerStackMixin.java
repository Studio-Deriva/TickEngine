package com.studioderiva.tickengine.mixin;

import com.studioderiva.tickengine.stacking.EntityStacker;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A player interaction with a stack that does something (feeding, shearing, a name tag, a lead)
 * applies to one mob only; {@link EntityStacker} moves the rest to a copy. Hooked on the player
 * rather than on {@code Mob.interact}: on Forge, shears (IForgeShearable) and other items act through
 * {@code Item.interactLivingEntity}, which {@code interactOn} calls after the mob's own interaction.
 */
@Mixin(Player.class)
abstract class PlayerStackMixin {
	@Inject(method = "interactOn", at = @At("HEAD"))
	private void tickengine$beforeInteract(Entity entity, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		if (entity instanceof Mob mob) {
			EntityStacker.beforeInteract(mob);
		}
	}

	@Inject(method = "interactOn", at = @At("RETURN"))
	private void tickengine$afterInteract(Entity entity, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		if (entity instanceof Mob mob) {
			EntityStacker.afterInteract(mob, cir.getReturnValue());
		}
	}
}
