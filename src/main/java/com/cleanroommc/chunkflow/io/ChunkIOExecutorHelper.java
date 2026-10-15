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
import java.util.concurrent.ThreadPoolExecutor;

public final class ChunkIOExecutorHelper {

    private static Map<?, ?> tasksMap;
    private static ThreadPoolExecutor poolInstance;
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

            Field pField = ChunkIOExecutor.class.getDeclaredField("pool");
            pField.setAccessible(true);
            poolInstance = (ThreadPoolExecutor) pField.get(null);

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

    /**
     * Quiesce/drain in-flight chunk I/O tasks for a specific world (or all worlds if world is null).
     */
    public static void quiesceWorld(World world) {
        initReflection();
        if (tasksMap == null || poolInstance == null) {
            return;
        }

        List<Object> tasksToWait = new ArrayList<>();

        for (Map.Entry<?, ?> entry : tasksMap.entrySet()) {
            Object key = entry.getKey();
            Object provider = entry.getValue();
            if (key == null || provider == null) {
                continue;
            }

            try {
                World taskWorld = (World) worldField.get(key);
                if (world == null || taskWorld == world) {
                    Runnable runnable = (Runnable) provider;
                    if (!poolInstance.remove(runnable)) {
                        tasksToWait.add(provider);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        for (Object provider : tasksToWait) {
            synchronized (provider) {
                long deadline = System.currentTimeMillis() + 1000L;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        Boolean finished = (Boolean) runFinishedMethod.invoke(provider);
                        if (finished != null && finished) {
                            break;
                        }
                        provider.wait(50L);
                    } catch (Throwable ignored) {
                        break;
                    }
                }
            }
        }
    }

    /**
     * Quiesce/drain in-flight chunk I/O tasks belonging to the specified world directory.
     */
    public static void quiesceWorldDir(File worldDir) {
        initReflection();
        if (tasksMap == null || poolInstance == null || worldDir == null) {
            quiesceWorld(null);
            return;
        }

        List<Object> tasksToWait = new ArrayList<>();
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
                if (matches) {
                    Runnable runnable = (Runnable) provider;
                    if (!poolInstance.remove(runnable)) {
                        tasksToWait.add(provider);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        for (Object provider : tasksToWait) {
            synchronized (provider) {
                long deadline = System.currentTimeMillis() + 1000L;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        Boolean finished = (Boolean) runFinishedMethod.invoke(provider);
                        if (finished != null && finished) {
                            break;
                        }
                        provider.wait(50L);
                    } catch (Throwable ignored) {
                        break;
                    }
                }
            }
        }
    }

    public static void quiesceAll() {
        quiesceWorld(null);
    }
}
