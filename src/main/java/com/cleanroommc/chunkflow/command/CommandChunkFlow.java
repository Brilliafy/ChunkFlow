package com.cleanroommc.chunkflow.command;

import com.cleanroommc.chunkflow.ChunkFlow;
import com.cleanroommc.chunkflow.config.ModConfig;
import com.cleanroommc.chunkflow.directional.DirectionalChunkHelper;
import com.cleanroommc.chunkflow.io.FastZlibPool;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

public class CommandChunkFlow extends CommandBase {

    @Override
    public String getName() {
        return "chunkflow";
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("chunkpipe", "cpipeline");
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/chunkflow [stats|threads <n>|toggle|region [stats|toggle|clear]|directional [stats|toggle|clear]|reload]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        String sub = args.length == 0 ? "stats" : args[0].toLowerCase();

        if (sub.equals("stats")) {
            printAsyncStats(sender);
            printRegionStats(sender);
            return;
        }

        if (sub.equals("threads")) {
            if (args.length < 2) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: /chunkflow threads <0=auto|n>"));
                return;
            }
            int value;
            try {
                value = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "Not a number: " + args[1]));
                return;
            }
            ModConfig.ASYNC.ioThreads = Math.max(0, Math.min(64, value));
            ConfigManager.sync(ChunkFlow.MOD_ID, Config.Type.INSTANCE);
            resize();
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Chunk I/O threads set to " +
                    (ModConfig.ASYNC.ioThreads > 0 ? ModConfig.ASYNC.ioThreads : "auto (" + ChunkFlow.effectiveThreads() + ")")));
            return;
        }

        if (sub.equals("toggle")) {
            ModConfig.ASYNC.enabled = !ModConfig.ASYNC.enabled;
            ConfigManager.sync(ChunkFlow.MOD_ID, Config.Type.INSTANCE);
            resize();
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Async chunk I/O now: " +
                    (ModConfig.ASYNC.enabled ? TextFormatting.GREEN + "ENABLED" : TextFormatting.RED + "DISABLED")));
            return;
        }

        if (sub.equals("region")) {
            String action = args.length < 2 ? "stats" : args[1].toLowerCase();
            if (action.equals("stats")) {
                printRegionStats(sender);
            } else if (action.equals("toggle")) {
                ModConfig.REGION.enabled = !ModConfig.REGION.enabled;
                ConfigManager.sync(ChunkFlow.MOD_ID, Config.Type.INSTANCE);
                sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Fast region I/O now: " +
                        (ModConfig.REGION.enabled ? TextFormatting.GREEN + "ENABLED" : TextFormatting.RED + "DISABLED")));
            } else if (action.equals("clear")) {
                FastZlibPool.clearStats();
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Region I/O statistics reset."));
            } else {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: " + getUsage(sender)));
            }
            return;
        }

        if (sub.equals("directional")) {
            String action = args.length < 2 ? "stats" : args[1].toLowerCase();
            if (action.equals("stats")) {
                printDirectionalStats(sender);
            } else if (action.equals("toggle")) {
                ModConfig.DIRECTIONAL.enabled = !ModConfig.DIRECTIONAL.enabled;
                sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Directional Chunks now: " +
                        (ModConfig.DIRECTIONAL.enabled ? TextFormatting.GREEN + "ENABLED" : TextFormatting.RED + "DISABLED")));
            } else if (action.equals("clear")) {
                DirectionalChunkHelper.clearStats();
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Directional Chunks statistics reset."));
            } else {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: " + getUsage(sender)));
            }
            return;
        }

        if (sub.equals("reload")) {
            ConfigManager.sync(ChunkFlow.MOD_ID, Config.Type.INSTANCE);
            resize();
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "ChunkFlow configuration reloaded."));
            return;
        }

        sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: " + getUsage(sender)));
    }

    private static void resize() {
        ThreadPoolExecutor pool = ChunkFlow.getPool();
        if (pool != null) {
            ChunkFlow.ensurePoolSize(pool);
        }
    }

    private static void printAsyncStats(ICommandSender sender) {
        sender.sendMessage(new TextComponentString(TextFormatting.GOLD + "=== ChunkFlow: Async Chunk I/O ==="));
        sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Status: " +
                (ModConfig.ASYNC.enabled ? TextFormatting.GREEN + "ACTIVE" : TextFormatting.RED + "DISABLED") +
                TextFormatting.GRAY + " | Configured threads: " + TextFormatting.WHITE +
                (ModConfig.ASYNC.ioThreads > 0 ? ModConfig.ASYNC.ioThreads : "auto")
                + TextFormatting.GRAY + " (target " + ChunkFlow.effectiveThreads() + ", max " + ModConfig.ASYNC.maxThreads + ")"));

        ThreadPoolExecutor pool = ChunkFlow.getPool();
        if (pool != null) {
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Live pool -> core: " +
                    TextFormatting.WHITE + pool.getCorePoolSize()
                    + TextFormatting.GREEN + ", max: " + TextFormatting.WHITE + pool.getMaximumPoolSize()
                    + TextFormatting.GREEN + ", active: " + TextFormatting.WHITE + pool.getActiveCount()));
            sender.sendMessage(new TextComponentString(TextFormatting.LIGHT_PURPLE + "Chunks loaded off-thread: " +
                    TextFormatting.WHITE + pool.getCompletedTaskCount()
                    + TextFormatting.LIGHT_PURPLE + ", queued: " + TextFormatting.WHITE + pool.getQueue().size()));
        } else {
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "Chunk I/O pool not touched yet this session (no chunk loaded)."));
        }
    }

    private static void printRegionStats(ICommandSender sender) {
        sender.sendMessage(new TextComponentString(TextFormatting.GOLD + "=== ChunkFlow: Region File I/O ==="));
        sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Status: " +
                (ModConfig.REGION.enabled ? TextFormatting.GREEN + "ACTIVE" : TextFormatting.RED + "DISABLED") +
                TextFormatting.GRAY + " | Zlib pooling: " +
                (ModConfig.REGION.enableZlibPooling ? TextFormatting.GREEN + "ON" : TextFormatting.RED + "OFF")));
        sender.sendMessage(new TextComponentString(TextFormatting.AQUA + "Chunks Decompressed: " + TextFormatting.WHITE +
                FastZlibPool.TOTAL_DECOMPRESSED_CHUNKS.get()));
        sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Chunks Compressed: " + TextFormatting.WHITE +
                FastZlibPool.TOTAL_COMPRESSED_CHUNKS.get()));
        sender.sendMessage(new TextComponentString(TextFormatting.LIGHT_PURPLE + "Zlib Compressor / Decompressor Reuses: " +
                TextFormatting.WHITE + FastZlibPool.ZLIB_REUSES.get()));
    }

    private static void printDirectionalStats(ICommandSender sender) {
        long total = DirectionalChunkHelper.TOTAL_CALLS.get();
        long biased = DirectionalChunkHelper.DIRECTIONAL_BIASED_CALLS.get();
        long boosted = DirectionalChunkHelper.FORWARD_CHUNKS_BOOSTED.get();

        double biasedPct = total > 0 ? (biased * 100.0 / total) : 0.0;

        sender.sendMessage(new TextComponentString(TextFormatting.GOLD + "=== Directional Chunks Statistics ==="));
        sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Status: " +
                (ModConfig.DIRECTIONAL.enabled ? TextFormatting.GREEN + "ACTIVE" : TextFormatting.RED + "DISABLED")));
        sender.sendMessage(new TextComponentString(TextFormatting.AQUA + "Total Distance Evaluations: " + TextFormatting.WHITE + total));
        sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Fast Flight Velocity Biased: " + TextFormatting.WHITE +
                biased + String.format(" (%.1f%%)", biasedPct)));
        sender.sendMessage(new TextComponentString(TextFormatting.LIGHT_PURPLE + "Forward Chunks Boosted to Front of Queue: " + TextFormatting.WHITE + boosted));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "stats", "threads", "toggle", "region", "directional", "reload");
        }
        if (args.length == 2 && "region".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "stats", "toggle", "clear");
        }
        if (args.length == 2 && "directional".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "stats", "toggle", "clear");
        }
        return Collections.emptyList();
    }
}
