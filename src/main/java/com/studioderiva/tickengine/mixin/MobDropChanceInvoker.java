package com.studioderiva.tickengine.mixin;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A mob's drop chance per slot, protected in vanilla: entity stacking tells picked-up items from spawn equipment by it. */
@Mixin(Mob.class)
public interface MobDropChanceInvoker {
	@Invoker("getEquipmentDropChance")
	float tickengine$getEquipmentDropChance(EquipmentSlot slot);
}
