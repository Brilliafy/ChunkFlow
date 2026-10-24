package com.cleanroommc.chunkflow.mixin;

import com.cleanroommc.chunkflow.io.IRegionFile;
import com.cleanroommc.chunkflow.io.RegionFileCacheHelper;
import net.minecraft.world.chunk.storage.RegionFile;
import net.minecraft.world.chunk.storage.RegionFileCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(RegionFileCache.class)
public abstract class RegionFileCacheMixin {

    @Shadow
    @Final
    @Mutable
    private static Map<File, RegionFile> REGIONS_BY_FILE;

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void chunkflow$onClinit(CallbackInfo ci) {
        REGIONS_BY_FILE = new ConcurrentHashMap<>();
    }

    @Inject(method = "createOrLoadRegionFile", at = @At("HEAD"), cancellable = true)
    private static void chunkflow$createOrLoadRegionFile(File worldDir, int chunkX, int chunkZ, CallbackInfoReturnable<RegionFile> cir) {
        File regionDir = new File(worldDir, "region");
        File file = new File(regionDir, "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
        RegionFile existing = REGIONS_BY_FILE.get(file);
        if (existing != null && !chunkflow$isClosed(existing)) {
            chunkflow$touch(existing);
            cir.setReturnValue(existing);
            return;
        }

        if (!regionDir.exists()) {
            regionDir.mkdirs();
        }

        RegionFileCacheHelper.trimCacheIfNeeded(REGIONS_BY_FILE);

        RegionFile created = new RegionFile(file);
        chunkflow$touch(created);
        while (true) {
            RegionFile current = REGIONS_BY_FILE.get(file);
            if (current != null && !chunkflow$isClosed(current)) {
                closeQuietly(created);
                chunkflow$touch(current);
                cir.setReturnValue(current);
                return;
            }
            boolean replaced;
            if (current == null) {
                replaced = REGIONS_BY_FILE.putIfAbsent(file, created) == null;
            } else {
                replaced = REGIONS_BY_FILE.replace(file, current, created);
                if (replaced) {
                    closeQuietly(current);
                }
            }
            if (replaced) {
                chunkflow$touch(created);
                cir.setReturnValue(created);
                return;
            }
        }
    }

    @Inject(method = "getRegionFileIfExists", at = @At("HEAD"), cancellable = true)
    private static void chunkflow$getRegionFileIfExists(File worldDir, int chunkX, int chunkZ, CallbackInfoReturnable<RegionFile> cir) {
        File regionDir = new File(worldDir, "region");
        File file = new File(regionDir, "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
        RegionFile existing = REGIONS_BY_FILE.get(file);
        if (existing != null && !chunkflow$isClosed(existing)) {
            chunkflow$touch(existing);
            cir.setReturnValue(existing);
            return;
        }
        if (!regionDir.exists() || !file.exists()) {
            cir.setReturnValue(null);
            return;
        }

        RegionFileCacheHelper.trimCacheIfNeeded(REGIONS_BY_FILE);

        RegionFile created = new RegionFile(file);
        chunkflow$touch(created);
        while (true) {
            RegionFile current = REGIONS_BY_FILE.get(file);
            if (current != null && !chunkflow$isClosed(current)) {
                closeQuietly(created);
                chunkflow$touch(current);
                cir.setReturnValue(current);
                return;
            }
            boolean replaced;
            if (current == null) {
                replaced = REGIONS_BY_FILE.putIfAbsent(file, created) == null;
            } else {
                replaced = REGIONS_BY_FILE.replace(file, current, created);
                if (replaced) {
                    closeQuietly(current);
                }
            }
            if (replaced) {
                chunkflow$touch(created);
                cir.setReturnValue(created);
                return;
            }
        }
    }

    private static boolean chunkflow$isClosed(RegionFile file) {
        return file instanceof IRegionFile && ((IRegionFile) file).chunkflow$isClosed();
    }

    private static void chunkflow$touch(RegionFile file) {
        if (file instanceof IRegionFile) {
            ((IRegionFile) file).chunkflow$touch();
        }
    }

    private static void closeQuietly(RegionFile file) {
        if (file == null) {
            return;
        }
        try {
            file.close();
        } catch (java.io.IOException ignored) {
        }
    }

    @Inject(method = "clearRegionFileReferences", at = @At("HEAD"), cancellable = true)
    private static void chunkflow$clearRegionFileReferences(CallbackInfo ci) {
        RegionFileCacheHelper.clearRegionFileReferencesSafely(REGIONS_BY_FILE);
        ci.cancel();
    }
}
