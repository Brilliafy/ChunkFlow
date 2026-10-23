package com.cleanroommc.chunkflow.torture;

import com.cleanroommc.chunkflow.directional.DirectionalScore;
import com.cleanroommc.chunkflow.io.FastZlibPool;
import com.cleanroommc.chunkflow.io.RegionChunkReader;
import com.cleanroommc.chunkflow.io.RegionFileLease;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.StampedLock;
import java.util.zip.Deflater;

/**
 * Plain-Java torture test for the lock-free region reader, lease, zlib strictness and
 * directional score. Run with:
 *
 * <pre>
 * javac -cp build/classes/java/main -d /tmp/cftest src/test/java/.../RegionTorture.java
 * java -cp build/classes/java/main:/tmp/cftest com.cleanroommc.chunkflow.torture.RegionTorture
 * </pre>
 */
public final class RegionTorture {

    private static final int CHUNKS = 256;
    private static final int HEADER_SECTORS = 2;
    private static final int PAYLOAD_BYTES = 8192;
    private static final int THREADS = 8;
    private static final int READS_PER_THREAD = 4000;

    private static final byte[] ZEROS = new byte[4096];

    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "chunkflow-torture-" + System.nanoTime());
        dir.mkdirs();
        try {
            File mca = new File(dir, "r.0.0.mca");
            int[] offsets = writeRegion(mca, false);
            int sectors = (int) (mca.length() / 4096);

            verifySequential(mca, offsets, sectors);
            verifyConcurrent(mca, offsets, sectors);
            verifyConcurrentWithWriter(mca, offsets, sectors);
            verifyTruncated(dir, offsets);
            verifyLease();
            verifyDirectional();
            benchmark(mca, offsets, sectors);
            System.out.println("ALL TORTURE TESTS PASSED");
        } finally {
            deleteRecursive(dir);
        }
    }

    private static void verifySequential(File mca, int[] offsets, int sectors) throws Exception {
        for (int i = 0; i < CHUNKS; i++) {
            byte[] expected = payload(i);
            byte[] actual = read(mca, offsets[i], sectors, i);
            if (!java.util.Arrays.equals(expected, actual)) {
                throw new AssertionError("sequential payload mismatch at " + i);
            }
        }
        System.out.println("sequential: " + CHUNKS + " chunks OK");
    }

    private static void verifyConcurrent(File mca, int[] offsets, int sectors) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            final int seed = t;
            Thread thread = new Thread(() -> {
                Random random = new Random(seed);
                try {
                    start.await();
                    for (int i = 0; i < READS_PER_THREAD; i++) {
                        int idx = random.nextInt(CHUNKS);
                        byte[] actual = read(mca, offsets[idx], sectors, idx);
                        if (!java.util.Arrays.equals(payload(idx), actual)) {
                            throw new AssertionError("concurrent payload mismatch at " + idx);
                        }
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                }
            }, "reader-" + t);
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        join(threads);
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        System.out.println("concurrent pread: " + (THREADS * READS_PER_THREAD) + " reads OK");
    }

    /**
     * Writers keep the vanilla RandomAccessFile cursor path; readers must see either the
     * old or the new payload, never a torn mix. Stored (level 0) deflate keeps the byte
     * length constant so offsets never move.
     */
    private static void verifyConcurrentWithWriter(File mca, int[] offsets, int sectors) throws Exception {
        byte[] payloadA = new byte[PAYLOAD_BYTES];
        byte[] payloadB = new byte[PAYLOAD_BYTES];
        java.util.Arrays.fill(payloadA, (byte) 'A');
        java.util.Arrays.fill(payloadB, (byte) 'B');
        byte[] compressedA = deflate(payloadA);
        byte[] compressedB = deflate(payloadB);
        if (compressedA.length != compressedB.length) {
            throw new AssertionError("stored payload lengths differ");
        }

        // Pre-fill every chunk with A so any unwritten chunk still has a uniform payload.
        try (RandomAccessFile raf = new RandomAccessFile(mca, "rw")) {
            for (int i = 0; i < CHUNKS; i++) {
                int sector = offsets[i] >>> 8;
                raf.seek((long) sector * 4096L);
                raf.writeInt(compressedA.length + 1);
                raf.writeByte(2);
                raf.write(compressedA);
            }
        }

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger reads = new AtomicInteger();
        StampedLock gate = new StampedLock();
        List<Thread> threads = new ArrayList<>();

        Thread writer = new Thread(() -> {
            try (RandomAccessFile raf = new RandomAccessFile(mca, "rw")) {
                start.await();
                for (int i = 0; i < 3000; i++) {
                    int idx = (i * 31) % CHUNKS;
                    byte[] data = (i & 1) == 0 ? compressedA : compressedB;
                    int sector = offsets[idx] >>> 8;
                    long stamp = gate.writeLock();
                    try {
                        raf.seek((long) sector * 4096L);
                        raf.writeInt(data.length + 1);
                        raf.writeByte(2);
                        raf.write(data);
                    } finally {
                        gate.unlockWrite(stamp);
                    }
                }
            } catch (Throwable e) {
                error.compareAndSet(null, e);
            }
        }, "writer");
        threads.add(writer);

        for (int t = 0; t < THREADS; t++) {
            final int seed = t;
            Thread thread = new Thread(() -> {
                Random random = new Random(seed * 7919L);
                try {
                    start.await();
                    for (int i = 0; i < 3000; i++) {
                        int idx = random.nextInt(CHUNKS);
                        if (idx == (i * 31) % CHUNKS) {
                            continue;
                        }
                        byte[] actual = readValidated(mca, offsets, sectors, idx, gate);
                        if (actual.length != PAYLOAD_BYTES) {
                            throw new AssertionError("length changed at " + idx);
                        }
                        byte first = actual[0];
                        for (int k = 0; k < actual.length; k++) {
                            if (actual[k] != first) {
                                int countA = 0, countB = 0, other = 0;
                                for (byte x : actual) {
                                    if (x == 'A') countA++; else if (x == 'B') countB++; else other++;
                                }
                                throw new AssertionError("TORN READ at chunk " + idx + " first=" + first
                                        + " at " + k + " actual=" + actual[k] + " A=" + countA + " B=" + countB + " other=" + other);
                            }
                        }
                        reads.incrementAndGet();
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                }
            }, "rw-reader-" + t);
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        join(threads);
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        System.out.println("pread vs RandomAccessFile write: " + reads.get() + " reads, 0 torn");
    }

    private static void verifyTruncated(File dir, int[] offsets) throws Exception {
        File bad = new File(dir, "r.1.0.mca");
        writeRegion(bad, true);
        FileChannel channel = FileChannel.open(bad.toPath(), StandardOpenOption.READ);
        try {
            RegionChunkReader.read(channel, offsets[0], (int) (bad.length() / 4096), 0, 0, "bad");
            throw new AssertionError("truncated zlib did not throw");
        } catch (EOFException expected) {
            System.out.println("truncation: EOFException OK");
        } finally {
            channel.close();
        }
    }

    private static void verifyLease() throws Exception {
        RegionFileLease lease = new RegionFileLease();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200000; i++) {
                        if (lease.tryAcquire()) {
                            active.incrementAndGet();
                            active.decrementAndGet();
                            lease.release();
                        }
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        Thread.sleep(50);
        boolean closed = false;
        long deadline = System.currentTimeMillis() + 5000;
        while (!closed && System.currentTimeMillis() < deadline) {
            closed = lease.tryClose();
        }
        join(threads);
        if (!closed) {
            throw new AssertionError("lease never reached 0 for close");
        }
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        if (lease.tryAcquire()) {
            throw new AssertionError("acquired after close");
        }
        if (lease.readers() != 0) {
            throw new AssertionError("readers after close: " + lease.readers());
        }
        System.out.println("lease: concurrent acquire/close OK");
    }

    private static void verifyDirectional() {
        double beta = 0.6;
        double threshold = 0.04;
        Random random = new Random(1234);
        for (int i = 0; i < 200000; i++) {
            double score = DirectionalScore.compute(
                    random.nextGaussian() * 1e6, random.nextGaussian() * 1e6,
                    random.nextGaussian() * 1e6, random.nextGaussian() * 1e6,
                    random.nextGaussian(), random.nextGaussian(), beta, threshold);
            if (Double.isNaN(score)) {
                throw new AssertionError("NaN score");
            }
        }
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            double score = DirectionalScore.compute(10, 0, 0, 0, bad, bad, beta, threshold);
            if (Double.isNaN(score)) {
                throw new AssertionError("NaN score for " + bad);
            }
        }
        double ahead = DirectionalScore.compute(10, 0, 0, 0, 1, 0, beta, threshold);
        double behind = DirectionalScore.compute(-10, 0, 0, 0, 1, 0, beta, threshold);
        if (!(ahead < behind)) {
            throw new AssertionError("directional bias wrong: ahead=" + ahead + " behind=" + behind);
        }
        System.out.println("directional: finite monotonic scores OK");
    }

    private static void benchmark(File mca, int[] offsets, int sectors) throws Exception {
        int ops = THREADS * 4000;
        long positional = timeReads(mca, offsets, sectors, false, ops);
        long vanilla = timeReads(mca, offsets, sectors, true, ops);
        double posOps = ops / (positional / 1e9);
        double vanOps = ops / (vanilla / 1e9);
        System.out.printf("benchmark: pread %.0f reads/s | synchronized RAF %.0f reads/s | speedup %.2fx%n",
                posOps, vanOps, posOps / vanOps);
    }

    private static long timeReads(File mca, int[] offsets, int sectors, boolean vanilla, int totalOps) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicReference<Throwable> error = new AtomicReference<>();
        RandomAccessFile sharedRaf = vanilla ? new RandomAccessFile(mca, "r") : null;
        FileChannel sharedChannel = vanilla ? null : FileChannel.open(mca.toPath(), StandardOpenOption.READ);
        long begin = System.nanoTime();
        for (int t = 0; t < THREADS; t++) {
            final int seed = t;
            new Thread(() -> {
                Random random = new Random(seed * 13L);
                try {
                    start.await();
                    for (int i = 0; i < totalOps / THREADS; i++) {
                        int idx = random.nextInt(CHUNKS);
                        if (vanilla) {
                            readVanilla(sharedRaf, offsets, sectors, idx);
                        } else {
                            read(sharedChannel, offsets[idx], sectors, idx);
                        }
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();
        long elapsed = System.nanoTime() - begin;
        if (sharedRaf != null) sharedRaf.close();
        if (sharedChannel != null) sharedChannel.close();
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return elapsed;
    }

    private static byte[] readVanilla(RandomAccessFile raf, int[] offsets, int sectors, int idx) throws IOException {
        synchronized (raf) {
            raf.seek((long) (offsets[idx] >>> 8) * 4096L);
            int length = raf.readInt();
            raf.readByte();
            byte[] data = new byte[length - 1];
            raf.readFully(data);
            return inflate(data);
        }
    }

    /** Same optimistic stamp protocol as RegionFileMixin.readOnce. */
    private static byte[] readValidated(File mca, int[] offsets, int sectors, int idx, StampedLock gate) throws IOException {
        while (true) {
            long stamp = gate.tryOptimisticRead();
            int off = offsets[idx];
            byte[] data = read(mca, off, sectors, idx);
            if (gate.validate(stamp)) {
                return data;
            }
            long readStamp = gate.readLock();
            try {
                return read(mca, offsets[idx], sectors, idx);
            } finally {
                gate.unlockRead(readStamp);
            }
        }
    }

    private static byte[] read(File mca, int offset, int sectors, int idx) throws IOException {
        try (FileChannel channel = FileChannel.open(mca.toPath(), StandardOpenOption.READ)) {
            return read(channel, offset, sectors, idx);
        }
    }

    private static byte[] read(FileChannel channel, int offset, int sectors, int idx) throws IOException {
        try (DataInputStream in = RegionChunkReader.read(channel, offset, sectors, idx % 32, idx / 32, "bench")) {
            if (in == null) {
                return null;
            }
            byte[] data = new byte[PAYLOAD_BYTES];
            in.readFully(data);
            return data;
        }
    }

    private static byte[] payload(int idx) {
        byte[] data = new byte[PAYLOAD_BYTES];
        Random random = new Random(idx * 1_000_003L);
        random.nextBytes(data);
        return data;
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.NO_COMPRESSION);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 64);
            byte[] buffer = new byte[4096];
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] compressed) throws IOException {
        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
        try {
            inflater.setInput(compressed);
            byte[] out = new byte[PAYLOAD_BYTES];
            int n = inflater.inflate(out);
            if (n != out.length) {
                throw new IOException("short inflate " + n);
            }
            return out;
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException(e);
        } finally {
            inflater.end();
        }
    }

    /** Writes 256 chunks: two header sectors, then one or more sectors per chunk. */
    private static int[] writeRegion(File file, boolean truncateFirst) throws IOException {
        int[] offsets = new int[CHUNKS];
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(0);
            raf.write(ZEROS);
            raf.write(ZEROS);
            int nextSector = HEADER_SECTORS;
            for (int i = 0; i < CHUNKS; i++) {
                byte[] compressed = deflate(payload(i));
                if (truncateFirst && i == 0) {
                    compressed = java.util.Arrays.copyOf(compressed, Math.max(1, compressed.length / 2));
                }
                int sectors = (compressed.length + 5 + 4095) / 4096;
                int offset = (nextSector << 8) | Math.min(sectors, 255);
                offsets[i] = offset;
                raf.seek((long) nextSector * 4096L);
                raf.writeInt(compressed.length + 1);
                raf.writeByte(2);
                raf.write(compressed);
                nextSector += sectors;
            }
            int end = (int) raf.length() / 4096 + 1;
            raf.setLength((long) end * 4096L);
            raf.seek(0);
            for (int i = 0; i < CHUNKS; i++) {
                raf.writeInt(offsets[i]);
            }
        }
        return offsets;
    }

    private static void join(List<Thread> threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join();
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        file.delete();
    }
}
