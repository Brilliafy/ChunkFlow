<div align="center">

<table style="display: inline-table; border: 1px solid #10b981; border-radius: 8px; background-color: rgba(16, 185, 129, 0.08); padding: 8px 16px; margin: 12px auto;">
  <tr>
    <td style="vertical-align: middle; padding-right: 12px;">
      <a href="https://github.com/Brilliafy/WorldFlow">
        <img src="https://raw.githubusercontent.com/Brilliafy/WorldFlow/main/logo.png" width="36" height="36" alt="WorldFlow Logo" style="border-radius: 6px; vertical-align: middle;" />
      </a>
    </td>
    <td style="vertical-align: middle; text-align: left; font-size: 14px;">
      🌍 <b>Pair with <a href="https://github.com/Brilliafy/WorldFlow">WorldFlow</a></b> to generate new terrain faster across all CPU cores and stop worldgen freezes!
    </td>
  </tr>
</table>

<br />

<img src="logo.png" alt="ChunkFlow Logo" width="130" height="130" />

# ChunkFlow

**Next-Generation Chunk Loading, Lock-Free Region I/O & Directional Streaming for Minecraft 1.12.2**

[![Minecraft](https://img.shields.io/badge/Minecraft-1.12.2-blue.svg?style=flat-square&logo=minecraft)](https://minecraft.net/)
[![Platform](https://img.shields.io/badge/Platform-Cleanroom%20%7C%20Forge-orange.svg?style=flat-square)](https://github.com/CleanroomMC)
[![Version](https://img.shields.io/badge/Release-v1.0.1-brightgreen.svg?style=flat-square)](https://github.com/Brilliafy/ChunkFlow/releases)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue.svg?style=flat-square)](LICENSE)
[![MixinBooter](https://img.shields.io/badge/Requires-MixinBooter%2010.2+-purple.svg?style=flat-square)](https://www.curseforge.com/minecraft/mc-mods/mixinbooter)
[![Speedup](https://img.shields.io/badge/I%2FO%20Speedup-Up%20to%205.9x-gold.svg?style=flat-square)](#-benchmarks--torture-testing)

<p align="center">
  <a href="#-why-chunkflow">Why ChunkFlow?</a> •
  <a href="#-key-features">Key Features</a> •
  <a href="#-architecture-comparison">Architecture</a> •
  <a href="#-benchmarks--torture-testing">Benchmarks</a> •
  <a href="#-installation">Installation</a> •
  <a href="#-commands">Commands</a> •
  <a href="#-configuration">Configuration</a> •
  <a href="#-compatibility">Compatibility</a>
</p>

</div>

---

## 🌟 Why ChunkFlow?

In vanilla Minecraft 1.12.2 and Forge, loading chunks from disk is plagued by legacy single-threaded bottlenecks and concurrency locks designed over a decade ago:

1. **Forge's 1-Thread Async Clamp**: Forge's `ChunkIOExecutor` builds its background worker pool with a **core pool size of 1** and an **unbounded queue**. Because a `ThreadPoolExecutor` only scales beyond its core size when its queue is full, and an unbounded queue *never* fills, Forge's pool never grows past 1 thread. Worse, Forge's internal `adjustPoolSize(players)` clamps the pool to `max(1, players / 50)`—meaning even a 64-core dedicated server with 30 players loads every chunk through a **single worker thread**!
2. **Region File Serialization**: Vanilla uses a shared `RandomAccessFile` file pointer cursor inside `RegionFile`. To prevent corrupted reads, vanilla synchronizes the entire `RegionFile` instance (`synchronized(this)`). If multiple players or workers need chunks from the same `.mca` region file, all reads are strictly serialized one-by-one.
3. **Catastrophic Cache Purges**: Vanilla's `RegionFileCache` enforces a hard limit of 256 open region files. When region #257 is opened, vanilla doesn't evict the oldest file; it invokes `REGIONS_BY_FILE.clear()`, **instantly closing and wiping all 256 cached files simultaneously**. This triggers massive I/O freezes and disk thrashing on active servers.
4. **The Elytra "Void Flight" Problem**: The server decides which chunks to send players based purely on radial 2D Euclidean distance around player coordinates. When a player flies at high speed (via Elytra, horses, or modded jetpacks), the server expends bandwidth and I/O sending chunks *behind* them, leaving players flying headfirst into unrendered void holes.
5. **Garbage-Heavy Decompression**: Vanilla allocates a brand-new `java.util.zip.Inflater` decompressor for every single chunk read from disk, creating excessive heap pressure and GC pauses during world travel.

**ChunkFlow rewrites this entire pipeline**, unlocking true multi-core parallel async chunk I/O, lock-free positional filesystem access, pooled native zlib decompressors, and velocity-projected directional chunk streaming.

---

## ⚡ Key Features

### 🚀 Lock-Free Positional Region I/O
- Replaces vanilla's synchronized `RandomAccessFile` with non-blocking positional `FileChannel.read(ByteBuffer, long)` (`pread64` on Linux/macOS, overlapped I/O on Windows).
- Completely eliminates the per-file monitor lock! Multiple async threads can now read different chunks from the **exact same `.mca` file concurrently**.
- Reads each chunk in a single atomic syscall directly into a 2 MiB thread-local direct buffer, parsing the 5-byte Minecraft sector header in memory with zero intermediate byte copies.

### 🛡️ Optimistic `StampedLock` Concurrency
- Uses Java's high-performance `StampedLock` for reading the region offset table.
- Reads proceed optimistically without acquiring exclusive locks. If an active world save modifies sector allocations, readers cleanly re-read under a shared read stamp.
- Battle-tested with zero torn reads and zero data corruption under extreme concurrent read/write torture testing.

### 🧵 Multi-Core Asynchronous Worker Pool
- Bypasses Forge's single-thread restriction by automatically configuring `ChunkIOExecutor` to use `CPU cores - 1` worker threads (configurable 1–64).
- Cancels Forge's hardcoded `adjustPoolSize` throttle to prevent Forge from collapsing the pool back to 1 thread.
- Thread-safe task lifecycle hooks: safely guards task removals (`syncChunkLoad` and `dropQueuedChunkLoad`) and drains in-flight I/O before dimension unloads (`WorldEvent.Unload`) and server shutdown (`FMLServerStoppingEvent`).

### 🎯 Directional Velocity-Projected Chunk Cone
- Senses player movement vectors (walking, riding minecarts/boats/horses, Elytra flight, and modded vehicles).
- Dynamically biases `PlayerChunkMapEntry` sorting toward the player's forward vector using a velocity-weighted cone.
- Powered by `DirectionalScore`, a mathematically pure, monotonic, and finite scoring function guaranteed to never produce `NaN` or violate TimSort comparator contracts.
- Chunks ahead of the player load and stream first, eliminating the dreaded "flying into the void" effect.

### 🔄 Pooled Thread-Local Zlib Decompression
- Uses reusable, thread-local `Inflater` and `Deflater` instances instead of allocating a fresh decompressor for every chunk.
- Strict stream validation: immediately detects and raises `EOFException` on truncated or corrupted chunk payloads instead of allowing silent corruption into the world.

### 🧹 Intelligent Region Cache Trimming & Atomic Leases
- Replaces vanilla's destructive `clear()` wipe with an intelligent LRU trimmer.
- Raises the default cache size from 256 to 512 region files (configurable from 64 to 4,096).
- **`RegionFileLease`**: Atomic CAS (`0 -> closed`) reader leases guarantee that the cache trimmer can never close or evict a region file that is actively being read.
- **Self-Healing Reads**: In the rare event of a cache eviction race, ChunkFlow automatically re-opens the live region file through `RegionFileCache` instead of returning a false `null` (preventing chunks from vanishing or regenerating).

---

## 📊 Architecture Comparison

```mermaid
flowchart TD
    subgraph Vanilla_Forge["Vanilla & Forge (Serialized Bottleneck)"]
        V1["Chunk Load Request"] --> V2["ChunkIOExecutor (Hardcoded 1 Thread)"]
        V2 --> V3["RegionFile (synchronized monitor lock)"]
        V3 --> V4["RandomAccessFile.seek() + read()"]
        V4 --> V5["New Inflater() Allocated"]
        V5 --> V6["Decompress & Parse NBT"]
        V6 --> V7["Radial Distance Sort (Sends chunks behind player)"]
    end

    subgraph ChunkFlow_Pipeline["ChunkFlow (Lock-Free Parallel Pipeline)"]
        C1["Concurrent Chunk Requests"] --> C2["ChunkIOExecutor (Multi-Core Worker Pool)"]
        C2 --> C3["Worker Threads 1..N"]
        C3 --> C4["StampedLock Optimistic Read"]
        C4 --> C5["FileChannel.read() (pread64 / Overlapped I/O)"]
        C5 --> C6["Thread-Local Direct Buffer + Pooled Inflater"]
        C6 --> C7["NBT Parse & DataFixer Walk Off-Thread"]
        C7 --> C8["Directional Velocity-Projected Send Cone"]
    end
```

---

## 📈 Benchmarks & Torture Testing

ChunkFlow includes a standalone torture test suite ([`tools/RegionTorture.java`](tools/RegionTorture.java)) that subjects the reader, lease, zlib pooling, and directional scoring algorithms to extreme multi-threaded stress:

| Metric | Vanilla / Standard Forge | ChunkFlow 1.0.1 | Improvement |
|---|---|---|---|
| **Region Read Throughput** | ~91,650 reads/sec | **~268,784 reads/sec** | **+193% (2.93x faster)** |
| **High-Concurrency Reads** | Heavily serialized | Fully parallel (`pread64`) | **Up to 5.9x faster** |
| **Concurrent Writer Integrity** | Prone to cursor contention | StampedLock / Zero torn reads | **100% Data Integrity** |
| **Zlib Allocations** | 1 `Inflater` per chunk | Zero allocations (ThreadLocal pool) | **~100% Allocation Reduction** |
| **Cache Eviction Behavior** | Wipes all 256 files on overflow | Trims idle files past 30s lease | **Zero Cache Thrashing** |
| **Chunk Send Priority** | Static radial circles | Velocity-projected cone | **Eliminates Elytra Voids** |

*Benchmark environment: Linux x86_64, 8 concurrent worker threads, 32,000 randomized chunk reads across real `.mca` sector layouts.*

---

## 📦 Installation

### Requirements
- **Minecraft**: `1.12.2`
- **Mod Loader**: [Cleanroom](https://github.com/CleanroomMC/Cleanroom) `0.6.x+` (recommended) or [Minecraft Forge](https://files.minecraftforge.net/) `14.23.5.2847+`
- **Core Dependency**: [MixinBooter](https://www.curseforge.com/minecraft/mc-mods/mixinbooter) `10.2` or newer
- **Java**: Java 8 up to modern Java 21+ runtimes supported.

### Client & Server Setup
1. Ensure **MixinBooter 10.2+** is installed in your `mods/` directory.
2. Drop `chunkflow-1.0.1.jar` into your `mods/` directory.
3. Launch the game or dedicated server.

> [!NOTE]
> ChunkFlow is fully server-side functional! Clients do not need ChunkFlow to join a server running it. However, installing ChunkFlow on the client is strongly recommended for singleplayer worlds and local integrated servers.

---

## 🛠️ Commands

All commands require permission level 2 (OP) or cheats enabled:

| Command | Description |
|---|---|
| `/chunkflow stats` | Displays live worker pool size, active thread counts, off-thread chunks loaded, open region files, and zlib reuse counters. |
| `/chunkflow threads <0\|n>` | Dynamically adjusts the worker thread count at runtime (`0` = automatic `cores - 1`). |
| `/chunkflow toggle` | Toggles async chunk I/O parallelism on or off without restarting. |
| `/chunkflow region stats` | Displays region read throughput, decompressed chunk counters, and zlib cache metrics. |
| `/chunkflow region toggle` | Toggles the fast lock-free region I/O path. |
| `/chunkflow region clear` | Resets cumulative region read statistics. |
| `/chunkflow directional stats` | Shows directional chunk sending metrics, biased calls, and boosted chunk counts. |
| `/chunkflow directional toggle`| Toggles velocity-projected directional chunk ordering. |
| `/chunkflow directional clear` | Resets directional metrics. |
| `/chunkflow reload` | Hot-reloads configuration settings from `config/chunkflow.cfg`. |

**Aliases**: `/chunkpipe`, `/cpipeline`

---

## ⚙️ Configuration

File location: `config/chunkflow.cfg`

### `Async Chunk I/O`
```properties
# Parallelisation of Forge's asynchronous chunk loading pool (ChunkIOExecutor).
"Async Chunk I/O" {
    # Master toggle for async chunk I/O parallelism.
    B:Enable=true

    # Number of chunk I/O worker threads.
    # 0 = automatic (CPU cores - 1, capped at 4 for spindle drive stability).
    I:"I/O Threads"=0

    # Upper bound for the automatic thread count and for the I/O Threads setting.
    I:"Max Threads"=8

    # Minimum chunk I/O worker threads when scaling with player count is enabled.
    I:"Min Threads"=2

    # Dynamically scale worker thread count between Min Threads and Max Threads based on player count.
    B:"Scale With Players"=false
}
```

### `Region File I/O`
```properties
# High-throughput region file read path with pooled zlib.
"Region File I/O" {
    # Master toggle for the fast region read path.
    B:Enable=true

    # Reuses inflaters instead of allocating one per chunk.
    B:"Enable Zlib Pooling"=true

    # Uses the fast custom decompression path in RegionFile#getChunkDataInputStream.
    B:"Enable Fast Decompression"=true

    # Maximum number of region files to keep open in the cache before evicting idle files.
    # Vanilla is 256 (which wipes all files). ChunkFlow cleanly trims only idle files.
    I:"Max Cache Size"=512
}
```

### `Directional Chunks`
```properties
# Velocity-projected chunk loading cone: bias chunk send order towards a moving player's heading.
"Directional Chunks" {
    # Master toggle to enable/disable directional chunk loading and network prioritization.
    B:"Enable Mod"=true

    # Velocity bias factor beta (0.1 to 0.9). Higher values strongly favor chunks ahead in flight.
    D:"Velocity Bias Weight"=0.6

    # Minimum velocity squared (in blocks/tick squared) required to activate directional bias.
    D:"Min Velocity Threshold Sq"=0.04

    # Enable debug console logging.
    B:"Debug Logging"=false
}
```

---

## 🧩 Compatibility

| Mod / System | Compatibility Status | Notes |
|---|:---:|---|
| **CleanroomMC (0.6.x+)** | ✅ Full Native | Built specifically for Cleanroom and modern 1.12.2 runtimes. |
| **Minecraft Forge (14.23.5.x)** | ✅ Fully Compatible | Seamlessly hooks `ChunkIOExecutor` and `RegionFile`. |
| **MixinBooter (10.2+)** | ✅ Required | Required for injecting late-stage bytecode mixins. |
| **Chunk-Pregenerator** | ✅ Fully Compatible | Automatically detects Chunk-Pregenerator and enables safe concurrent region file interop. |
| **SpongeForge** | ✅ Compatible | Safe task draining guarantees compatibility with Sponge's world lifecycle. |
| **FoamFix / VanillaFix** | ✅ Compatible | No overlapping bytecode transformations. |
| **OptiFine** | ✅ Compatible | Client-side visual rendering is completely unaffected. |
| **[WorldFlow](https://github.com/Brilliafy/WorldFlow)** | ✅ Paired Companion | ChunkFlow manages disk I/O & network streaming; WorldFlow handles multi-threaded worldgen. |
| **[OTG-Flow](https://github.com/Brilliafy/OTG-Flow)** | ✅ Full Integration | High-performance concurrent OpenTerrainGenerator engine paired with WorldFlow & ChunkFlow. |

---

## 🏗️ Building from Source

### Prerequisites
- JDK 17, 21, or 25 (to run the modern Gradle 9+ build daemon)
- Target toolchain automatically compiles to Java 8 bytecode

### Build Instructions
```bash
# Clone the repository
git clone https://github.com/Brilliafy/ChunkFlow.git
cd ChunkFlow

# Build the mod jar using Gradle
./gradlew build

# The output jar will be located at:
# build/libs/chunkflow-1.0.1.jar
```

### Running the Torture Test Suite
ChunkFlow includes a standalone stress harness that validates lock-free concurrency without launching the full Minecraft client:

```bash
# 1. Compile the main mod classes
./gradlew compileJava

# 2. Compile and run RegionTorture
mkdir -p /tmp/cftest
javac -cp build/classes/java/main -d /tmp/cftest tools/RegionTorture.java
java -cp build/classes/java/main:/tmp/cftest com.cleanroommc.chunkflow.torture.RegionTorture
```

---

## 🤝 Contributing

Contributions, bug reports, and pull requests are welcome!
- Please read our [**Contributing Guidelines**](CONTRIBUTING.md) before opening a pull request.
- Ensure that any changes to I/O logic pass the [**RegionTorture**](tools/RegionTorture.java) stress test without warnings.
- Report issues and suggest features on the [**GitHub Issue Tracker**](https://github.com/Brilliafy/ChunkFlow/issues).

---

## 📜 License & Credits

- **Author**: Brilliafy
- **License**: [GNU General Public License v3.0 (GPL-3.0)](LICENSE)

*Minecraft is a registered trademark of Mojang Synergies AB. ChunkFlow is an open-source optimization project not affiliated with Mojang or Microsoft.*
