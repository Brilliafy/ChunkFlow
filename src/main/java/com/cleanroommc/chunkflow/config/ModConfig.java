package com.cleanroommc.chunkflow.config;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Config(modid = "chunkflow", name = "chunkflow")
public class ModConfig {

    @Config.Name("Async Chunk I/O")
    @Config.Comment("Parallelisation of Forge's asynchronous chunk loading pool (ChunkIOExecutor).")
    public static AsyncIO ASYNC = new AsyncIO();

    @Config.Name("Region File I/O")
    @Config.Comment("High-throughput region file read path with pooled zlib.")
    public static RegionIO REGION = new RegionIO();

    @Config.Name("Directional Chunks")
    @Config.Comment("Velocity-projected chunk loading cone: bias chunk send order towards a moving player's heading.")
    public static Directional DIRECTIONAL = new Directional();

    @Config.Comment("Log pool resizing and thread counts to the game log.")
    @Config.Name("Debug Logging")
    public static boolean debugLogging = false;

    public static class AsyncIO {
        @Config.Comment("Master toggle for async chunk I/O parallelism. When disabled, Forge's original single-threaded pool behaviour is used.")
        @Config.Name("Enable")
        public boolean enabled = true;

        @Config.Comment("Number of chunk I/O worker threads. 0 = automatic (CPU cores - 1, capped at 4 for stability), or specify a positive count.")
        @Config.RangeInt(min = 0, max = 64)
        @Config.Name("I/O Threads")
        public int ioThreads = 0;

        @Config.Comment("Upper bound for the automatic thread count and for the I/O Threads setting.")
        @Config.RangeInt(min = 1, max = 64)
        @Config.Name("Max Threads")
        public int maxThreads = 8;

        @Config.Comment("Minimum chunk I/O worker threads when scaling with player count is enabled.")
        @Config.RangeInt(min = 1, max = 64)
        @Config.Name("Min Threads")
        public int minThreads = 2;

        @Config.Comment("Dynamically scale worker thread count between Min Threads and Max Threads based on player count. When false, effective target threads are always maintained.")
        @Config.Name("Scale With Players")
        public boolean scaleWithPlayers = false;
    }

    public static class RegionIO {
        @Config.Comment("Master toggle for the fast region read path. When disabled, vanilla's InflaterInputStream path is used.")
        @Config.Name("Enable")
        public boolean enabled = true;

        @Config.Comment("Use pooled zlib Inflater instances instead of allocating one per chunk.")
        @Config.Name("Enable Zlib Pooling")
        public boolean enableZlibPooling = true;

        @Config.Comment("Use the fast custom decompression path in RegionFile#getChunkDataInputStream.")
        @Config.Name("Enable Fast Decompression")
        public boolean enableFastDecompression = true;

        @Config.Comment("Maximum number of region files to keep open in the cache before evicting idle files. Vanilla is 256 (which wipes all files). ChunkFlow cleanly trims only idle files.")
        @Config.RangeInt(min = 64, max = 4096)
        @Config.Name("Max Cache Size")
        public int maxCacheSize = 512;
    }

    public static class Directional {
        @Config.Comment("Master toggle to enable/disable directional chunk loading and network prioritization.")
        @Config.Name("Enable Mod")
        public boolean enabled = true;

        @Config.Comment("Velocity bias factor beta (0.1 to 0.9). Higher values strongly favor chunks directly ahead in the flight direction.")
        @Config.RangeDouble(min = 0.1, max = 0.9)
        @Config.Name("Velocity Bias Weight")
        public double velocityWeightBeta = 0.6;

        @Config.Comment("Minimum velocity squared (in blocks/tick squared) required to activate directional bias.")
        @Config.RangeDouble(min = 0.01, max = 1.0)
        @Config.Name("Min Velocity Threshold Sq")
        public double minVelocityThresholdSq = 0.04;

        @Config.Comment("Enable debug console logging.")
        @Config.Name("Debug Logging")
        public boolean debugLogging = false;
    }

    @Mod.EventBusSubscriber(modid = "chunkflow")
    public static class ConfigSyncHandler {
        @SubscribeEvent
        public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
            if (event.getModID().equals("chunkflow")) {
                ConfigManager.sync("chunkflow", Config.Type.INSTANCE);
            }
        }
    }
}
