package com.studioderiva.tickengine.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the minimal tick age dropped items, so they still despawn after 5 minutes. */
@Mixin(ItemEntity.class)
public interface ItemEntityAccessor {
	@Accessor("age")
	int tickengine$getAge();

	@Accessor("age")
	void tickengine$setAge(int age);
}
