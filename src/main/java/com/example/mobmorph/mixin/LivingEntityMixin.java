package com.example.mobmorph.mixin;

import com.example.mobmorph.MorphState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Паук (и пещерный паук) лазает по стенам как по лестнице: иди в стену; Shift — висеть на месте. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Inject(method = "isClimbing", at = @At("HEAD"), cancellable = true)
    private void mobmorph$spiderClimb(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof PlayerEntity p && p.horizontalCollision && !p.isSpectator()) {
            EntityType<?> t = MorphState.type(p);
            if (t == EntityType.SPIDER || t == EntityType.CAVE_SPIDER) cir.setReturnValue(true);
        }
    }
}
