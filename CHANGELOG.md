# Changelog

All notable changes to **ChunkFlow** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.1] - 2026-10-24

### Fixed
- **Cleanroom Singleplayer Initialization**: Fixed a critical `NullPointerException` on Cleanroom singleplayer caused by `@Unique` mixin field initializers (`lease`, `lock`, `channel`, `EMPTY`) not merging into target constructors on certain runtimes. All mixin-owned state is now initialized lazily on first access.
- **Cache Trimmer Visibility**: Corrected `chunkflow$lastAccess` timestamp publication timing to guarantee that region file timestamps are published before the region file becomes visible to the cache map.
- **Self-Healing Depth Guard**: Lazy initialized the thread-local self-heal recursion depth counter to eliminate potential bootstrap NPEs during nested region reopen operations.

---

## [1.0.0] - 2026-10-24

### Added
- **Lock-Free Positional Region I/O**: Replaced vanilla's synchronized `RandomAccessFile` cursor with positional `FileChannel.read(ByteBuffer, long)` (`pread64` on Linux, overlapped I/O on Windows). Different threads can now concurrently read chunks from the exact same `.mca` region file without monitor contention.
- **Thread-Local 2 MiB Direct Buffers**: Reads entire chunk payloads in a single atomic syscall into a direct byte buffer, parsing the 5-byte sector header in memory with zero intermediate buffer allocations.
- **Optimistic StampedLock Concurrency**: Implemented `StampedLock` optimistic reads for `RegionFile` offset table lookups. Readers proceed non-blocking; writers hold exclusive write stamps during sector expansion and allocation.
- **Atomic Reader Leases (`RegionFileLease`)**: Introduced CAS (`0 -> closed`) reference counted reader leases preventing cache eviction from closing files that have in-flight reads.
- **Self-Healing File Access**: Eviction races automatically reopen the live region file via `RegionFileCache` rather than returning a false `null`, preventing chunk loss and erroneous terrain regeneration.
- **Pooled Zlib Decompression (`FastZlibPool`)**: Thread-local `Inflater` and `Deflater` pooling with strict stream integrity validation, throwing `EOFException` on truncated or corrupt streams.
- **Directional Velocity-Projected Chunk Cone**: Re-orders `PlayerChunkMapEntry` distance calculations using player velocity vectors (walking, riding, Elytra, jetpacks). Prioritizes sending chunks directly ahead of fast-moving players.
- **TimSort-Safe Scoring (`DirectionalScore`)**: Mathematically pure, monotonic, and finite scoring function guaranteed to never produce `NaN` or violate `Comparator` contracts.
- **Intelligent Cache Trimming**: Upgraded `RegionFileCache` to support 512 open files (configurable 64–4096) with clean idle-only LRU trimming, replacing vanilla's destructive `cache.clear()` wipe.
- **Comprehensive Torture Harness (`RegionTorture.java`)**: Added standalone multi-threaded verification testing 32,000 concurrent positional reads, concurrent writer integrity with 0 torn reads, truncation validation, lease concurrency, and directional scoring.
- **In-Game Administration Commands**: Full suite of `/chunkflow` (`/chunkpipe`, `/cpipeline`) commands for live monitoring, thread adjustments, feature toggling, and config reloading.

---

## [Milestone Releases] - 2026-10-22

### Added
- `milestone-lockfree-96-20261022`: ChunkFlow I/O milestone paired with 96-worker multi-threaded scale runs.
- `milestone-lockfree-20261022`: ChunkFlow I/O milestone paired with WorldFlow lock-free generation.
- `milestone-16gb-4225-20261022`: High-memory 16GB 4225 stress milestone.
- Hardened Forge `ChunkIOExecutor` task lifecycle and task maps with concurrent access guards and graceful dimension unload task draining.
