package com.example.mobmorph.mixin;

import com.example.mobmorph.MorphState;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Хитбокс и высота глаз игрока = как у моба. */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {

    @Inject(method = "getDimensions", at = @At("HEAD"), cancellable = true)
    private void mobmorph$dimensions(EntityPose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        MorphState.Profile pr = MorphState.profileOf((PlayerEntity) (Object) this);
        if (pr != null) cir.setReturnValue(EntityDimensions.changing(pr.width(), pr.height()));
    }

    @Inject(method = "getActiveEyeHeight", at = @At("HEAD"), cancellable = true)
    private void mobmorph$eye(EntityPose pose, EntityDimensions dimensions, CallbackInfoReturnable<Float> cir) {
        MorphState.Profile pr = MorphState.profileOf((PlayerEntity) (Object) this);
        if (pr != null) cir.setReturnValue(pr.eye());
    }
}
