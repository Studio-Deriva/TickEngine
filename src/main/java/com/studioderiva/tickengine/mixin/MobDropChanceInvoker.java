package com.studioderiva.tickengine.mixin;

import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Before 1.21.5 a mob's drop chance per slot is protected: entity stacking reads it to tell picked-up
 * items from the equipment a mob spawned with. Empty from 1.21.5, where it is public.
 */
@Mixin(Mob.class)
public interface MobDropChanceInvoker {
	//? if <1.21.5 {
	/*@org.spongepowered.asm.mixin.gen.Invoker("getEquipmentDropChance")
	float tickengine$getEquipmentDropChance(net.minecraft.world.entity.EquipmentSlot slot);
	*///?}
}
