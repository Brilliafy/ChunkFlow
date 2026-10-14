package com.cleanroommc.chunkflow.mixin;

import com.cleanroommc.chunkflow.config.ModConfig;
import com.cleanroommc.chunkflow.directional.DirectionalChunkHelper;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(PlayerChunkMapEntry.class)
public abstract class PlayerChunkMapEntryMixin {

    @Shadow
    private ChunkPos pos;

    @Shadow
    private List<EntityPlayerMP> players;

    @Inject(method = "getClosestPlayerDistance()D", at = @At("HEAD"), cancellable = true)
    private void directionalChunks$onGetClosestPlayerDistance(CallbackInfoReturnable<Double> cir) {
        if (!ModConfig.DIRECTIONAL.enabled || this.players == null || this.players.isEmpty()) {
            return;
        }

        double dirDist = DirectionalChunkHelper.getDirectionalDistance(this.pos, this.players);
        cir.setReturnValue(dirDist);
    }
}
