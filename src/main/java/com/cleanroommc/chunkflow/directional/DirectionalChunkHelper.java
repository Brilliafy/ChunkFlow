package com.cleanroommc.chunkflow.directional;

import com.cleanroommc.chunkflow.config.ModConfig;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.ChunkPos;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class DirectionalChunkHelper {

    public static final AtomicLong TOTAL_CALLS = new AtomicLong(0);
    public static final AtomicLong DIRECTIONAL_BIASED_CALLS = new AtomicLong(0);
    public static final AtomicLong FORWARD_CHUNKS_BOOSTED = new AtomicLong(0);

    public static double getDirectionalDistance(ChunkPos pos, List<EntityPlayerMP> players) {
        TOTAL_CALLS.incrementAndGet();

        double cx = (pos.x << 4) + 8.0;
        double cz = (pos.z << 4) + 8.0;
        double beta = ModConfig.DIRECTIONAL.velocityWeightBeta;
        double thresholdSq = ModConfig.DIRECTIONAL.minVelocityThresholdSq;
        double minEffectiveDist = Double.POSITIVE_INFINITY;

        for (int i = 0; i < players.size(); i++) {
            EntityPlayerMP player = players.get(i);
            if (player == null) {
                continue;
            }

            double vx;
            double vz;
            net.minecraft.entity.Entity vehicle = player.getRidingEntity();
            if (vehicle != null) {
                vx = vehicle.posX - vehicle.prevPosX;
                vz = vehicle.posZ - vehicle.prevPosZ;
                if (vx == 0.0 && vz == 0.0) {
                    vx = vehicle.motionX;
                    vz = vehicle.motionZ;
                }
            } else {
                vx = player.posX - player.prevPosX;
                vz = player.posZ - player.prevPosZ;
                if (vx == 0.0 && vz == 0.0) {
                    vx = player.motionX;
                    vz = player.motionZ;
                }
            }

            if (vx * vx + vz * vz > thresholdSq) {
                DIRECTIONAL_BIASED_CALLS.incrementAndGet();
            }
            double score = DirectionalScore.compute(cx, cz, player.posX, player.posZ, vx, vz, beta, thresholdSq);
            if (score < minEffectiveDist) {
                minEffectiveDist = score;
            }
        }

        return minEffectiveDist;
    }

    public static void clearStats() {
        TOTAL_CALLS.set(0);
        DIRECTIONAL_BIASED_CALLS.set(0);
        FORWARD_CHUNKS_BOOSTED.set(0);
    }
}
