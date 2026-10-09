# Building KoperLib

The build targets Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3 and Java 25. Exact versions are in `gradle.properties`.

## Local build

Use a Java 25 toolchain and Rust/Cargo. Gradle may run on a newer JDK, but compilation targets Java 25. Machine-specific `org.gradle.java.home` paths are unnecessary.

From the repository root:

```bash
./gradlew compileJava test
./gradlew build
```

The build compiles the native workspace in release mode and bundles the available libraries. Module jars are written to `build/libs/`. For a release, run `./gradlew buildWindows` first (Rust target `x86_64-pc-windows-gnu` plus MinGW) so the Windows libraries are bundled next to the Linux ones; then `./gradlew build releaseJars` packs them into the three release jars in `build/release/` (KoperLib Core, Fullpack APIs, Khysics). The root `koper_lib` jar is a development aggregator only, and Koperstuff is a separate developer jar.

`./gradlew runClient -PdevModules=core,specific,kender,khysics,elpe` starts the dev game with only the listed modules, to check that a release jar runs without the others.

For a standalone Rust check:

```bash
cd engine
cargo build --release
```

Run the Gradle command from the repository root afterwards. On Windows a loaded native DLL can remain locked; close the game before replacing it.

## Native libraries

Each owning module bundles its own library under `native/<platform>/`:

| Module | Library stem |
|---|---|
| Kender | `koperlib_engine` |
| Fullpack | `koperlib_fullpack_engine` |
| Khysics | `koperlib_khysics_engine` |
| Elpe | `koperlib_elpe_engine` |
| Kodel | `koperlib_kodel_engine` |

Core extracts libraries to `<game directory>/.koperlib/natives/<module>/<platform>/` and loads them for their owner. Kodel has a Java sampler fallback. Lua scripting and native physics require their native libraries.

The packaging targets are Linux x86_64, Windows x86_64, macOS arm64/x86_64 and Android arm64. A build does not guarantee all five binaries are present.

| Gradle task | Target |
|---|---|
| `buildRust` | Host native libraries |
| `buildLinux` | `x86_64-unknown-linux-gnu` |
| `buildWindows` | `x86_64-pc-windows-gnu` |
| `buildMac` | `aarch64-apple-darwin` |
| `buildMacIntel` | `x86_64-apple-darwin` |
| `buildAndroid` | `aarch64-linux-android`, using the configured Termux compiler |
| `buildAll` | Requests every cross-platform task |

Cross-compilation needs the matching Rust target, linker and platform SDK. Missing targets can be skipped by the build, so inspect bundle warnings and the actual jar contents before distributing it.

```bash
./gradlew buildAll build
```

Prebuilt libraries can be supplied separately:

```bash
./gradlew build -Pkoperlib.prebuiltNatives=/path/to/native-libraries
```

## Addon development

Modules publish independently to Maven Local:

```bash
./gradlew publishToMavenLocal
```

Use the coordinate of the module the addon actually needs, such as `com.koper.koper_lib:koperlib-fullpack:0.1.1-alpha`. See [Java addons](JAVA_ADDONS.md). Kopermod currently consumes local module jars directly; build KoperLib and Koper Mana Lib before building it.

## Verification

Module tests cover codecs, graph examples, model sampling and other isolated behavior. KFX JSON examples use the real graph parser, and its Lua examples are exercised by the Rust scripting tests:

```bash
./gradlew :koperlib-effects:test
cd engine
cargo test -p koperlib-scripting
```

Koperstuff adds developer commands such as `/koperlib stress all`. Runtime tests and live shader scenes are separate checks; a successful compilation does not establish visual compatibility or release readiness.

*Claude AI used for documentation.*
