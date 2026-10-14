# ChunkFlow

![ChunkFlow](logo.png)

Chunk loading and I/O optimization for Minecraft 1.12.2 on Cleanroom or Minecraft Forge.

## Overview

ChunkFlow speeds up how the game loads chunks from disk and how it decides which chunks to send
to players. It combines three related optimizations:

- Parallel asynchronous chunk loading across all CPU cores.
- Pooled zlib decompression for region file reads.
- Directional chunk sending that favors the direction a player is moving.

## Parallel async chunk loading

Forge's `net.minecraftforge.common.chunkio.ChunkIOExecutor` owns the pool used to read
already-generated chunks from the region files. That pool is created with a core size of 1 and an
unbounded queue. A `ThreadPoolExecutor` only adds workers beyond its core size when the queue is full,
and an unbounded queue never fills, so the pool never grows. Forge's `adjustPoolSize(playerCount)` only
raised the core to `max(1, players / 50)`, which is 1 for any normal server.

Everything for a disk-loaded chunk then ran on that single thread: the region read, the zlib
decompress, the data fixer pass, the NBT parse, and the construction of the `Chunk` object. Chunk
Pipeline sets the core and max pool size to `cores - 1` (configurable) and cancels `adjustPoolSize` so
Forge cannot shrink it again.

This is safe to parallelize. Reads from a region file are already synchronized inside
`RegionFile#getChunkDataInputStream`, and `AnvilChunkLoader#readChunkFromNBT` builds a self contained
`Chunk` that does not touch shared world or lighting state. Work that does touch the world, such as
entity loading, structure recreation and `populate`, still runs on the main thread through
`ChunkIOExecutor.tick()`.

Only `tick()` and `adjustPoolSize(int)` are hooked, and both have descriptors without Minecraft types.
That keeps the mixin valid under the obfuscated runtime.

## Region file I/O

`RegionFile#getChunkDataInputStream` is reimplemented to decompress with pooled, thread-local
`Inflater` instances and to return an exact sized buffer. Vanilla allocates a new inflater for every
chunk, which adds up once several loader threads are running. The reimplementation keeps the same
sector and length validation as vanilla, and falls back to the vanilla path when the option is turned
off.

## Directional chunks

For a moving player the chunks in front of them are the ones they will need first. ChunkFlow
overrides `PlayerChunkMapEntry#getClosestPlayerDistance` with a velocity projected distance, so the
chunk send queue favors chunks ahead of the player. The bias scales with the player's speed and can be
tuned or disabled.

## Requirements

- Minecraft 1.12.2
- Minecraft Forge 14.23.5.x or Cleanroom 0.6.x
- MixinBooter 10 or newer
- Java 8

## Installation

Put `chunkflow-<version>.jar` into your `mods` folder. It runs on the client and on a dedicated
server. The largest benefit comes from installing it on the server, since that is where chunks are
read from disk.

## Configuration

File: `config/chunkflow.cfg`

### Async Chunk I/O

| Option | Default | Description |
|---|---|---|
| Enable | true | Turns the parallel async pool on or off. |
| I/O Threads | 0 | Worker threads. 0 picks `cores - 1`, with a minimum of 2. |
| Max Threads | 8 | Upper bound for the automatic count and the value above. |

### Region File I/O

| Option | Default | Description |
|---|---|---|
| Enable | true | Turns the fast region read path on or off. |
| Enable Zlib Pooling | true | Reuses inflaters instead of allocating one per chunk. |
| Enable Fast Decompression | true | Uses the reimplemented `getChunkDataInputStream`. |

### Directional Chunks

| Option | Default | Description |
|---|---|---|
| Enable | true | Turns directional send ordering on or off. |
| Velocity Bias Weight | 0.6 | How strongly forward chunks are favored. |
| Min Velocity Threshold Sq | 0.04 | Speed above which the bias applies. |

## Commands

- `/chunkflow stats` shows the live pool state and region counters.
- `/chunkflow threads <0|n>` sets the async worker count.
- `/chunkflow toggle` enables or disables the async pool.
- `/chunkflow region [stats|toggle|clear]` controls the region read path.
- `/chunkflow directional [stats|toggle|clear]` controls directional ordering.
- `/chunkflow reload` reloads the config.

Aliases: `/chunkpipe`, `/cpipeline`. Requires permission level 2.

## Notes and limitations

- Vanilla synchronizes reads per region file, so reads of the same region serialize. The speedup is
  roughly the number of worker threads up to the number of regions being read, plus full parallelism
  for the NBT parse and chunk build that happen outside that lock.
- This mod covers chunks that already exist on disk. Generating brand new terrain is handled by other
  mods in this suite.

## Credits

Written by Brilliafy.

## License

GPL-3.0. Copyright (C) 2026 Brilliafy. See the LICENSE file.
