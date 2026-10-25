# Contributing to ChunkFlow

Thank you for your interest in contributing to **ChunkFlow**! We welcome bug reports, feature proposals, architectural improvements, and pull requests.

---

## 🛠️ Development Setup

ChunkFlow is built for **Minecraft 1.12.2** on **Cleanroom** and **Minecraft Forge**, using RetroFuturaGradle.

### Prerequisites
- **JDK 17, 21, or 25**: Required to run the Gradle build daemon.
- **Git**: For version control.
- **IDE**: IntelliJ IDEA (recommended) or Eclipse / VSCode.

### Initializing the Workspace
```bash
# Clone the repository
git clone https://github.com/Brilliafy/ChunkFlow.git
cd ChunkFlow

# Build the mod
./gradlew build
```

### IDE Setup (IntelliJ IDEA)
1. Open IntelliJ IDEA and select **Open**, then choose the `ChunkFlow` root directory.
2. Allow Gradle to import and sync dependencies.
3. If necessary, run `./gradlew idea` or `./gradlew genSources` to generate IDE metadata.

---

## 🧪 Testing & Verification

Because ChunkFlow operates on the lowest levels of Minecraft's disk I/O and concurrency layers, **all changes to I/O logic, concurrency, or decompression must be verified against our torture test suite**.

### Running the Torture Harness
Before submitting any changes to `RegionFileMixin`, `RegionChunkReader`, `RegionFileLease`, or `FastZlibPool`:

```bash
# 1. Compile the mod classes
./gradlew compileJava

# 2. Compile and run the torture test
mkdir -p /tmp/cftest
javac -cp build/classes/java/main -d /tmp/cftest tools/RegionTorture.java
java -cp build/classes/java/main:/tmp/cftest com.cleanroommc.chunkflow.torture.RegionTorture
```

The test must complete with `ALL TORTURE TESTS PASSED` and **0 torn reads** under concurrent writer conditions.

---

## 📐 Code Style & Architecture Guidelines

1. **Zero Garbage in Hot Paths**:
   - Chunk loading runs across multiple threads at thousands of operations per second. Avoid unnecessary heap allocations, boxing/unboxing, or temporary collections in `RegionChunkReader`, `FastZlibPool`, or `DirectionalScore`.
   - Prefer thread-local direct buffers, reusable streams, and primitive types.

2. **Strict Thread Safety**:
   - Concurrency primitives must use proper memory visibility guarantees (`volatile`, atomic operations, `StampedLock`, or synchronized blocks where appropriate).
   - Never introduce race conditions that could lead to deadlocks, data races, or corrupted sector tables.

3. **Mixin Discipline**:
   - Keep mixins minimal and surgical.
   - For obfuscation compatibility when `remap = false`, avoid hooking methods whose descriptors contain non-standard or obfuscated Minecraft types where possible.
   - Always account for runtime initialization gaps: initialize `@Unique` fields lazily if there is any chance the target constructor bypasses field initialization bytecode.

4. **Preserve Documentation**:
   - Keep comments and Javadocs informative, accurate, and up to date with implementation changes.

---

## 🌿 Git & Pull Request Guidelines

1. **Branching**:
   - Create a descriptive feature or bugfix branch:
     ```bash
     git checkout -b feature/your-feature-name
     # or
     git checkout -b fix/issue-description
     ```
   - Never commit directly to `main`.

2. **Commit Messages**:
   - Write clear, structured commit messages following conventional commits:
     - `feat(io): add ...`
     - `fix(mixin): resolve ...`
     - `perf(zlib): optimize ...`
     - `docs(readme): update ...`

3. **Submitting a Pull Request**:
   - Fill out the provided [Pull Request Template](.github/PULL_REQUEST_TEMPLATE.md).
   - Ensure `./gradlew build` succeeds without compiler warnings or errors.
   - Describe what changed and include verification or benchmark logs if modifying performance-sensitive code.

---

## 🐞 Reporting Issues

If you encounter a bug or crash:
1. Search the [GitHub Issue Tracker](https://github.com/Brilliafy/ChunkFlow/issues) to verify the issue has not already been reported.
2. Provide a clear and concise description of the issue.
3. Attach full logs from `logs/latest.log` or `logs/debug.log` (use a paste service like Pastebin or GitHub Gist).
4. Include your exact mod loader (Forge version or Cleanroom version), MixinBooter version, and operating system.

Thank you for helping make Minecraft 1.12.2 faster and more reliable!
