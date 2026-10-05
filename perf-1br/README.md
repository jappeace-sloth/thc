# 1br performance log

This branch (`jappeace-sloth/thc`, `1br-performance-v2`) applies generic
runtime changes to THC `4c198dde` and measures them on
[1br](https://github.com/jappeace/1br), the one billion row challenge in
Haskell. Each change is one commit; this file records what each one was
checked against and what it measured. The analysis that motivated them
is 1br's [thc/PERFORMANCE.md](https://github.com/jappeace/1br/blob/master/thc/PERFORMANCE.md).
The first commit is upstream PR ekmett/thc#1138. An earlier series on
`0ae57cbf` lives on branch `1br-performance`; the commits here replay it.

## How each commit is checked

All on one laptop (Ryzen AI 7 350, 8 cores, 16 threads), GHC 9.14.1 with
complete Core, cabal 3.16.1, clang 18.1.8, GraalVM 25.3.4.1.

- THC's per-commit JUnit group, as its CI runs it:
  `python3 .github/scripts/fast_ci.py start`, then `compile-common`, then
  `group --group commit --cadence commit`, on the committed tree. 455
  cases per handoff mode. On unmodified `4c198dde` eight fail in both
  modes in this container, all environmental: two expect `/tmp` paths
  that resolve into the nix store here, five count native file
  descriptors, one cannot load `libstdc++.so.6`. A commit passes when
  nothing else fails.
- An address gate: every non-quarantined JUnit class whose source mentions
  native allocations or `Addr#` reads (114 classes), plus the launcher and
  bytecode-lowering classes the changes touch (840 cases per mode),
  prepared with `fast_fixtures.py --tests CLASS` and run with
  `./gradlew testDefault --tests … testDense --tests …`. Its baseline
  failures are five of the environmental ones; two other tests have
  failed only under this gate's load and passed alone (`PinnedPointerCellsTest`,
  `NativeAddressTest.preparedAddressOperationsUseTheInvokingNativeRegistry`).
- 1br's test suite through THC (65 cases, 13 of them through THC), then
  wall and CPU time of 10M rows with THC's defaults and of a billion rows
  with `-Dpolyglot.compiler.MaximumGraalGraphSize=400000
  -Dpolyglot.engine.OSR=false`, after three idle minutes. From the commit
  that lets compilations over 640 KB install, the billion-row run keeps
  OSR on and passes only the graph budget. Every report is
  compared byte for byte with native GHC's.

## Results

| commit | THC tests | 1br tests | 10M, defaults | 1B, tuned | 1B allocated |
|--------|-----------|-----------|---------------|-----------|--------------|
| launcher settings overridable (ekmett/thc#1138) | baseline | 65/65 | 40.2 s | 177.4 s, 2267 s CPU | 337 GB |
| liveness without the lock | no new failures | 65/65 | 40.3 s | 136.5 s, 1853 s CPU | 341 GB |
| scalar reads in one locked load | no new failures | 65/65 | 35.6 s | 117.5 s, 1483 s CPU | 270 GB |
| launcher metrics only for diagnostics | no new failures | 65/65 | 38.4 s | 82.5 to 92.4 s (mean 87.0), 969 s CPU | 271 GB |
| plusAddr# reads without the offset address | no new failures | 65/65 | 33.2 s | 73.1 to 85.7 s (mean 78.7), 908 s CPU | 90 GB |
| join and capture locals start with their carrier's tag | no new failures | 65/65 | 35.5 s | 66.7 to 98.4 s (mean 82.0), 936 s CPU | 95 GB |
| compilations over 640 KB install (tuned run with OSR on) | no new failures | 65/65 | 32.7 s | 64.4 to 76.8 s (mean 70.7), 914 s CPU | 102 GB |

Allocation is the heap growth between collections summed over a
`-Xlog:gc` log of the same run. On this base single billion-row runs
vary by several seconds, so the rows from the plusAddr# commit on give
three or more cold runs each, most of them alternating with the previous
commit's runtime, three idle minutes before each; CPU and allocation are
from the gate's run.

## Liveness without the lock

`Owner.requireLive` borrowed the allocation (one acquisition of the fair
`ReentrantReadWriteLock`) to check it was not freed. One 8-byte read
through `peekByteOff` took 19 such lock round trips: `plusAddr#` checks
liveness (1); `readWord64OffAddr#` borrows (1) and range-checks through
`size()`, which checks liveness (1); then it assembles the word from eight
byte reads, each of which borrows (8) and range-checks (8). Ten of the 19
are `requireLive`.

[LivenessModel.java](LivenessModel.java) models the original, a flag raised
only once the free holds the write lock, and the committed request
counter, under a free in progress, a free queued behind a held borrow and
a failing free (`java perf-1br/LivenessModel.java`):

```
free in progress  original  blocked, then fault
free in progress  flag      blocked, then fault
free in progress  counter   blocked, then fault
queued free       original  blocked, then fault
queued free       flag      LIVE while the free was queued
queued free       counter   blocked, then fault
failing free      original  blocked, then LIVE
failing free      flag      blocked, then LIVE
failing free      counter   blocked, then LIVE
```

The flag version failed `AtomicAddressTest.pointerValidationDoesNotHoldCellMonitorBehindQueuedNativeFree`
in the address gate, which is how the queued case was found.

## Scalar reads in one locked load

The commit message says `Owner.readScalar` allocates nothing. It allocates
no `Borrow`, but the read lock it takes allocates a hold record (32 bytes
per acquisition) whenever the reading thread is not the lock's first
reader, that is, while another thread is reading the same allocation. In
1br every chunk buffer has one reader, so this run does not show it.

## Launcher metrics

A steady-state JFR profile of a billion-row run with the scalar-read
change attributed 3.8% of CPU samples to `AtomicLong.incrementAndGet`
in `Metrics.incrementTailBounces`: the launcher enabled THC's metrics on
every IO launch although it prints them only under `-Dthc.diagnostics`.
Turning them off unless requested saved 30% of wall time on this base
(21% on the earlier one), far more than the samples suggested, because
sixteen threads were contending for the same counters' cache lines.

## Join and capture locals start typed

The chunk loop's root was invalidated once per run with "local tags
updated" and compiled again. An instrumented copy of the generated
`BytecodeRootGen` logged every cached-tag change: the invalidating ones
were first stores, by a thread still interpreting the root after its
compilation was installed, to `captured typed input N`, `join result` and
`join operand N lane N`, all created without a `FrameSlotKind`. Three
probes after the change showed no such invalidation. Besides both gates,
the 22 JUnit classes that build join points and belong to neither pass in
both modes (248 cases each).

The wall time does not show it with OSR off: four cold runs gave 66.7 to
98.4 s against 80.0 to 85.3 s for the previous commit, the spread coming
from when the chunk loop's compilations land. With OSR on and the size
limit of the next commit passed by hand, three alternating pairs gave a
mean of 68.5 s against 72.0 s, this commit faster in two of the three.

## Where a billion-row run spends its time

A JFR recording of the join-locals commit with OSR off, read with
[ClassifySamples.java](ClassifySamples.java), shows every worker thread
going through the same phases:

- 2 to 9 s: `newTable`'s `setPrimArray`, a C memset that Sulong runs over
  the pinned table, each store converting the buffer to a pointer through
  `NativeAddresses.project`.
- 9 to 33 s: the first chunk, interpreted. The chunk loop is entered once
  per 27 MB chunk, and with OSR off an invocation that started in the
  interpreter stays there until it returns, however soon its root is
  compiled. 16 of the 512 chunks take a third of the run.
- 33 to 60 s: compiled.
- Then the main thread merges the sixteen tables for 9 s, most of it in
  `NativeAddresses.reap`.

The root's first compilation is hardly used: it lands while every thread
is still in its first chunk, before any chunk has ended, and the first
thread to finish a chunk in it deoptimizes at the loop's exit.

## Compilations over 640 KB install

With the 400 000-node budget the chunk loop's OSR code is 873 to 904 KB,
over JVMCI's 640 KB default, so its install failed and graph recovery
replaced the root; that is why the tuned runs above keep OSR off. With
the launcher raising the limit, three alternating pairs against the
join-locals commit with OSR off gave 64.4, 66.8 and 74.8 s against 74.0,
78.5 and 78.1 s.

Not committed: projecting a pinned C buffer once per foreign call (branch
`parked-pinned-base`) takes the start-up memset from 6 s to under 1 s
per worker, but three alternating pairs showed no change in wall or CPU
time. The workers reached their first chunk sooner and interpreted it
for correspondingly longer.
