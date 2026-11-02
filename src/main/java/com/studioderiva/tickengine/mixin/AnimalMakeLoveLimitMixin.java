package com.studioderiva.tickengine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.studioderiva.tickengine.limit.EntityLimit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.AnimalMakeLove;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Breeding of animals that think with a brain (frogs, sniffers, goats, axolotls, camels, armadillos)
 * and the entity limit. Checked where the behaviour makes the baby, because frogs and sniffers make an
 * egg in their own {@code spawnChildFromBreeding}, which {@link AnimalBreedingMixin} does not see.
 */
@Mixin(AnimalMakeLove.class)
abstract class AnimalMakeLoveLimitMixin {
	@WrapOperation(method = "tick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/Animal;spawnChildFromBreeding(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/animal/Animal;)V"))
	private void tickengine$limitEntities(Animal parent, ServerLevel level, Animal partner, Operation<Void> original) {
		if (!EntityLimit.allowsBreeding(parent, partner)) {
			return;
		}
		EntityLimit.setBreedingChecked(true);
		try {
			original.call(parent, level, partner);
		} finally {
			EntityLimit.setBreedingChecked(false);
		}
	}
}
