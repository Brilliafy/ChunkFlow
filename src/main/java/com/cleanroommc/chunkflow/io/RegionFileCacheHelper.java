package com.cleanroommc.chunkflow.io;

import com.cleanroommc.chunkflow.ChunkFlow;
import com.cleanroommc.chunkflow.config.ModConfig;
import net.minecraft.world.chunk.storage.RegionFile;
import net.minecraft.world.chunk.storage.RegionFileCache;
import sun.misc.Unsafe;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class RegionFileCacheHelper {

    private static final ThreadLocal<File> FLUSHING_WORLD_DIR = new ThreadLocal<>();

    private RegionFileCacheHelper() {
    }

    public static void beforeFlush(File worldDir) {
        FLUSHING_WORLD_DIR.set(worldDir);
        ChunkIOExecutorHelper.quiesceWorldDir(worldDir);
    }

    public static void afterFlush() {
        FLUSHING_WORLD_DIR.remove();
    }

    public static File getFlushingWorldDir() {
        return FLUSHING_WORLD_DIR.get();
    }

    /**
     * Replaces RegionFileCache.REGIONS_BY_FILE with a ConcurrentHashMap if it is not one already.
     */
    @SuppressWarnings("unchecked")
    public static void ensureConcurrentMap() {
        try {
            Field field = null;
            try {
                field = RegionFileCache.class.getDeclaredField("REGIONS_BY_FILE");
            } catch (NoSuchFieldException e) {
                try {
                    field = RegionFileCache.class.getDeclaredField("field_76553_a");
                } catch (NoSuchFieldException e2) {
                    for (Field f : RegionFileCache.class.getDeclaredFields()) {
                        if (Map.class.isAssignableFrom(f.getType())) {
                            field = f;
                            break;
                        }
                    }
                }
            }

            if (field == null) {
                ChunkFlow.LOGGER.warn("[ChunkFlow] Could not locate REGIONS_BY_FILE field on RegionFileCache.");
                return;
            }

            field.setAccessible(true);
            Object current = field.get(null);
            if (!(current instanceof ConcurrentMap)) {
                ConcurrentHashMap<File, RegionFile> newMap = new ConcurrentHashMap<>();
                if (current instanceof Map) {
                    newMap.putAll((Map<File, RegionFile>) current);
                }

                // Try Unsafe first
                try {
                    Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
                    unsafeField.setAccessible(true);
                    Unsafe unsafe = (Unsafe) unsafeField.get(null);
                    Object base = unsafe.staticFieldBase(field);
                    long offset = unsafe.staticFieldOffset(field);
                    unsafe.putObject(base, offset, newMap);
                    ChunkFlow.LOGGER.info("[ChunkFlow] Converted RegionFileCache.REGIONS_BY_FILE to ConcurrentHashMap via Unsafe.");
                    return;
                } catch (Throwable t) {
                    ChunkFlow.LOGGER.debug("[ChunkFlow] Unsafe replacement failed, falling back to reflection", t);
                }

                // Fallback reflection
                try {
                    Field modifiersField = Field.class.getDeclaredField("modifiers");
                    modifiersField.setAccessible(true);
                    modifiersField.setInt(field, field.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
                    field.set(null, newMap);
                    ChunkFlow.LOGGER.info("[ChunkFlow] Converted RegionFileCache.REGIONS_BY_FILE to ConcurrentHashMap via reflection.");
                } catch (Throwable t) {
                    ChunkFlow.LOGGER.warn("[ChunkFlow] Could not set ConcurrentHashMap on REGIONS_BY_FILE", t);
                }
            }
        } catch (Throwable t) {
            ChunkFlow.LOGGER.warn("[ChunkFlow] Failed to inspect RegionFileCache.REGIONS_BY_FILE", t);
        }
    }

    /**
     * Safely clears region file references without throwing ConcurrentModificationException,
     * and only closes files for the flushing world directory if a specific world is flushing.
     */
    public static void clearRegionFileReferencesSafely(Map<File, RegionFile> map) {
        File flushingDir = FLUSHING_WORLD_DIR.get();
        if (flushingDir != null) {
            String prefix = flushingDir.getAbsolutePath();
            Iterator<Map.Entry<File, RegionFile>> iterator = map.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<File, RegionFile> entry = iterator.next();
                File file = entry.getKey();
                if (file != null && (file.getAbsolutePath().startsWith(prefix) || file.getAbsolutePath().contains(prefix))) {
                    iterator.remove();
                    RegionFile rf = entry.getValue();
                    if (rf != null) {
                        try {
                            synchronized (rf) {
                                rf.close();
                            }
                        } catch (IOException e) {
                            ChunkFlow.LOGGER.warn("[ChunkFlow] Error closing region file: {}", file, e);
                        }
                    }
                }
            }
        } else {
            // Global clear (e.g. server shutdown)
            Iterator<Map.Entry<File, RegionFile>> iterator = map.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<File, RegionFile> entry = iterator.next();
                iterator.remove();
                RegionFile rf = entry.getValue();
                if (rf != null) {
                    try {
                        synchronized (rf) {
                            rf.close();
                        }
                    } catch (IOException e) {
                        ChunkFlow.LOGGER.warn("[ChunkFlow] Error closing region file: {}", entry.getKey(), e);
                    }
                }
            }
        }
    }

    /**
     * Trims idle / closed region files if cache size exceeds maxCacheSize.
     */
    public static void trimCacheIfNeeded(Map<File, RegionFile> map) {
        int max = ModConfig.REGION.maxCacheSize;
        if (map.size() < max) {
            return;
        }

        long now = System.currentTimeMillis();
        long idleThreshold = 30000L; // 30 seconds
        int targetRemove = map.size() - (max * 3 / 4);

        Iterator<Map.Entry<File, RegionFile>> it = map.entrySet().iterator();
        while (it.hasNext() && targetRemove > 0) {
            Map.Entry<File, RegionFile> entry = it.next();
            RegionFile rf = entry.getValue();
            if (rf == null || (rf instanceof IRegionFile && ((IRegionFile) rf).chunkflow$isClosed())) {
                it.remove();
                targetRemove--;
            } else if (rf instanceof IRegionFile && (now - ((IRegionFile) rf).chunkflow$getLastAccess() > idleThreshold)) {
                it.remove();
                try {
                    synchronized (rf) {
                        rf.close();
                    }
                } catch (IOException ignored) {
                }
                targetRemove--;
            }
        }
    }
}
