# Where the 1br work stopped (5 Oct 2026)

`1br-performance-v2` holds the gated commits on THC `4c198dde`; its
[README](README.md) logs what each one was checked against and measured.
This branch adds one commit that has not been gated:

- `Reap the native address registry only as it grows`. The commit group,
  the address gate and the benchmark were started and stopped. Still to
  do: run the gate, then three alternating cold billion-row pairs
  against the launcher size-limit commit with OSR on. A JFR recording of
  an earlier build with the same change took the main thread's final
  merge from about 9 s to 4 s.

Branch `parked-pinned-base` holds a change that measured no gain (see
the README).

## Upstream state

ekmett/thc#1138 (launcher overrides) was merged as `93be556a`, reshaped
into one `System.getProperty` per option. Upstream `main` is 27 commits
past `4c198dde`. Against it:

| commit | applies to upstream main |
|--------|--------------------------|
| liveness without the lock | conflicts; upstream reworked `ManagedNativeAllocations` for retiring owned malloc storage |
| scalar reads in one locked load | applies |
| launcher metrics only for diagnostics | applies with a three-way merge, 2 files, +25/-4 |
| plusAddr# reads without the offset address | conflicts |
| join and capture locals start typed | applies |
| compilations over 640 KB install | applies |
| reap only as the registry grows | applies |

## What limits a billion-row run now

With OSR on (the size-limit commit), per-thread JFR timelines show:

1. Each worker interprets its first 27 MB chunk. The chunk loop's first
   OSR compilation is requested about a second after the cached
   interpreter starts, takes 10 to 12 s on a loaded machine, and all
   sixteen threads hit an uncommon trap within a second of entering it
   (HotSpot's deoptimization log places it in `continueAt` just after
   `handleBranchBackward`). The recompilation takes another 12 s.
   Finding that trap is the largest remaining item; making the first
   compilation stick would save roughly 12 s.
2. The root's first regular compilation lands before any chunk has
   ended, and the first thread to leave the loop in it deoptimizes at
   the exit branch, which no profile had seen.
3. The main thread's merge at the end (see the ungated commit above).
4. Host-level deoptimizations of the generated `continueAt` put
   interpreting threads into the JVM's own interpreter for seconds at a
   time with OSR off; with OSR on they are rare.

## Tooling

`ClassifySamples.java` here reads a JFR recording. The gate and
benchmark scripts (commit group, address gate, paired cold runs, warm-up
probes, the instrumented `BytecodeRootGen` for tag and bytecode-update
logging) live outside the repository in the working machine's
`~/vibes/thc-dev`.
