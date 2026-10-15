package com.cleanroommc.chunkflow.io;

import com.cleanroommc.chunkflow.ChunkFlow;
import net.minecraft.world.World;
import net.minecraftforge.common.chunkio.ChunkIOExecutor;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Helper to gracefully await and drain in-flight chunk I/O worker reads before dimension unloads
 * or server stopping.
 *
 * <p>Strictly respects Forge's ChunkIOProvider contract: does NOT remove queued tasks or manually
 * invoke callbacks from outside. The synchronized RegionFile.close() and ConcurrentHashMap in
 * RegionFileCache remain the authoritative safety net against races.</p>
 */
public final class ChunkIOExecutorHelper {

    private static Map<?, ?> tasksMap;
    private static Field worldField;
    private static Method runFinishedMethod;
    private static boolean reflectionInitialized = false;

    private ChunkIOExecutorHelper() {
    }

    private static synchronized void initReflection() {
        if (reflectionInitialized) {
            return;
        }
        try {
            Field tasksField = ChunkIOExecutor.class.getDeclaredField("tasks");
            tasksField.setAccessible(true);
            tasksMap = (Map<?, ?>) tasksField.get(null);

            Class<?> queuedChunkClass = Class.forName("net.minecraftforge.common.chunkio.QueuedChunk");
            worldField = queuedChunkClass.getDeclaredField("world");
            worldField.setAccessible(true);

            Class<?> providerClass = Class.forName("net.minecraftforge.common.chunkio.ChunkIOProvider");
            runFinishedMethod = providerClass.getDeclaredMethod("runFinished");
            runFinishedMethod.setAccessible(true);

            reflectionInitialized = true;
        } catch (Throwable t) {
            ChunkFlow.LOGGER.error("[ChunkFlow] Failed to reflect ChunkIOExecutor fields", t);
        }
    }

    private static boolean isRunFinished(Object provider) {
        if (runFinishedMethod != null && provider != null) {
            try {
                Boolean res = (Boolean) runFinishedMethod.invoke(provider);
                return res != null && res;
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    /**
     * Awaits in-flight chunk I/O worker reads for a specific world before it is unloaded or flushed.
     * Does NOT alter task state or dequeue tasks, preserving Forge's callback lifecycle.
     */
    public static void drainWorldTasks(World world) {
        initReflection();
        if (tasksMap == null || worldField == null) {
            return;
        }

        List<Object> inFlightProviders = new ArrayList<>();

        for (Map.Entry<?, ?> entry : tasksMap.entrySet()) {
            Object key = entry.getKey();
            Object provider = entry.getValue();
            if (key == null || provider == null) {
                continue;
            }

            try {
                World taskWorld = (World) worldField.get(key);
                if (world == null || taskWorld == world) {
                    if (!isRunFinished(provider)) {
                        inFlightProviders.add(provider);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        for (Object provider : inFlightProviders) {
            synchronized (provider) {
                long deadline = System.currentTimeMillis() + 500L;
                while (!isRunFinished(provider) && System.currentTimeMillis() < deadline) {
                    try {
                        provider.wait(50L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    /**
     * Awaits in-flight chunk I/O worker reads belonging to the specified world save directory.
     */
    public static void drainWorldTasks(File worldDir) {
        initReflection();
        if (tasksMap == null || worldField == null || worldDir == null) {
            drainWorldTasks((World) null);
            return;
        }

        List<Object> inFlightProviders = new ArrayList<>();
        String targetPath = worldDir.getAbsolutePath();

        for (Map.Entry<?, ?> entry : tasksMap.entrySet()) {
            Object key = entry.getKey();
            Object provider = entry.getValue();
            if (key == null || provider == null) {
                continue;
            }

            try {
                World taskWorld = (World) worldField.get(key);
                boolean matches = false;
                if (taskWorld != null && taskWorld.getSaveHandler() != null) {
                    File saveDir = taskWorld.getSaveHandler().getWorldDirectory();
                    if (saveDir != null && (saveDir.getAbsolutePath().equals(targetPath) || saveDir.getAbsolutePath().startsWith(targetPath))) {
                        matches = true;
                    }
                }
                if (matches && !isRunFinished(provider)) {
                    inFlightProviders.add(provider);
                }
            } catch (Throwable ignored) {
            }
        }

        for (Object provider : inFlightProviders) {
            synchronized (provider) {
                long deadline = System.currentTimeMillis() + 500L;
                while (!isRunFinished(provider) && System.currentTimeMillis() < deadline) {
                    try {
                        provider.wait(50L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    public static void drainAll() {
        drainWorldTasks((World) null);
        try {
            ChunkIOExecutor.tick();
        } catch (Throwable ignored) {
        }
    }
}
