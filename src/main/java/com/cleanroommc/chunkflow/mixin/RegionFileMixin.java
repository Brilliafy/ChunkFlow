package com.cleanroommc.chunkflow.mixin;

import com.cleanroommc.chunkflow.config.ModConfig;
import com.cleanroommc.chunkflow.io.FastZlibPool;
import com.cleanroommc.chunkflow.io.IRegionFile;
import com.cleanroommc.chunkflow.io.RegionChunkReader;
import com.cleanroommc.chunkflow.io.RegionFileLease;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.chunk.storage.RegionFile;
import net.minecraft.world.chunk.storage.RegionFileCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.concurrent.locks.StampedLock;

/**
 * Lock-free region reads for ChunkFlow.
 *
 * <p>Reads use positional {@link FileChannel#read(java.nio.ByteBuffer, long)} (pread64 on
 * Linux, overlapped read on Windows) and a {@link StampedLock} optimistic stamp over the
 * offset table. Writers keep vanilla's {@link RandomAccessFile} path but hold the write
 * stamp, so the shared file cursor is never touched by a reader.</p>
 *
 * <p>A {@link RegionFileLease} makes cache eviction safe: the trimmer can only close a file
 * by winning a CAS from 0 readers to closed, and a read that loses the race transparently
 * re-opens the live file through {@link RegionFileCache}. A missing chunk is therefore only
 * ever reported for a live file with offset == 0.</p>
 */
@Mixin(RegionFile.class)
public abstract class RegionFileMixin implements IRegionFile {

    @Unique
    private static ThreadLocal<Integer> SELF_HEAL_DEPTH;

    @Unique
    private static ThreadLocal<Integer> chunkflow$selfHealDepth() {
        ThreadLocal<Integer> depth = SELF_HEAL_DEPTH;
        if (depth == null) {
            depth = ThreadLocal.withInitial(() -> 0);
            SELF_HEAL_DEPTH = depth;
        }
        return depth;
    }

    @Shadow
    private RandomAccessFile dataFile;

    @Shadow
    @Final
    private File fileName;

    @Shadow
    private int[] offsets;

    @Shadow
    private List<Boolean> sectorFree;

    @Shadow
    protected abstract boolean outOfBounds(int x, int z);

    @Shadow
    private int getOffset(int x, int z) {
        throw new AssertionError();
    }

    @Shadow
    private void setOffset(int x, int z, int offset) throws IOException {
        throw new AssertionError();
    }

    @Shadow
    private void setChunkTimestamp(int x, int z, int timestamp) throws IOException {
        throw new AssertionError();
    }

    @Shadow
    private void write(int sectorNumber, byte[] data, int length) throws IOException {
        throw new AssertionError();
    }

    @Unique
    private volatile RegionFileLease chunkflow$lease;
    @Unique
    private volatile StampedLock chunkflow$lock;
    @Unique
    private volatile FileChannel chunkflow$channel;
    @Unique
    private volatile long chunkflow$lastAccess = System.currentTimeMillis();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunkflow$captureChannel(File file, CallbackInfo ci) {
        this.chunkflow$channel = this.dataFile != null ? this.dataFile.getChannel() : null;
    }

    /**
     * Unique-field initializers are not guaranteed to be merged into every target
     * constructor (observed on CleanMix: the field stayed null while the overwritten
     * methods were live). Create them lazily; the null path runs once per instance.
     */
    @Unique
    private RegionFileLease chunkflow$lease() {
        RegionFileLease lease = this.chunkflow$lease;
        if (lease == null) {
            synchronized (this) {
                lease = this.chunkflow$lease;
                if (lease == null) {
                    lease = new RegionFileLease();
                    this.chunkflow$lease = lease;
                }
            }
        }
        return lease;
    }

    @Unique
    private FileChannel chunkflow$channel() {
        FileChannel channel = this.chunkflow$channel;
        if (channel == null && this.dataFile != null) {
            channel = this.dataFile.getChannel();
            this.chunkflow$channel = channel;
        }
        return channel;
    }

    @Unique
    private StampedLock chunkflow$lock() {
        StampedLock lock = this.chunkflow$lock;
        if (lock == null) {
            synchronized (this) {
                lock = this.chunkflow$lock;
                if (lock == null) {
                    lock = new StampedLock();
                    this.chunkflow$lock = lock;
                }
            }
        }
        return lock;
    }

    @Override
    public boolean chunkflow$isClosed() {
        return this.chunkflow$lease().isClosed() || this.dataFile == null;
    }

    @Override
    public void chunkflow$setClosed(boolean closed) {
        if (closed) {
            this.chunkflow$lease().forceClose();
        }
    }

    @Override
    public long chunkflow$getLastAccess() {
        long access = this.chunkflow$lastAccess;
        return access == 0L ? System.currentTimeMillis() : access;
    }

    @Override
    public void chunkflow$touch() {
        this.chunkflow$lastAccess = System.currentTimeMillis();
    }

    @Override
    public boolean chunkflow$tryCloseLease() {
        return this.chunkflow$lease().tryClose();
    }

    @Override
    public void chunkflow$forceCloseLease() {
        this.chunkflow$lease().forceClose();
    }

    @Override
    public int chunkflow$readers() {
        return this.chunkflow$lease().readers();
    }

    /**
     * @author ChunkFlow
     * @reason positional pread64 + StampedLock optimistic read path (no per-file monitor)
     */
    @Overwrite
    @Nullable
    public DataInputStream getChunkDataInputStream(int x, int z) {
        this.chunkflow$touch();
        if (this.outOfBounds(x, z)) {
            return null;
        }
        if (!this.chunkflow$lease().tryAcquire()) {
            return this.chunkflow$reopenAndRead(x, z);
        }
        try {
            return this.chunkflow$readOnce(x, z);
        } finally {
            this.chunkflow$lease().release();
        }
    }

    @Unique
    private DataInputStream chunkflow$readOnce(int x, int z) {
        FileChannel channel = this.chunkflow$channel();
        if (channel == null || !channel.isOpen()) {
            return null;
        }

        long stamp = this.chunkflow$lock().tryOptimisticRead();
        int offset = this.getOffset(x, z);
        if (offset == 0) {
            return null;
        }
        int sectors = this.sectorFree.size();

        DataInputStream result = null;
        Throwable failure = null;
        try {
            result = RegionChunkReader.read(channel, offset, sectors, x, z, this.fileName.getName());
        } catch (Throwable t) {
            failure = t;
        }

        if (!this.chunkflow$lock().validate(stamp)) {
            // A write landed while we were reading; redo under the shared stamp.
            long readStamp = this.chunkflow$lock().readLock();
            try {
                offset = this.getOffset(x, z);
                if (offset == 0) {
                    return null;
                }
                return RegionChunkReader.read(channel, offset, this.sectorFree.size(), x, z, this.fileName.getName());
            } catch (IOException e) {
                return null;
            } finally {
                this.chunkflow$lock().unlockRead(readStamp);
            }
        }

        if (failure != null) {
            if (failure instanceof IOException) {
                return null;
            }
            if (failure instanceof RuntimeException) {
                throw (RuntimeException) failure;
            }
            if (failure instanceof Error) {
                throw (Error) failure;
            }
            return null;
        }
        return result;
    }

    /**
     * The file was evicted between cache lookup and read. Never return a false null: open the
     * live file and read from it instead.
     */
    @Unique
    @Nullable
    private DataInputStream chunkflow$reopenAndRead(int x, int z) {
        int depth = RegionFileMixin.chunkflow$selfHealDepth().get();
        if (depth >= 2 || this.fileName == null || this.fileName.getParentFile() == null) {
            return null;
        }
        RegionFileMixin.chunkflow$selfHealDepth().set(depth + 1);
        try {
            File worldDir = this.fileName.getParentFile().getParentFile();
            if (worldDir == null) {
                return null;
            }
            RegionFile live = RegionFileCache.createOrLoadRegionFile(worldDir, x, z);
            if (live == null || live == (Object) this) {
                return null;
            }
            return live.getChunkDataInputStream(x, z);
        } catch (Throwable t) {
            return null;
        } finally {
            RegionFileMixin.chunkflow$selfHealDepth().set(depth);
        }
    }

    /**
     * @author ChunkFlow
     * @reason keep the vanilla RandomAccessFile write path but exclude positional readers with
     *         the write stamp, so the shared cursor and sector table stay consistent
     */
    @Overwrite
    protected void write(int x, int z, byte[] data, int length) {
        long stamp = this.chunkflow$lock().writeLock();
        try {
            this.chunkflow$writeLocked(x, z, data, length);
        } finally {
            this.chunkflow$lock().unlockWrite(stamp);
        }
    }

    @Unique
    private void chunkflow$writeLocked(int x, int z, byte[] data, int length) {
        try {
            int i = this.getOffset(x, z);
            int j = i >> 8;
            int k = i & 255;
            if (k == 255) {
                this.dataFile.seek(j * 4096);
                k = (this.dataFile.readInt() + 4) / 4096 + 1;
            }
            int l = (length + 5) / 4096 + 1;

            if (l >= 256) {
                if (!Boolean.parseBoolean(System.getProperty("forge.enableExtendedSave", "true"))) {
                    return;
                }
                net.minecraftforge.fml.common.FMLLog.log.warn("Large Chunk Detected: ({}, {}) Size: {} {}",
                        x, z, l, this.fileName);
            }

            if (j != 0 && k == l) {
                this.write(j, data, length);
            } else {
                for (int i1 = 0; i1 < k; ++i1) {
                    this.sectorFree.set(j + i1, Boolean.TRUE);
                }

                int l1 = this.sectorFree.indexOf(Boolean.TRUE);
                int j1 = 0;

                if (l1 != -1) {
                    for (int k1 = l1; k1 < this.sectorFree.size(); ++k1) {
                        if (j1 != 0) {
                            if (this.sectorFree.get(k1)) {
                                ++j1;
                            } else {
                                j1 = 0;
                            }
                        } else if (this.sectorFree.get(k1)) {
                            l1 = k1;
                            j1 = 1;
                        }

                        if (j1 >= l) {
                            break;
                        }
                    }
                }

                if (j1 >= l) {
                    j = l1;
                    this.setOffset(x, z, l1 << 8 | (l > 255 ? 255 : l));

                    for (int j2 = 0; j2 < l; ++j2) {
                        this.sectorFree.set(j + j2, Boolean.FALSE);
                    }

                    this.write(j, data, length);
                } else {
                    this.dataFile.seek(this.dataFile.length());
                    j = this.sectorFree.size();

                    for (int i2 = 0; i2 < l; ++i2) {
                        this.dataFile.write(chunkflow$empty());
                        this.sectorFree.add(Boolean.FALSE);
                    }

                    this.write(j, data, length);
                    this.setOffset(x, z, j << 8 | (l > 255 ? 255 : l));
                }
            }

            this.setChunkTimestamp(x, z, (int) (MinecraftServer.getCurrentTimeMillis() / 1000L));
            this.chunkflow$touch();
        } catch (IOException ioexception) {
            ioexception.printStackTrace();
        }
    }

    /** 4 KB of zeroes; vanilla's EMPTY_SECTOR is private, so keep our own (lazy). */
    @Unique
    private static byte[] EMPTY;

    @Unique
    private static byte[] chunkflow$empty() {
        byte[] empty = EMPTY;
        if (empty == null) {
            empty = new byte[4096];
            EMPTY = empty;
        }
        return empty;
    }

    /**
     * @author ChunkFlow
     * @reason close only after readers drain; the cache lease prevents new readers
     */
    @Overwrite
    public void close() throws IOException {
        this.chunkflow$lease().forceClose();
        long deadline = System.currentTimeMillis() + 250L;
        while (this.chunkflow$lease().readers() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(1L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        long stamp = this.chunkflow$lock().writeLock();
        try {
            this.chunkflow$channel = null;
            if (this.dataFile != null) {
                try {
                    this.dataFile.close();
                } finally {
                    this.dataFile = null;
                }
            }
        } finally {
            this.chunkflow$lock().unlockWrite(stamp);
        }
    }
}
