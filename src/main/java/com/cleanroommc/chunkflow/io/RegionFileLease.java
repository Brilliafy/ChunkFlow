package com.cleanroommc.chunkflow.io;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lock-free reader lease for a region file.
 *
 * <p>{@code state >= 0} is the number of active readers; {@code -1} means closed. A reader
 * increments with a CAS loop; the trimmer may only close a file by winning a CAS from 0 to
 * -1. That makes "close while a reader holds a reference" impossible without a lock.</p>
 */
public final class RegionFileLease {

    private final AtomicInteger state = new AtomicInteger(0);

    public boolean tryAcquire() {
        while (true) {
            int current = this.state.get();
            if (current < 0) {
                return false;
            }
            if (this.state.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void release() {
        while (true) {
            int current = this.state.get();
            if (current <= 0) {
                return;
            }
            if (this.state.compareAndSet(current, current - 1)) {
                return;
            }
        }
    }

    /** @return true only if this call moved the lease from idle to permanently closed. */
    public boolean tryClose() {
        return this.state.compareAndSet(0, -1);
    }

    /** Forces the closed state (global shutdown); existing readers still finish. */
    public void forceClose() {
        this.state.set(-1);
    }

    public boolean isClosed() {
        return this.state.get() < 0;
    }

    public int readers() {
        return Math.max(0, this.state.get());
    }
}
