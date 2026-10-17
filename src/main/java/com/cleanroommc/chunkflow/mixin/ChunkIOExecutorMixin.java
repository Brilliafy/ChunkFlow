package com.cleanroommc.chunkflow.mixin;

import com.cleanroommc.chunkflow.ChunkFlow;
import net.minecraftforge.common.chunkio.ChunkIOExecutor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Hooks into ChunkIOExecutor to:
 * 1. Ensure `tasks` is backed by a thread-safe ConcurrentHashMap.
 * 2. Guard `pool.remove(task)` in both `syncChunkLoad` and `dropQueuedChunkLoad` against concurrency races.
 * 3. Size the chunk I/O worker pool dynamically.
 *
 * Only hooks methods or bytecode sites whose descriptors contain no Minecraft types to remain compatible
 * with obfuscated runtimes when remap = false.
 */
@Mixin(value = ChunkIOExecutor.class, remap = false)
public abstract class ChunkIOExecutorMixin {

    @Shadow(remap = false)
    @Final
    @Mutable
    private static Map tasks;

    /** Forge's chunk I/O executor: private static final ThreadPoolExecutor pool. */
    @Shadow(remap = false)
    private static ThreadPoolExecutor pool;

    @Inject(method = "<clinit>", at = @At("RETURN"), remap = false)
    private static void chunkPipeline$onClinit(CallbackInfo ci) {
        if (!(tasks instanceof ConcurrentHashMap)) {
            tasks = new ConcurrentHashMap<>(tasks != null ? tasks : Collections.emptyMap());
            ChunkFlow.LOGGER.info("[ChunkFlow] Upgraded ChunkIOExecutor.tasks to ConcurrentHashMap in <clinit>.");
        }
    }

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

    /**
     * Guards pool.remove(task) in syncChunkLoad so concurrent removals/executions don't race.
     */
    @Redirect(
            method = "syncChunkLoad",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/ThreadPoolExecutor;remove(Ljava/lang/Runnable;)Z"),
            remap = false
    )
    private static boolean chunkPipeline$guardSyncPoolRemove(ThreadPoolExecutor executor, Runnable task) {
        if (task == null) {
            return false;
        }
        synchronized (task) {
            try {
                return executor.remove(task);
            } catch (Throwable t) {
                return false;
            }
        }
    }

    /**
     * Guards pool.remove(task) in dropQueuedChunkLoad so task removal cannot race with async worker execution.
     */
    @Redirect(
            method = "dropQueuedChunkLoad",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/ThreadPoolExecutor;remove(Ljava/lang/Runnable;)Z"),
            remap = false
    )
    private static boolean chunkPipeline$guardDropPoolRemove(ThreadPoolExecutor executor, Runnable task) {
        if (task == null) {
            return false;
        }
        synchronized (task) {
            try {
                return executor.remove(task);
            } catch (Throwable t) {
                return false;
            }
        }
    }
}
