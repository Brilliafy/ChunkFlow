package com.cleanroommc.chunkflow.directional;

/**
 * Pure directional priority math. No Minecraft types, no player state: safe to call from
 * a comparator and trivially unit-testable.
 */
public final class DirectionalScore {

    private DirectionalScore() {
    }

    /**
     * Deterministic, transitive sort key. Non-finite inputs collapse to the unbiased
     * squared distance, so a comparator over this value cannot violate the TimSort
     * contract.
     */
    public static double compute(double cx, double cz, double px, double pz,
                                 double vx, double vz, double beta, double thresholdSq) {
        double dx = cx - px;
        double dz = cz - pz;
        double distSq = dx * dx + dz * dz;
        if (!Double.isFinite(distSq)) {
            return Double.POSITIVE_INFINITY;
        }

        double speedSq = vx * vx + vz * vz;
        if (!Double.isFinite(speedSq) || speedSq <= thresholdSq) {
            return distSq;
        }

        double speed = Math.sqrt(speedSq);
        double dist = Math.sqrt(distSq);
        if (dist <= 0.001 || speed <= 0.0) {
            return distSq;
        }

        double cosTheta = (dx * vx + dz * vz) / (dist * speed);
        if (Double.isNaN(cosTheta)) {
            return distSq;
        }
        if (cosTheta < -1.0) {
            cosTheta = -1.0;
        } else if (cosTheta > 1.0) {
            cosTheta = 1.0;
        }

        double speedFactor = Math.min(1.0, speed * 2.0);
        double multiplier = 1.0 - (beta * speedFactor * cosTheta);
        if (!Double.isFinite(multiplier) || multiplier < 0.1) {
            multiplier = 0.1;
        }

        double score = distSq * multiplier * multiplier;
        return Double.isFinite(score) ? score : distSq;
    }
}
