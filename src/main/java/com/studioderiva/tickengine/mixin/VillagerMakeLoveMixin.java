package com.studioderiva.tickengine.mixin;

import com.studioderiva.tickengine.limit.EntityLimit;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.behavior.VillagerMakeLove;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Villager breeding and the entity limit: in a chunk at the limit no child is born, the bed is given
 * back (vanilla does that when breeding fails) and both parents wait as if they had bred, instead of
 * eating their food again on every try.
 */
@Mixin(VillagerMakeLove.class)
abstract class VillagerMakeLoveMixin {
	/** Age a parent gets after breeding in vanilla: the wait before it can breed again. */
	private static final int PARENT_AGE_AFTER_BREEDING = 6000;

	@Inject(method = "breed", at = @At("HEAD"), cancellable = true)
	private void tickengine$limitEntities(ServerLevel level, Villager parent, Villager partner,
		CallbackInfoReturnable<Optional<Villager>> cir) {
		if (!EntityLimit.allows(level, EntityType.VILLAGER, parent.blockPosition(), EntityLimit.Source.BREEDING)) {
			parent.setAge(PARENT_AGE_AFTER_BREEDING);
			partner.setAge(PARENT_AGE_AFTER_BREEDING);
			cir.setReturnValue(Optional.empty());
		}
	}
}
