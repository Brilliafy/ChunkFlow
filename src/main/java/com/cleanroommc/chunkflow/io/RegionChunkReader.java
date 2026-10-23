package com.cleanroommc.chunkflow.io;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.zip.GZIPInputStream;

/**
 * Lock-free positional region-chunk reader.
 *
 * <p>Vanilla uses a shared {@code RandomAccessFile} cursor, so every read must take the
 * {@code RegionFile} monitor. This reader instead issues positional {@code FileChannel.read}
 * calls ({@code pread64} / overlapped read), which never touch the file pointer and can run
 * concurrently on the same {@code .mca}.</p>
 *
 * <p>The offset table already stores the allocated sector count, so the whole chunk is read
 * with one syscall into a thread-local direct buffer; the 5-byte header is parsed in memory.</p>
 *
 * <p>Plain Java 8, no Minecraft types: the RegionFile mixin delegates here so the format
 * logic is testable without a game runtime.</p>
 */
public final class RegionChunkReader {

    /** 255 sectors is the on-disk cap; extended saves can be larger (2 MiB buffer). */
    private static final int MAX_SECTORS = 512;
    private static final int BUFFER_BYTES = MAX_SECTORS * 4096;

    private static final ThreadLocal<ByteBuffer> BUFFER =
            ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(BUFFER_BYTES));

    private RegionChunkReader() {
    }

    /**
     * @param channel       positional channel for the region file
     * @param offsetEntry   the 32-bit value from {@code offsets[index]}
     * @param sectorsInFile {@code sectorFree.size()} (file length / 4096)
     * @param x             chunk x inside the region (for diagnostics)
     * @param z             chunk z inside the region
     * @param fileName      region file name (for diagnostics)
     * @return decompressed stream, or {@code null} only for a live chunk with offset 0
     */
    public static DataInputStream read(FileChannel channel, int offsetEntry, int sectorsInFile,
                                       int x, int z, String fileName) throws IOException {
        int sector = offsetEntry >>> 8;
        int sectors = offsetEntry & 255;
        if (sector <= 0 || sectors <= 0) {
            return null;
        }
        if (sector + sectors > sectorsInFile) {
            throw new IOException("Invalid chunk (" + x + "," + z + ") offset " + sector
                    + " sectors " + sectors + " runs off end of " + fileName);
        }

        ByteBuffer buffer = BUFFER.get();
        int want = Math.min(sectors, MAX_SECTORS) * 4096;
        buffer.clear();
        buffer.limit(Math.min(want, buffer.capacity()));
        readFully(channel, buffer, (long) sector * 4096L);

        int length = buffer.getInt(0);
        byte compression = buffer.get(4);

        // Low byte == 255 means the on-disk sector count is saturated; the exact length is
        // stored in the first four bytes. Re-read once with the real size if it is bigger.
        if (sectors == 255) {
            int realSectors = (length + 4) / 4096 + 1;
            if (realSectors * 4096 > buffer.capacity()) {
                throw new IOException("Chunk (" + x + "," + z + ") too large for region reader: "
                        + realSectors + " sectors in " + fileName);
            }
            if (realSectors * 4096 > want) {
                buffer.clear();
                buffer.limit(realSectors * 4096);
                readFully(channel, buffer, (long) sector * 4096L);
            }
            sectors = realSectors;
        }

        if (length <= 0) {
            throw new IOException("Invalid chunk (" + x + "," + z + ") size " + length + " in " + fileName);
        }
        if (length > sectors * 4096) {
            throw new IOException("Invalid chunk (" + x + "," + z + ") size " + length + " > "
                    + (sectors * 4096) + " in " + fileName);
        }

        byte[] payload = new byte[length - 1];
        ByteBuffer view = buffer.duplicate();
        view.position(5);
        view.get(payload);

        if (compression == 2) {
            return FastZlibPool.decompressChunk(payload, 0, payload.length);
        }
        if (compression == 1) {
            return new DataInputStream(new BufferedInputStream(
                    new GZIPInputStream(new ByteArrayInputStream(payload))));
        }
        return null;
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long pos = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, pos);
            if (read < 0) {
                throw new EOFException("Unexpected end of region file at " + pos);
            }
            pos += read;
        }
    }
}
