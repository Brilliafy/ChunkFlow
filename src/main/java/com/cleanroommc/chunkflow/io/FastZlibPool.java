package com.cleanroommc.chunkflow.io;

import com.cleanroommc.chunkflow.config.ModConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Pooled zlib helpers for region file I/O.
 *
 * <p>Vanilla allocates a fresh {@link Inflater} for every chunk it reads. These are thread-local and
 * reset between uses, which matters now that chunk loading happens on several threads.</p>
 */
public final class FastZlibPool {

    public static final AtomicLong TOTAL_DECOMPRESSED_CHUNKS = new AtomicLong(0);
    public static final AtomicLong TOTAL_COMPRESSED_CHUNKS = new AtomicLong(0);
    public static final AtomicLong ZLIB_REUSES = new AtomicLong(0);

    private static final ThreadLocal<Inflater> INFLATER_POOL = ThreadLocal.withInitial(Inflater::new);
    private static final ThreadLocal<Deflater> DEFLATER_POOL = ThreadLocal.withInitial(Deflater::new);

    private static final ThreadLocal<byte[]> TEMP_BUFFER = ThreadLocal.withInitial(() -> new byte[65536]);
    private static final ThreadLocal<ReusableByteArrayOutputStream> REUSABLE_BAOS =
            ThreadLocal.withInitial(() -> new ReusableByteArrayOutputStream(65536));

    private FastZlibPool() {
    }

    public static class ReusableByteArrayOutputStream extends ByteArrayOutputStream {
        public ReusableByteArrayOutputStream(int size) {
            super(size);
        }
        public byte[] getRawBuffer() {
            return this.buf;
        }
        public int getCount() {
            return this.count;
        }
    }

    public static DataInputStream decompressChunk(byte[] compressedData, int offset, int length) throws IOException {
        TOTAL_DECOMPRESSED_CHUNKS.incrementAndGet();

        if (!ModConfig.REGION.enabled || !ModConfig.REGION.enableZlibPooling) {
            Inflater inf = new Inflater();
            try {
                inf.setInput(compressedData, offset, length);
                byte[] out = decompressFallback(inf);
                return new DataInputStream(new ByteArrayInputStream(out));
            } finally {
                inf.end();
            }
        }

        ZLIB_REUSES.incrementAndGet();
        Inflater inflater = INFLATER_POOL.get();
        inflater.reset();
        inflater.setInput(compressedData, offset, length);

        ReusableByteArrayOutputStream baos = REUSABLE_BAOS.get();
        baos.reset();
        byte[] buffer = TEMP_BUFFER.get();

        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                baos.write(buffer, 0, count);
            }
        } catch (DataFormatException dfe) {
            try {
                inflater.end();
            } catch (Throwable ignored) {
            }
            INFLATER_POOL.set(new Inflater());
            throw new IOException("Corrupt compressed chunk data", dfe);
        } catch (Throwable t) {
            try {
                inflater.end();
            } catch (Throwable ignored) {
            }
            INFLATER_POOL.set(new Inflater());
            throw new IOException("Failed to decompress chunk data", t);
        }

        // Copy into an exact-sized array. Returning a view over the thread-local scratch buffer would
        // silently corrupt any stream that outlives the next decompression on this thread.
        int count = baos.getCount();
        byte[] exact = new byte[count];
        System.arraycopy(baos.getRawBuffer(), 0, exact, 0, count);
        return new DataInputStream(new ByteArrayInputStream(exact));
    }

    private static byte[] decompressFallback(Inflater inflater) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(32768);
        byte[] buffer = TEMP_BUFFER.get();

        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                baos.write(buffer, 0, count);
            }
        } catch (DataFormatException dfe) {
            throw new IOException("Corrupt compressed chunk data", dfe);
        }

        return baos.toByteArray();
    }

    public static byte[] compressChunk(byte[] uncompressedData, int offset, int length) {
        TOTAL_COMPRESSED_CHUNKS.incrementAndGet();

        Deflater deflater = DEFLATER_POOL.get();
        deflater.reset();
        deflater.setInput(uncompressedData, offset, length);
        deflater.finish();

        ByteArrayOutputStream baos = new ByteArrayOutputStream(length);
        byte[] buffer = TEMP_BUFFER.get();

        try {
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                baos.write(buffer, 0, count);
            }
        } catch (Throwable t) {
            try {
                deflater.end();
            } catch (Throwable ignored) {
            }
            DEFLATER_POOL.set(new Deflater());
            throw t;
        }

        ZLIB_REUSES.incrementAndGet();
        return baos.toByteArray();
    }

    public static void clearStats() {
        TOTAL_DECOMPRESSED_CHUNKS.set(0);
        TOTAL_COMPRESSED_CHUNKS.set(0);
        ZLIB_REUSES.set(0);
    }
}
