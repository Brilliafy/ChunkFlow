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
        File file1 = new File(worldDir, "region");
        File file2 = new File(file1, "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
        RegionFile regionfile = REGIONS_BY_FILE.get(file2);

        if (regionfile != null) {
            if (regionfile instanceof IRegionFile && ((IRegionFile) regionfile).chunkflow$isClosed()) {
                REGIONS_BY_FILE.remove(file2, regionfile);
            } else {
                if (regionfile instanceof IRegionFile) {
                    ((IRegionFile) regionfile).chunkflow$touch();
                }
                cir.setReturnValue(regionfile);
                return;
            }
        }

        if (!file1.exists()) {
            file1.mkdirs();
        }

        RegionFileCacheHelper.trimCacheIfNeeded(REGIONS_BY_FILE);

        RegionFile newRegionFile = new RegionFile(file2);
        RegionFile existing = REGIONS_BY_FILE.putIfAbsent(file2, newRegionFile);
        if (existing != null && !(existing instanceof IRegionFile && ((IRegionFile) existing).chunkflow$isClosed())) {
            try {
                newRegionFile.close();
            } catch (IOException ignored) {
            }
            if (existing instanceof IRegionFile) {
                ((IRegionFile) existing).chunkflow$touch();
            }
            cir.setReturnValue(existing);
        } else {
            if (existing != null) {
                REGIONS_BY_FILE.put(file2, newRegionFile);
            }
            if (newRegionFile instanceof IRegionFile) {
                ((IRegionFile) newRegionFile).chunkflow$touch();
            }
            cir.setReturnValue(newRegionFile);
        }
    }

    @Inject(method = "getRegionFileIfExists", at = @At("HEAD"), cancellable = true)
    private static void chunkflow$getRegionFileIfExists(File worldDir, int chunkX, int chunkZ, CallbackInfoReturnable<RegionFile> cir) {
        File file1 = new File(worldDir, "region");
        File file2 = new File(file1, "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
        RegionFile regionfile = REGIONS_BY_FILE.get(file2);

        if (regionfile != null) {
            if (regionfile instanceof IRegionFile && ((IRegionFile) regionfile).chunkflow$isClosed()) {
                REGIONS_BY_FILE.remove(file2, regionfile);
            } else {
                if (regionfile instanceof IRegionFile) {
                    ((IRegionFile) regionfile).chunkflow$touch();
                }
                cir.setReturnValue(regionfile);
                return;
            }
        }

        if (file1.exists() && file2.exists()) {
            RegionFileCacheHelper.trimCacheIfNeeded(REGIONS_BY_FILE);

            RegionFile newRegionFile = new RegionFile(file2);
            RegionFile existing = REGIONS_BY_FILE.putIfAbsent(file2, newRegionFile);
            if (existing != null && !(existing instanceof IRegionFile && ((IRegionFile) existing).chunkflow$isClosed())) {
                try {
                    newRegionFile.close();
                } catch (IOException ignored) {
                }
                if (existing instanceof IRegionFile) {
                    ((IRegionFile) existing).chunkflow$touch();
                }
                cir.setReturnValue(existing);
            } else {
                if (existing != null) {
                    REGIONS_BY_FILE.put(file2, newRegionFile);
                }
                if (newRegionFile instanceof IRegionFile) {
                    ((IRegionFile) newRegionFile).chunkflow$touch();
                }
                cir.setReturnValue(newRegionFile);
            }
        } else {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "clearRegionFileReferences", at = @At("HEAD"), cancellable = true)
    private static void chunkflow$clearRegionFileReferences(CallbackInfo ci) {
        RegionFileCacheHelper.clearRegionFileReferencesSafely(REGIONS_BY_FILE);
        ci.cancel();
    }
}
