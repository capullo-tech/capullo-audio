# Simultaneous-probe calibration (no-muting upgrade)

Status: implemented, HARDENED after a 5-run rig stress test, then given the three
commit-blocker fixes from a Fable design review (2026-07-22), 22 unit tests green. Review
brief + reply: `~/capullo-tech/ADVISOR-differential-verify.md` / `-REPLY.md`.

THREE COMMIT-BLOCKERS (now implemented):
1. PROBE THE REFERENCE. The reference takes probe slot 0 and is identified by its own
   displacement, never by "the unmoved salient cluster" — a fixed music self-similarity
   ghost can be exactly that, and electing it biases every delta by a constant the
   differential verify cannot see (common-mode → a false green checkmark). Attribution now
   matches all entities (reference = index 0) uniformly and catalogues unclaimed baseline
   leaders as ghosts. Caps a batch at PROBE_SET_MS.size − 1 targets. PROBE_SET_MS is now
   {60,90,210,390}: every value in (offsets ∪ pairwise differences) is ≥ 2×MATCH_TOL apart
   so no shifted grid aliases another (needed for the verify consensus).
2. VERIFY CONSENSUS ≥2 + AMBIGUITY REJECTION. confirmTracking elects the reference only if
   ≥2 targets land at ref+offset (a lone hit can't name a reference); a leader within tol
   of two expected positions tracks neither. A single apply-target can't reach quorum, so
   it routes to the v1 pair path. To avoid all-or-nothing at exactly 2 targets, any target
   not confirmed in the batch (didn't track, or quorum missed) is re-measured on the muted
   pair path instead of being discarded.
3. READ-BACK. Every batch commit/restore is confirmed against the host's live status
   (`readLatencies` callback) and retried once; a client that stays wrong is downgraded
   success→fault. Tolerates one extra status-refresh interval before condemning (staleness
   ≠ write failure). Scope: batch path only — pair-path and probe-failure restores aren't
   read-back-verified (documented gap).

KNOWN LIMITATION — web-client id churn across broadcast sessions (diagnosed 2026-07-22).
A web (WebAudio) client stores its `qcweb-…` id in `localStorage` (webui index.html:415/426),
which is origin-scoped. The snapserver's HTTP port is OS-assigned per broadcast session
(`SnapserverProcess` / `SnapserverPorts.free`, intentional so multiple capullo apps coexist
on one device), so every broadcast RESTART changes the page origin (`host:port`), wipes
localStorage, and the client mints a fresh id. Consequences: (a) a web client's committed
latency does not carry across a broadcast restart — it recalibrates as a new client; (b)
stale disconnected `qcweb-…` entries accumulate server-side. It is NOT per-run and NOT
Safari-specific: within a single broadcast session the id is stable (rig-confirmed: one
`iPhone i9` across three runs) and calibration persists normally. Native (BT) clients are
unaffected — their id comes from the device, not the browser origin. FIX OPTIONS (deferred):
pin the HTTP port (trades off the multi-app OS-assigned-port design), or GC long-stale
disconnected `qcweb-` clients server-side via Client.Delete (must not evict one that will
reconnect to the same id within the session). For now this is documented, not fixed.

Earlier hardening (still in place): `SyncCalibrator.calibrate` dispatches by client count (3+ → `calibrateSimultaneous`,
2 → v1 pair round); pure clustering/matching/verify live in `PeakAttribution`.

VERIFY = DIFFERENTIAL (rig-mandated rewrite). The first cut anchored verify to the
reference's absolute probed lag; the stress test showed that fails under (a) coarse-clock
drift between captures (reference measured to move ~145 ms though physically fixed),
(b) music self-similarity ghosts at fixed lag, and (c) dense/already-aligned scenes where
the stack sits on every target's old position (false "peak did not move"). Replacement:
after computing corrections, re-apply each target as `correction − VPROBE_slot` (re-probe
by its own Sidon offset), measure once, and confirm via `PeakAttribution.confirmTracking`
— the reference is found by OFFSET-CONSENSUS (the cluster at which the most targets land
at ref+offset), so only peak SPACING matters and drift/ghosts cancel. A target confirmed
at ref+offset gets its aligned value committed (probe removed); one that didn't track is
restored. The final aligned write is unmeasured — justified by linearity, guarded by the
`confirmTracking` non-tracking unit test (a target with no peak at ref+offset MUST fail).

CORRECTION GATING (`SyncCalibrator.gate`, pure/tested): DEADBAND_MS=15 (below → already
aligned, no write), MAX_PLAUSIBLE_DELTA_MS=500 (above → suspected mis-attribution, skip;
kept clear of legit cold-start e.g. iPhone-web +217), and a SPLIT-HALF CONSISTENCY gate
(replaced the rig-dependent z threshold 2026-07-22): the delta is re-derived on each 6 s
half of the probe capture (`PeakAttribution.halfDelta` relocates the ref/target peaks in
each half) and must agree within SPLIT_TOL_MS=12, else defer. This is loudness-invariant —
the mic sits by the loud reference so across-room BT targets read z~12-14 yet can be
perfectly stable; the old z=14 gate deferred every real BT correction on-rig. A jittery
peak (run-5 noise) disagrees across halves and still defers.

Other deviations from the sketch below:
- Matching has a salience-ratio gate (Z_RATIO_MAX=3): a probe moves a cluster without
  changing its z much. Needed because correlation sidelobes on autocorrelated program
  material can clear MIN_PEAK_Z and steal a probe match from a quiet speaker.
- Probe-slot overflow (>4 targets) and batch-level failures degrade to muted v1 pair
  rounds; overflow targets stay muted DURING the batch so a stable un-probed speaker
  can't be mistaken for the reference. Web clients (`qcweb-`) never enter fallback.
- An all-deferred / all-already-aligned run (no successes, no faults) reports Done, not
  Failed — re-measuring an aligned system must not look like a failure.

## Why

v1 (`SyncCalibrator`) reduces N speakers to N−1 pair rounds by muting all other
targets during each round. Correct, but each round silences speakers for ~60–90 s,
which is disruptive when several rooms are actively listening.

## Idea

All clients stay audible. One measurement window, but each target client is probed
**concurrently with a unique latency offset** so every correlation peak becomes
attributable in a single pass:

1. Baseline measurement (all speakers audible): peaks P0 = {p_i}, unattributed —
   each audible speaker (plus room reflections) contributes one cluster.
2. Probe: for target k (k = 1..N−1), apply `latency_k − PROBE_k` where the PROBE_k
   are pairwise-distinct and pairwise-difference-distinct (a Sidon/B₂ set, e.g.
   {40, 90, 150, 230} ms), so no two probe shifts can be confused with each other
   or with a reflection offset.
3. Second measurement: for each k, find the peak that moved by exactly PROBE_k
   (±tol). That peak's un-shifted lag is client k's arrival time; the peak that
   moved for no k is the reference.
4. Per-client correction: `delta_k = round(lag_k − lag_ref)`, applied as
   `latency_k + delta_k`, all in one shot; single verify measurement afterwards
   (expected: all salient peaks within tolerance of each other).

Two measurements + one verify total, regardless of N, nothing muted.

## Constraints / open points

- Peak count grows with N (direct + reflections per speaker): needs a larger
  `peakCount` in `DelayMeasurement.estimateSpeakerDelays` and a clustering step
  (group peaks within ~5 ms before matching, match on cluster leaders).
- Attribution tolerance vs probe spacing: MATCH_TOL must stay well under half the
  minimum pairwise difference of the PROBE set.
- Loudness imbalance: a quiet distant speaker's peak may fall below MIN_PEAK_Z
  while louder ones dominate the PHAT spectrum. Fallback: any unattributed target
  degrades to a v1 muted pair round just for that client.
- The verify criterion generalizes from "top-2 spread" to "all salient peaks
  within tolerance" — but reflections make "salient" ambiguous; use attributed
  clusters only.

## Reuse

`Dsp`, `ReferencePcmRing`, `MicCapture`, `DelayMeasurement` are unchanged. Only
`SyncCalibrator.calibrate/calibratePair` is replaced by the batch flow; keep the
v1 pair round as the per-client fallback path.
