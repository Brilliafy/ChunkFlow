package com.cleanroommc.chunkflow.mixin;

import com.cleanroommc.chunkflow.ChunkFlow;
import net.minecraftforge.common.chunkio.ChunkIOExecutor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Only hooks methods whose descriptors contain no Minecraft types. The runtime is obfuscated (SRG
 * members / obfuscated classes), and this is a {@code remap = false} mixin into a Forge class, so
 * referencing {@code World}/{@code Chunk}/{@code AnvilChunkLoader}/{@code ChunkProviderServer} in a
 * handler signature would not match the runtime descriptor. {@code tick()} and {@code adjustPoolSize(int)}
 * are safe and are invoked often enough (once per server tick, and on every player join) to keep the
 * pool sized before chunk loads happen.
 */
@Mixin(value = ChunkIOExecutor.class, remap = false)
public abstract class ChunkIOExecutorMixin {

    /** Forge's chunk I/O executor: private static final ThreadPoolExecutor pool. */
    @Shadow
    private static ThreadPoolExecutor pool;

    /**
     * Forge derives the core pool size from player count ({@code max(1, players/50)}). We manage the
     * size through ChunkFlow's configurator.
     */
    @Inject(method = "adjustPoolSize", at = @At("HEAD"), cancellable = true, remap = false)
    private static void chunkPipeline$adjustPoolSize(int players, CallbackInfo ci) {
        ChunkFlow.adjustPoolSize(pool, players);
        ci.cancel();
    }

    /** Called once per server tick, before chunk work for the tick. Keeps the pool sized. */
    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private static void chunkPipeline$tick(CallbackInfo ci) {
        ChunkFlow.ensurePoolSize(pool);
    }
}
