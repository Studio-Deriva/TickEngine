package com.studioderiva.tickengine.mixin;

import com.studioderiva.tickengine.limit.EntityLimit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Breeding and the entity limit: in a chunk at the limit two animals make no baby and lose their love
 * mode, so they can be fed again later. The limit is checked for the parents' type; the rare babies of
 * another type (a mule) count as their parents.
 */
@Mixin(Animal.class)
abstract class AnimalBreedingMixin {
	@Inject(method = "spawnChildFromBreeding", at = @At("HEAD"), cancellable = true)
	private void tickengine$limitEntities(ServerLevel level, Animal partner, CallbackInfo ci) {
		Animal self = (Animal) (Object) this;
		if (!EntityLimit.allows(level, self.getType(), self.blockPosition(), EntityLimit.Source.BREEDING)) {
			self.resetLove();
			partner.resetLove();
			ci.cancel();
		}
	}
}
