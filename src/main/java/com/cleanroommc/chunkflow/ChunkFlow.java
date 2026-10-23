package com.cleanroommc.chunkflow;

import com.cleanroommc.chunkflow.command.CommandChunkFlow;
import com.cleanroommc.chunkflow.config.ModConfig;
import com.cleanroommc.chunkflow.io.ChunkIOExecutorHelper;
import com.cleanroommc.chunkflow.io.RegionFileCacheHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
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
 *       there. Safe because per-region reads are synchronised in {@code RegionFile}, region file cache is
 *       backed by a thread-safe map with safe closing, and tasks are drained prior to dimension unloads.</li>
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
        MinecraftForge.EVENT_BUS.register(this);

        try {
            Class.forName("pregenerator.ChunkPregenerator");
            LOGGER.info("[ChunkFlow] Chunk-Pregenerator detected: concurrent region cache & safe file closing interop active.");
        } catch (ClassNotFoundException ignored) {
        }

        LOGGER.info("[ChunkFlow] Initialised. CPU cores: {}, target chunk I/O threads: {}.",
                Runtime.getRuntime().availableProcessors(),
                ModConfig.ASYNC.enabled ? effectiveThreads() : 0);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandChunkFlow());
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        LOGGER.info("[ChunkFlow] Server stopping: draining chunk I/O pool.");
        ChunkIOExecutorHelper.drainAll();
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld() != null) {
            ChunkIOExecutorHelper.drainWorldTasks(event.getWorld());
        }
    }

    /** Number of worker threads the chunk I/O pool should use. */
    public static int effectiveThreads() {
        int max = Math.max(1, ModConfig.ASYNC.maxThreads);
        if (ModConfig.ASYNC.ioThreads > 0) {
            return Math.min(max, Math.max(1, ModConfig.ASYNC.ioThreads));
        }
        int cores = Runtime.getRuntime().availableProcessors();
        int auto;
        if (cores <= 2) {
            auto = 1;
        } else {
            // Auto default capped at 4 for stability against I/O thrashing on sata/spindle drives
            auto = Math.min(4, cores - 1);
        }
        return Math.min(max, auto);
    }

    public static void adjustPoolSize(ThreadPoolExecutor pool, int players) {
        poolRef = pool;
        if (!ModConfig.ASYNC.enabled) {
            return;
        }
        int target;
        if (ModConfig.ASYNC.scaleWithPlayers) {
            int min = Math.max(1, ModConfig.ASYNC.minThreads);
            int max = effectiveThreads();
            target = Math.min(max, Math.max(min, min + (players / 10)));
        } else {
            target = effectiveThreads();
        }
        ensurePoolSize(pool, target);
    }

    /**
     * Sizes the chunk I/O pool to {@link #effectiveThreads()} if needed. Called from the mixin on every
     * load path (cheap: a couple of field reads), and from {@code adjustPoolSize} to stop Forge from
     * shrinking it back to one thread.
     */
    public static void ensurePoolSize(ThreadPoolExecutor pool) {
        ensurePoolSize(pool, effectiveThreads());
    }

    public static void ensurePoolSize(ThreadPoolExecutor pool, int target) {
        poolRef = pool;
        if (!ModConfig.ASYNC.enabled) {
            return;
        }
        if (pool.getCorePoolSize() == target && pool.getMaximumPoolSize() == target) {
            return;
        }
        int prevCore = pool.getCorePoolSize();
        int prevMax = pool.getMaximumPoolSize();
        try {
            pool.setCorePoolSize(target);
            pool.setMaximumPoolSize(target);
            if (ModConfig.debugLogging) {
                LOGGER.info("[ChunkFlow] Chunk I/O pool sized to {} thread(s) (was core {}, max {}).",
                        target, prevCore, prevMax);
            }
        } catch (Throwable t) {
            LOGGER.warn("[ChunkFlow] Could not resize chunk I/O pool to {}; leaving it alone.", target, t);
        }
    }

    public static ThreadPoolExecutor getPool() {
        return poolRef;
    }
}
