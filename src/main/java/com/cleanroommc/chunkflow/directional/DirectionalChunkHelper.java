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

        double minEffectiveDist = Double.MAX_VALUE;

        double cx = (pos.x << 4) + 8.0;
        double cz = (pos.z << 4) + 8.0;

        for (int i = 0; i < players.size(); i++) {
            EntityPlayerMP player = players.get(i);
            if (player == null) {
                continue;
            }

            double px = player.posX;
            double pz = player.posZ;

            double dx = cx - px;
            double dz = cz - pz;
            double distSq = dx * dx + dz * dz;

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
            double speedSq = vx * vx + vz * vz;

            double effectiveDist = distSq;

            if (speedSq > ModConfig.DIRECTIONAL.minVelocityThresholdSq) {
                DIRECTIONAL_BIASED_CALLS.incrementAndGet();

                double speed = Math.sqrt(speedSq);
                double dist = Math.sqrt(distSq);

                if (dist > 0.001) {
                    double cosTheta = (dx * vx + dz * vz) / (dist * speed);

                    if (cosTheta > 0.2) {
                        FORWARD_CHUNKS_BOOSTED.incrementAndGet();
                    }

                    double speedFactor = Math.min(1.0, speed * 2.0);
                    double multiplier = 1.0 - (ModConfig.DIRECTIONAL.velocityWeightBeta * speedFactor * cosTheta);
                    if (multiplier < 0.1) {
                        multiplier = 0.1;
                    }

                    effectiveDist = distSq * (multiplier * multiplier);
                }
            }

            if (effectiveDist < minEffectiveDist) {
                minEffectiveDist = effectiveDist;
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
