package com.cleanroommc.chunkflow;

import com.cleanroommc.chunkflow.command.CommandChunkFlow;
import com.cleanroommc.chunkflow.config.ModConfig;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Unified chunk pipeline for Cleanroom 1.12.2:
 *
 * <ul>
 *   <li><b>Async chunk I/O</b> - {@code net.minecraftforge.common.chunkio.ChunkIOExecutor} owns the pool
 *       used to load already-generated chunks off the main thread. Forge builds it with a
 *       <b>core pool size of 1</b> and an <b>unbounded</b> queue, so a {@link ThreadPoolExecutor} never
 *       grows past one worker: the region read + zlib inflate + {@code DataFixer} walk + NBT parse +
 *       {@code Chunk} construction ran on a single thread. This mod sizes it to the CPU and keeps it
 *       there. Safe because per-region reads are already synchronised in
 *       {@code RegionFile#getChunkDataInputStream} and {@code readChunkFromNBT} builds a self-contained
 *       {@code Chunk}; world-touching callbacks still run on the main thread via
 *       {@code ChunkIOExecutor.tick()}.</li>
 *   <li><b>Region file I/O</b> - a fast {@code RegionFile#getChunkDataInputStream} path using pooled
 *       zlib decompressors instead of allocating a new {@code Inflater} per chunk.</li>
 * </ul>
 */
@Mod(
        modid = ChunkFlow.MOD_ID,
        name = ChunkFlow.MOD_NAME,
        version = ChunkFlow.VERSION,
        acceptableRemoteVersions = "*"
)
public class ChunkFlow {

    public static final String MOD_ID = "chunkflow";
    public static final String MOD_NAME = "ChunkFlow";
    public static final String VERSION = "1.0.0";

    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    private static volatile ThreadPoolExecutor poolRef;

    @Mod.Instance(MOD_ID)
    public static ChunkFlow instance;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LOGGER.info("[ChunkFlow] Initialised. CPU cores: {}, target chunk I/O threads: {}.",
                Runtime.getRuntime().availableProcessors(),
                ModConfig.ASYNC.enabled ? effectiveThreads() : 0);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandChunkFlow());
    }

    /** Number of worker threads the chunk I/O pool should use. */
    public static int effectiveThreads() {
        int max = Math.max(1, ModConfig.ASYNC.maxThreads);
        if (ModConfig.ASYNC.ioThreads > 0) {
            return Math.min(max, Math.max(1, ModConfig.ASYNC.ioThreads));
        }
        int cores = Runtime.getRuntime().availableProcessors();
        int auto = Math.max(2, cores - 1);
        return Math.min(max, auto);
    }

    /**
     * Sizes the chunk I/O pool to {@link #effectiveThreads()} if needed. Called from the mixin on every
     * load path (cheap: a couple of field reads), and from {@code adjustPoolSize} to stop Forge from
     * shrinking it back to one thread.
     */
    public static void ensurePoolSize(ThreadPoolExecutor pool) {
        poolRef = pool;
        if (!ModConfig.ASYNC.enabled) {
            return;
        }
        int target = effectiveThreads();
        if (pool.getCorePoolSize() == target && pool.getMaximumPoolSize() == target) {
            return;
        }
        int prevCore = pool.getCorePoolSize();
        int prevMax = pool.getMaximumPoolSize();
        try {
            pool.setCorePoolSize(target);
            pool.setMaximumPoolSize(target);
            LOGGER.info("[ChunkFlow] Chunk I/O pool sized to {} thread(s) (was core {}, max {}).",
                    target, prevCore, prevMax);
        } catch (Throwable t) {
            LOGGER.warn("[ChunkFlow] Could not resize chunk I/O pool to {}; leaving it alone.", target, t);
        }
    }

    public static ThreadPoolExecutor getPool() {
        return poolRef;
    }
}
