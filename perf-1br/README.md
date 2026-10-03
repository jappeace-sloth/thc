# 1br performance log

This branch (`jappeace-sloth/thc`, `1br-performance`) applies generic
runtime changes to THC `0ae57cbf` and measures them on
[1br](https://github.com/jappeace/1br), the one billion row challenge in
Haskell. Each change is one commit; this file records what each one was
checked against and what it measured. The analysis that motivated them
is 1br's [thc/PERFORMANCE.md](https://github.com/jappeace/1br/blob/master/thc/PERFORMANCE.md).

## How each commit is checked

All on one laptop (Ryzen AI 7 350, 8 cores, 16 threads), GHC 9.14.1 with
complete Core, cabal 3.16.1, clang 18.1.8, GraalVM 25.3.4.1.

- THC's per-commit JUnit group, as its CI runs it:
  `python3 .github/scripts/fast_ci.py start`, then `compile-common`, then
  `group --group commit --cadence commit`, on the committed tree. 510
  cases per handoff mode. On unmodified `0ae57cbf` eight fail in both
  modes in this container, all environmental: two expect `/tmp` paths
  that resolve into the nix store here, five count native file
  descriptors, one cannot load `libstdc++.so.6`. A commit passes when
  nothing else fails.
- An address gate: every non-quarantined JUnit class whose source mentions
  native allocations or `Addr#` reads (113 classes, 800 cases per mode),
  prepared with `fast_fixtures.py --tests CLASS` and run with
  `./gradlew testDefault --tests … testDense --tests …`. Its baseline
  failures are the same environmental ones plus two that fail only under
  the gate's load and pass alone (`PinnedPointerCellsTest`,
  `NativeAddressTest.preparedAddressOperationsUseTheInvokingNativeRegistry`).
- 1br's test suite through THC (65 cases, 13 of them through THC), then
  wall and CPU time of 10M rows with THC's defaults and of a billion rows
  with `-Dpolyglot.compiler.MaximumGraalGraphSize=400000
  -Dpolyglot.engine.OSR=false`, after three idle minutes. Every report is
  compared byte for byte with native GHC's.

## Results

| commit | THC tests | 1br tests | 10M, defaults | 1B, tuned | 1B allocated |
|--------|-----------|-----------|---------------|-----------|--------------|
| `0ae57cbf` (THC upstream) | baseline | 65/65 | 37.7 s | 154.5 to 166.3 s, via a patched launcher | |
| launcher settings overridable | no new failures | 65/65 | 39.1 s | 168.4 s, 2250 s CPU | |
| liveness without the lock | no new failures | 65/65 | 34.6 s | 130.4 s, 1802 s CPU | 340 GB |
| scalar reads in one locked load | no new failures | 65/65 | 34.6 s | 112.4 s, 1477 s CPU | 275 GB |

Allocation is the heap growth between collections summed over a
`-Xlog:gc` log of the same run.

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
