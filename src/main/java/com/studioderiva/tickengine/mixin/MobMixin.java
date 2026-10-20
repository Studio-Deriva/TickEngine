package com.studioderiva.tickengine.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.studioderiva.tickengine.throttle.EntityThrottle;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.sensing.Sensing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Skips the AI parts of {@code serverAiStep} (sensing, goals, navigation and
 * {@code customServerAiStep}, where brain-based mobs like villagers think) when
 * {@link EntityThrottle} throttles this mob's AI. Movement, look and jump controls still run,
 * so the mob keeps moving toward its last target smoothly.
 */
@Mixin(Mob.class)
abstract class MobMixin {
	@WrapWithCondition(method = "serverAiStep",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/sensing/Sensing;tick()V"))
	private boolean tickengine$sensing(Sensing sensing) {
		return !EntityThrottle.isAiSkipped((Mob) (Object) this);
	}

	@WrapWithCondition(method = "serverAiStep",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/goal/GoalSelector;tickRunningGoals(Z)V"))
	private boolean tickengine$runningGoals(GoalSelector selector, boolean tickAll) {
		return !EntityThrottle.isAiSkipped((Mob) (Object) this);
	}

	@WrapWithCondition(method = "serverAiStep",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/goal/GoalSelector;tick()V"))
	private boolean tickengine$goals(GoalSelector selector) {
		return !EntityThrottle.isAiSkipped((Mob) (Object) this);
	}

	@WrapWithCondition(method = "serverAiStep",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;tick()V"))
	private boolean tickengine$navigation(PathNavigation navigation) {
		return !EntityThrottle.isAiSkipped((Mob) (Object) this);
	}

	@WrapWithCondition(method = "serverAiStep",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;customServerAiStep(Lnet/minecraft/server/level/ServerLevel;)V"))
	private boolean tickengine$customAi(Mob mob, ServerLevel level) {
		return !EntityThrottle.isAiSkipped(mob);
	}
}
