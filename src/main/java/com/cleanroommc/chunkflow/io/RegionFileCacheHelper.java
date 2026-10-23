package com.cleanroommc.chunkflow.io;

import com.cleanroommc.chunkflow.ChunkFlow;
import com.cleanroommc.chunkflow.config.ModConfig;
import net.minecraft.world.chunk.storage.RegionFile;
import net.minecraft.world.chunk.storage.RegionFileCache;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.Map;

public final class RegionFileCacheHelper {

    private static final ThreadLocal<File> FLUSHING_WORLD_DIR = new ThreadLocal<>();

    private RegionFileCacheHelper() {
    }

    public static void beforeFlush(File worldDir) {
        FLUSHING_WORLD_DIR.set(worldDir);
        ChunkIOExecutorHelper.drainWorldTasks(worldDir);
    }

    public static void afterFlush() {
        FLUSHING_WORLD_DIR.remove();
    }

    public static File getFlushingWorldDir() {
        return FLUSHING_WORLD_DIR.get();
    }

    private static Field getRegionsByFileField() {
        try {
            return RegionFileCache.class.getDeclaredField("REGIONS_BY_FILE");
        } catch (NoSuchFieldException e) {
            try {
                return RegionFileCache.class.getDeclaredField("field_76553_a");
            } catch (NoSuchFieldException e2) {
                for (Field f : RegionFileCache.class.getDeclaredFields()) {
                    if (Map.class.isAssignableFrom(f.getType())) {
                        return f;
                    }
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Map<File, RegionFile> getRegionsMap() {
        Field field = getRegionsByFileField();
        if (field != null) {
            field.setAccessible(true);
            try {
                return (Map<File, RegionFile>) field.get(null);
            } catch (Throwable ignored) {
            }
        }
        return null;
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
                            rf.close();
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
                        rf.close();
                    } catch (IOException e) {
                        ChunkFlow.LOGGER.warn("[ChunkFlow] Error closing region file: {}", entry.getKey(), e);
                    }
                }
            }
        }
    }

    /**
     * Trims idle / closed region files if the cache exceeds maxCacheSize.
     *
     * <p>A file is only closed after winning the reader-lease CAS 0 -> closed. If the map
     * entry was concurrently replaced, the old file is closed on its own (no readers) and
     * the live entry is untouched.</p>
     */
    public static void trimCacheIfNeeded(Map<File, RegionFile> map) {
        int max = ModConfig.REGION.maxCacheSize;
        if (map.size() < max) {
            return;
        }

        long now = System.currentTimeMillis();
        long idleThreshold = 30000L;
        int targetRemove = map.size() - (max * 3 / 4);

        Iterator<Map.Entry<File, RegionFile>> it = map.entrySet().iterator();
        while (it.hasNext() && targetRemove > 0) {
            Map.Entry<File, RegionFile> entry = it.next();
            File file = entry.getKey();
            RegionFile rf = entry.getValue();
            if (rf == null) {
                it.remove();
                targetRemove--;
                continue;
            }
            if (rf instanceof IRegionFile && ((IRegionFile) rf).chunkflow$isClosed()) {
                if (map.remove(file, rf)) {
                    targetRemove--;
                }
                continue;
            }
            if (rf instanceof IRegionFile
                    && (now - ((IRegionFile) rf).chunkflow$getLastAccess()) > idleThreshold
                    && ((IRegionFile) rf).chunkflow$tryCloseLease()) {
                if (!map.remove(file, rf)) {
                    // Another thread replaced the entry; ours has no readers, close it.
                    try {
                        rf.close();
                    } catch (IOException ignored) {
                    }
                    continue;
                }
                try {
                    rf.close();
                } catch (IOException ignored) {
                }
                targetRemove--;
            }
        }
    }
}
