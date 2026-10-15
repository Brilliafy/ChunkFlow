package com.cleanroommc.chunkflow;

import com.cleanroommc.chunkflow.io.RegionFileCacheHelper;
import zone.rong.mixinbooter.ILateMixinLoader;

import java.util.ArrayList;
import java.util.List;

public class ChunkFlowLateMixinLoader implements ILateMixinLoader {

    static {
        // Run as early as possible during MixinBooter late-mixin discovery
        RegionFileCacheHelper.ensureConcurrentMap();
    }

    @Override
    public List<String> getMixinConfigs() {
        RegionFileCacheHelper.ensureConcurrentMap();
        List<String> configs = new ArrayList<>();
        configs.add("mixins.chunkflow.json");
        return configs;
    }
}
