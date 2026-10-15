package com.cleanroommc.chunkflow.mixin;

import com.cleanroommc.chunkflow.config.ModConfig;
import com.cleanroommc.chunkflow.io.FastZlibPool;
import com.cleanroommc.chunkflow.io.IRegionFile;
import net.minecraft.world.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Reimplements {@code RegionFile#getChunkDataInputStream} with the same sector/length validation as
 * vanilla, but decompresses with a pooled {@code Inflater} (see {@link FastZlibPool}) instead of a
 * fresh {@code InflaterInputStream} per chunk. Falls back to vanilla behaviour when disabled.
 *
 * <p>Crucially overwrites {@code close()} to be {@code synchronized} on {@code this}, serializing with
 * {@code getChunkDataInputStream} and {@code write} so a region file cannot be closed while in-flight
 * reads or writes are active.</p>
 */
@Mixin(RegionFile.class)
public abstract class RegionFileMixin implements IRegionFile {

    @Shadow
    private RandomAccessFile dataFile;

    @Shadow
    private List<Boolean> sectorFree;

    @Shadow
    protected abstract boolean outOfBounds(int x, int z);

    @Shadow
    protected abstract int getOffset(int x, int z);

    private volatile boolean chunkflow$closed = false;
    private volatile long chunkflow$lastAccess = System.currentTimeMillis();

    @Override
    public boolean chunkflow$isClosed() {
        return this.chunkflow$closed || this.dataFile == null;
    }

    @Override
    public void chunkflow$setClosed(boolean closed) {
        this.chunkflow$closed = closed;
    }

    @Override
    public long chunkflow$getLastAccess() {
        return this.chunkflow$lastAccess;
    }

    @Override
    public void chunkflow$touch() {
        this.chunkflow$lastAccess = System.currentTimeMillis();
    }

    /**
     * @author ChunkFlow
     * @reason Overwrite close() to be synchronized on this (holding the monitor), ensuring it blocks
     *         until any active getChunkDataInputStream or write completes, and nulling dataFile safely.
     */
    @Overwrite
    public synchronized void close() throws IOException {
        this.chunkflow$closed = true;
        if (this.dataFile != null) {
            try {
                this.dataFile.close();
            } finally {
                this.dataFile = null;
            }
        }
    }

    @Inject(method = "getChunkDataInputStream(II)Ljava/io/DataInputStream;", at = @At("HEAD"), cancellable = true)
    private void chunkPipeline$onGetChunkDataInputStream(int x, int z, CallbackInfoReturnable<DataInputStream> cir) {
        this.chunkflow$touch();

        if (this.chunkflow$isClosed()) {
            cir.setReturnValue(null);
            return;
        }

        if (!ModConfig.REGION.enabled || !ModConfig.REGION.enableFastDecompression) {
            return;
        }

        if (this.outOfBounds(x, z)) {
            cir.setReturnValue(null);
            return;
        }

        try {
            int i = this.getOffset(x, z);
            if (i == 0) {
                cir.setReturnValue(null);
                return;
            }

            int j = i >> 8;
            int k = i & 255;
            if (k == 255) {
                if (this.dataFile == null) {
                    cir.setReturnValue(null);
                    return;
                }
                this.dataFile.seek((long) j * 4096L);
                k = (this.dataFile.readInt() + 4) / 4096 + 1;
            }

            if (j + k > this.sectorFree.size()) {
                cir.setReturnValue(null);
                return;
            }

            if (this.dataFile == null) {
                cir.setReturnValue(null);
                return;
            }
            this.dataFile.seek((long) j * 4096L);
            int l = this.dataFile.readInt();
            if (l > 4096 * k || l <= 0) {
                cir.setReturnValue(null);
                return;
            }

            byte b0 = this.dataFile.readByte();
            if (b0 == 2) {
                byte[] rawBytes = new byte[l - 1];
                this.dataFile.readFully(rawBytes);
                cir.setReturnValue(FastZlibPool.decompressChunk(rawBytes, 0, rawBytes.length));
            } else if (b0 == 1) {
                byte[] rawBytes = new byte[l - 1];
                this.dataFile.readFully(rawBytes);
                cir.setReturnValue(new DataInputStream(new BufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(rawBytes)))));
            }
        } catch (IOException e) {
            cir.setReturnValue(null);
        }
    }
}
