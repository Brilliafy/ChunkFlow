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
</div>

# ChunkFlow

**Next-Generation Chunk Loading, Lock-Free Region I/O & Directional Chunk Streaming for Minecraft 1.12.2**

---

### 🚀 What is ChunkFlow?

**ChunkFlow** is a modern, high-performance optimization mod for **Minecraft 1.12.2** (running on **Cleanroom** or **Minecraft Forge**) that revolutionizes how the game reads, decompresses, and streams existing chunks from disk.

Ever flown an Elytra or ridden a high-speed mount only to slam into unrendered, invisible chunk borders? Ever noticed your server freezing with massive tick lag spikes whenever players explore existing terrain?

That happens because Minecraft 1.12.2 still relies on single-threaded I/O architectures and synchronized disk locks designed over a decade ago. **ChunkFlow completely overhauls this pipeline**, delivering up to **2.9x – 5.9x faster chunk loading throughput** while ensuring 100% data integrity and stability.

---

## ⚡ The Problems ChunkFlow Solves

1. **The 1-Thread Async Bottleneck**: Forge provides an asynchronous chunk loader (`ChunkIOExecutor`), but hardcodes its core pool size to **1 thread** with an unbounded queue. This means whether your server has 4 cores or 64 cores, every chunk read from disk is bottlenecked behind a single worker thread!
2. **Region File Serialization**: Vanilla uses a shared file cursor on region (`.mca`) files and locks the entire file with `synchronized(this)`. When multiple players or workers need chunks from the same region, their reads are forced into a single-file line.
3. **Catastrophic Cache Wipes**: When vanilla's region file cache reaches 256 open files, it doesn't evict the oldest file it purges and closes **all 256 files at once**, causing brutal disk spikes and server lag.
4. **The Elytra "Void Flight" Syndrome**: The vanilla server calculates chunk send priority using radial circles around player coordinates. When flying fast, the server wastes time and bandwidth sending chunks *behind* you rather than the ones directly ahead of you.
5. **Garbage Churn**: Vanilla allocates a new `java.util.zip.Inflater` decompressor for every chunk read from disk, creating memory pressure and GC micro-stutters.

---

## 🌟 Key Features

* 🚀 **Lock-Free Positional Region I/O**: Replaces vanilla's synchronized file cursor with non-blocking positional `FileChannel.read` (`pread64` on Linux, overlapped I/O on Windows). Multiple CPU threads can now read different chunks from the exact same `.mca` file simultaneously!
* 🧵 **Multi-Core Asynchronous Loading**: Automatically expands Forge's `ChunkIOExecutor` across your available CPU cores (`cores - 1`, auto-default capped at 4 for spindle drives, or manually configured up to 64).
* 🎯 **Velocity-Projected Directional Chunk Sending**: Calculates player heading and speed (walking, riding, Elytra flying, or modded jetpacks) and prioritizes chunk network sending in a forward cone. No more flying into the void!
* 🔄 **Pooled Thread-Local Zlib Decompression**: Reuses thread-local `Inflater` instances with exact buffer allocation, eliminating decompressor allocation garbage and GC stalls.
* 🛡️ **Atomic Reader Leases & Self-Healing Reads**: Atomic CAS reader leases (`RegionFileLease`) prevent the cache trimmer from evicting a file mid-read. If an eviction race ever occurs, ChunkFlow transparently re-opens the live file guaranteeing **zero false nulls** and **zero lost chunks**.
* 🧹 **Intelligent Cache Trimming**: Expands the region file cache to 512 (configurable up to 4,096) and performs clean, idle-only LRU trimming instead of vanilla's devastating full-cache wipes.
* 🔒 **StampedLock Concurrency**: Non-blocking optimistic offset table reads. Writers hold exclusive write stamps during sector reallocation, verified with 0 torn reads under extreme torture testing.

---

## 📊 Performance & Benchmarks

Measured using the ChunkFlow multi-threaded stress harness ([`RegionTorture`](https://github.com/Brilliafy/ChunkFlow)):

| Benchmark Metric | Vanilla / Standard Forge | ChunkFlow 1.0.1 | Improvement |
|---|---|---|---|
| **Region Read Throughput** | ~91,650 reads/sec | **~268,784 reads/sec** | **+193% (2.93x faster)** |
| **High-Concurrency Reads** | Serialized behind RAF lock | Fully parallel (`pread64`) | **Up to 5.9x faster** |
| **Concurrent Writer Integrity** | Subject to race conditions | StampedLock / Zero torn reads | **100% Data Integrity** |
| **Zlib Inflater Allocations** | 1 per chunk read | Pooled / Reused | **~100% Allocation Reduction** |
| **Region Cache Eviction** | Destructive full purge (256) | Clean idle-only LRU trim | **Zero Cache Thrashing** |
| **Elytra Chunk Streaming** | Radial Euclidean (stalls ahead) | Velocity-projected cone | **Eliminates Void Holes** |

---

## 📦 Requirements & Installation

### Requirements:
* **Minecraft**: `1.12.2`
* **Mod Loader**: [Cleanroom](https://github.com/CleanroomMC/Cleanroom) `0.6.x+` (recommended) or [Minecraft Forge](https://files.minecraftforge.net/) `14.23.5.2847+`
* **Required Dependency**: [**MixinBooter**](https://www.curseforge.com/minecraft/mc-mods/mixinbooter) `10.2` or newer
* **Java**: Java 8 through modern Java 21+ runtimes supported.

### Installation:
1. Make sure **MixinBooter** (version 10.2 or higher) is installed in your `.minecraft/mods` folder.
2. Download `chunkflow-1.0.1.jar` and place it in your `mods` folder.
3. Launch the game!

> **Client vs Server**: ChunkFlow is **100% server-side compatible**! Clients do not need ChunkFlow installed to join a server running it. However, installing ChunkFlow on your client is highly recommended for singleplayer worlds and LAN games.

---

## 🛠️ In-Game Commands

Requires OP / Permission Level 2:

* `/chunkflow stats`   Live report of worker threads, pool capacity, off-thread chunks loaded, cached regions, and zlib reuse counters.
* `/chunkflow threads <0|n>`   Dynamically change the worker thread count at runtime (`0` = automatic `cores - 1`).
* `/chunkflow toggle`   Toggles async chunk I/O parallelism on or off without restarting.
* `/chunkflow region stats`   Displays region read throughput, decompressed chunk counters, and zlib cache metrics.
* `/chunkflow region toggle`   Toggles the fast lock-free region I/O path.
* `/chunkflow region clear`   Resets cumulative region read statistics.
* `/chunkflow directional stats`   Shows directional chunk sending metrics, biased calls, and boosted chunk counts.
* `/chunkflow directional toggle`   Toggles velocity-projected directional chunk ordering.
* `/chunkflow directional clear`   Resets directional metrics.
* `/chunkflow reload`   Hot-reloads settings from `config/chunkflow.cfg`.

*(Aliases: `/chunkpipe`, `/cpipeline`)*

---

## ⚙️ Configuration

Config file: `config/chunkflow.cfg`

* **`I/O Threads`** (Default: `0`): Number of chunk I/O worker threads. `0` selects `cores - 1` (capped at 4 for spindle disk safety). Can be raised up to `64` for high-end dedicated servers.
* **`Max Threads`** (Default: `8`): Upper bound for automatic thread allocation.
* **`Scale With Players`** (Default: `false`): Dynamically scales worker threads between min and max threads based on online player count.
* **`Max Cache Size`** (Default: `512`): Maximum number of region files kept in memory before idle eviction (Vanilla is 256). Range: `64` to `4096`.
* **`Velocity Bias Weight`** (Default: `0.6`): How aggressively forward chunks are prioritized when moving quickly (0.1 to 0.9).
* **`Min Velocity Threshold Sq`** (Default: `0.04`): Minimum player speed required to activate directional chunk sending.

---

## ❓ Frequently Asked Questions (FAQ)

**Q: Can ChunkFlow corrupt my world saves?**  
**A:** No. ChunkFlow only alters how existing chunks are read from disk and prioritized for network sending. World saving continues through standard write channels, guarded by `StampedLock` write stamps that prevent any concurrent reader conflict. Every build is verified against our comprehensive `RegionTorture` test suite with 0 torn reads.

**Q: Does ChunkFlow generate new chunks?**  
**A:** No. ChunkFlow optimizes reading and streaming chunks that **already exist on disk**. If you are looking for parallel multi-threaded world generation for new chunks, pair ChunkFlow with its companion mod **WorldFlow**.

**Q: Is ChunkFlow compatible with Chunk-Pregenerator?**  
**A:** Yes! ChunkFlow includes built-in detection for Chunk-Pregenerator and enables safe concurrent region file interop automatically.

**Q: Can I use ChunkFlow in my modpack?**  
**A:** Absolutely! ChunkFlow is licensed under the open-source **GPL-3.0** license. You are free to include it in any public or private modpack on CurseForge, Modrinth, FTB, or custom launchers.

---

## 🔗 Links & Community

* **Source Code & Issue Tracker**: [GitHub](https://github.com/Brilliafy/ChunkFlow)
* **Cleanroom Discord**: [Cleanroom Community Discord](https://discord.gg/cleanroommc)
* **Companion Mods**: [WorldFlow](https://github.com/Brilliafy/WorldFlow) (Terrain Generation) • [OTG-Flow](https://github.com/Brilliafy/OTG-Flow) (Concurrent OTG World Engine)
