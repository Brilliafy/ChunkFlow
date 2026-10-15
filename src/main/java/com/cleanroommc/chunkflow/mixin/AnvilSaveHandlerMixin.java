package com.cleanroommc.chunkflow.mixin;

import com.cleanroommc.chunkflow.io.RegionFileCacheHelper;
import net.minecraft.world.chunk.storage.AnvilSaveHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AnvilSaveHandler.class)
public abstract class AnvilSaveHandlerMixin {

    @Inject(method = "flush", at = @At("HEAD"))
    private void chunkflow$onFlushHead(CallbackInfo ci) {
        AnvilSaveHandler handler = (AnvilSaveHandler) (Object) this;
        RegionFileCacheHelper.beforeFlush(handler.getWorldDirectory());
    }

    @Inject(method = "flush", at = @At("RETURN"))
    private void chunkflow$onFlushReturn(CallbackInfo ci) {
        RegionFileCacheHelper.afterFlush();
    }
}
