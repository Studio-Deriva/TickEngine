package com.studioderiva.tickengine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.studioderiva.tickengine.stacking.EntityStacker;
import net.minecraft.core.dispenser.ShearsDispenseItemBehavior;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A dispenser shearing a stack shears one mob, like a player does: the rest moves to a copy (see
 * {@link EntityStacker#afterInteract}). The method was renamed in 1.21.6 and {@code shear} gained its
 * level and item parameters in 1.21.2.
 */
@Mixin(ShearsDispenseItemBehavior.class)
abstract class ShearsDispenseStackMixin {
	//? if >=1.21.2 {
	@WrapOperation(method = { "tryShearLivingEntity", "tryShearEntity" }, require = 1,
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Shearable;shear(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/sounds/SoundSource;Lnet/minecraft/world/item/ItemStack;)V"))
	private static void tickengine$shearOne(Shearable shearable, ServerLevel level, SoundSource source, ItemStack shears,
		Operation<Void> original) {
		Mob mob = shearable instanceof Mob shorn ? shorn : null;
		if (mob != null) {
			EntityStacker.beforeInteract(mob);
		}
		original.call(shearable, level, source, shears);
		if (mob != null) {
			EntityStacker.afterInteract(mob, InteractionResult.SUCCESS);
		}
	}
	//?} else {
	/*@WrapOperation(method = { "tryShearLivingEntity", "tryShearEntity" }, require = 1,
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Shearable;shear(Lnet/minecraft/sounds/SoundSource;)V"))
	private static void tickengine$shearOne(Shearable shearable, SoundSource source, Operation<Void> original) {
		Mob mob = shearable instanceof Mob shorn ? shorn : null;
		if (mob != null) {
			EntityStacker.beforeInteract(mob);
		}
		original.call(shearable, source);
		if (mob != null) {
			EntityStacker.afterInteract(mob, InteractionResult.SUCCESS);
		}
	}
	*///?}
}
