# Simultaneous-probe calibration (no-muting upgrade)

Status: design only. Branch exists so this doesn't ride on the muting v1.

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
