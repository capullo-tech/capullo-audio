# Post-merge verification of sync + balance on hardware — 2026-08-20

Build: quantumcast vc7 (`fbd1b93`) against capullo-audio `e83ff2c`, both on main.
Run from the OPPO CLIENT (reversed rig). Criteria for each run were written BEFORE it:
`CRITERIA-postmerge-verify-20260820.md`. The raw logcat for the three runs
(`postmerge-run1/2/3-20260820.log`) is kept on the build box, not in this repo; every figure quoted
below is copied verbatim from it.

## Result: both halves work. Sync verified against a known answer; balance verified end to end.

### Sync — PASS, and the test was falsifiable

The room was already aligned to 2 ms, so sync would have reported `already aligned` and proved
nothing. The trim was therefore FORCED TO 0 first, re-opening a gap whose true size was known
independently (-59 ms, the value the rig had carried).

Run 1 measured **-60 ms** and verified it: `trim -60ms (latency -60, residual 12ms, verified)`.
Two earlier attempts in the same run were REJECTED and rolled back — one where verify could not
identify the pair, one at 27 ms residual over the 12 ms gate — so the guard rejects bad trims
rather than keeping them. Runs 2 and 3 then reported `already aligned` (Δ-21 ms, Δ24 ms), which is
the trim holding across two further runs.

`sync used a boost — re-reading levels at the real volumes` appeared in run 1: commit `5efd1f8`,
the boost gap fix, firing on hardware.

### Balance — PASS on the third try, and the two failures are the interesting part

| run | start (ONEPLUS/PFFM10) | measured | outcome |
|---|---|---|---|
| 1 | 60 / 60 | 0.2 dB apart | `already even at the mic` — no write, correctly |
| 2 | 30 / 60 | -3.6 dB, right direction | `DECLINED — only 1 of 1 capture(s) agree ... need 3` |
| 3 | 60 / 90 | ONEPLUS 0.67 vs PFFM10 1.00 | **WROTE** 60 -> 79 and 90 -> 100, 4/4 agreeing |

Run 3 is the write-path proof: 4 of 4 captures agreed, ONEPLUS was identified as the quiet speaker
and raised MORE than PFFM10 (+19 points against +10), so the pair moved toward even in the
commanded direction. No `read-back FAILED` in any run. No `qctap-` id ever entered a run as a
target, with six stale ones sitting in server status, so `3861cee`'s exclusion holds. No live tap
remained afterwards.

## The finding worth keeping: the actionable window is narrow, and BOTH edges were hit

The balance only acts inside a band, and this rig walked into both walls in one afternoon:

- **Under ~1 dB** it declares `already even` and writes nothing (run 1, 0.2 dB).
- **Above roughly 3 dB of separation** the quiet speaker drops under the shared-capture
  correlation floor, most captures fail to identify the pair, and the 3-agreeing-captures gate
  declines the correction (run 2 harvested 1 usable capture of 6).

Run 2 is not a defect: the estimator got the sign and rough size right (-3.6 dB on the speaker that
had been cut 30/60) and then refused to act on one capture. But it means a badly unbalanced room —
the case the feature exists for — is exactly the case most likely to decline on the first pass.
The escape is iterative: each run that DOES write moves the room closer, and the next run then sits
inside the window. Run 3 wrote from a ~3.5 dB gap because both speakers were loud (60/90) rather
than one being quiet (30/60). **Absolute level, not just the ratio, decides whether a correction
lands.**

## Traps confirmed or added

- **`micz`'s `mic=` figure is ambient RMS and does NOT track speaker gain.** It read -59.5 dBFS at
  15 % and -59.3 dBFS at 100 %. Only the peak z responds (z≈5 scattered at 15 %, z=13.6 in a tight
  116-154 ms cluster at 100 %). Two solo checks were wasted reading the wrong number.
- **Do not predict balance direction from `micz` z.** z is detectability, not level. Soloed at
  100 %, ONEPLUS read z=11.2 and PFFM10 z=7.4, which predicted PFFM10 should go up; the balance's
  own deconvolved levels put them within 0.2 dB and, once a real imbalance existed, moved the other
  way.
- **Historical volume values did not transfer.** The HK Neo was off for the 2026-08-19 runs and is
  powered now, so `94/100` and the `60->78->90->100` ladder describe a different room.
- The masking guard fired verbatim in run 3 and is worth quoting: `reference speaker PFFM10 was not
  found at its expected probe position ... masked below the shared-capture correlation floor. Its
  solo level is fine; do not raise volumes.`

## Rig left at

ONEPLUS 15 % / PFFM10 15 %, unmuted, both A2DP sinks playing. ONEPLUS latency **-60 ms**, which is
a VERIFIED trim from run 1, not the stale -59 ms this session started with. PFFM10 0 ms.
