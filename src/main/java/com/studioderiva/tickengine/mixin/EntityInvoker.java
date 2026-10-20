package com.studioderiva.tickengine.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the minimal tick advance the portal cooldown of a throttled entity. */
@Mixin(Entity.class)
public interface EntityInvoker {
	@Invoker("processPortalCooldown")
	void tickengine$processPortalCooldown();
}
