# Post-merge verification of sync + balance — acceptance, written BEFORE the run

Build: quantumcast vc7 (`fbd1b93`) against capullo-audio `e83ff2c`, both merged to main 2026-08-20.
Run from the OPPO CLIENT (reversed rig: OnePlus broadcasts, OPPO holds the mic).

## Measured setup, established before the run

`micz` at 100 % SW, each speaker soloed with the other at 1 % (NOT muted):

| solo | top peak |
|---|---|
| ONEPLUS (HK Neo) | z=11.2 @ 111.0 ms |
| PFFM10 (OPPO's own sink) | z=7.4 @ 113.1 ms |

Two things follow, and both are needed to read the result:

- **The mic is not sitting on its own speaker.** PFFM10 is the WEAKER of the two at the mic, so the
  anchor bias that motivated reversing the rig is not in play.
- **The room is near-aligned as it stands**, arrivals 2 ms apart, with ONEPLUS carrying a -59 ms
  trim. So sync would report `already aligned` and prove nothing. **The run therefore starts with
  ONEPLUS latency forced to 0**, which should re-open a ~59 ms gap and give sync a known target.

`mic=` in `micz` output is ambient RMS and does NOT track speaker gain — it read -59.5 dBFS at
15 % and -59.3 dBFS at 100 %. Only the peak z responds. Do not read levels off it.

Starting volumes: ONEPLUS 60 % / PFFM10 60 %, mid-range so the balance has headroom both ways.
Historical values (94/100, the 60→78→90→100 ladder) do NOT transfer: the HK Neo was off for the
2026-08-19 runs and is powered now, so the acoustic setup is not the same room.

## PASS

1. The run starts on a CLIENT with no spurious refusal.
2. Sync measures the re-opened gap and writes a trim of **-59 ms ± 15 ms** to ONEPLUS, and the
   verify step confirms it. This is the falsifiable half: the value is known independently.
3. Balance produces a correction and writes it. Predicted DIRECTION from the solo readings:
   PFFM10 up relative to ONEPLUS, since ONEPLUS is the hotter of the two at equal gain.
4. No read-back failure.
5. No `qctap-` client enters the run as a calibration target, and no live tap remains afterwards.
   Six stale `qctap-` ids sit in server status, so `3861cee`'s exclusion is under real test.
6. A second run reports `already aligned` and a SMALLER balance correction in the SAME direction.

## FAIL

- A trim far from -59 ms, or a verify that does not confirm = sync is not measuring the real gap.
- A balance correction that raises ONEPLUS relative to PFFM10 = the sign is inverted.
- `read-back FAILED` = the `clientLatencies` fix regressed.
- A `qctap-` id appearing as a balance target = the exclusion regressed.
- Run 2 correction larger than run 1, or reversing direction = not converging.

---

# Addendum — run 2, written BEFORE the run

Run 1 result: sync `trim -60ms (latency -60, residual 12ms, verified)`, PASS against the -59 ms
prediction. Balance reported `already even at the mic (PFFM10=1.00, ONEPLUS=0.95)`, +0.2/-0.2 dB,
below the action threshold, so **it wrote no volumes and its write path went unexercised**.

Also: my run-1 prediction that PFFM10 would go UP was wrong in sign. It was inferred from the solo
z readings (ONEPLUS z=11.2 vs PFFM10 z=7.4), and z is a DETECTABILITY score, not a level. The
balance's own deconvolved levels put the two within 0.2 dB. Do not predict balance direction from
micz z again.

Run 2 therefore introduces a known imbalance instead of guessing at one.

Start: **ONEPLUS 30 % / PFFM10 60 %**, ONEPLUS latency -60 ms (as run 1 left it).

## PASS

1. Sync reports `already aligned` within the deadband, since the trim was just verified.
2. Balance detects ONEPLUS as the QUIET one and moves the pair toward even — ONEPLUS up relative
   to PFFM10. This is the direction test; the imbalance is commanded, so the answer is known.
3. The balance WRITES a volume, and read-back confirms it.
4. Correction is partial, not complete: the 6 dB cap and 0.7 damping should leave the room still
   somewhat uneven after one run. A single run that lands exactly even would suggest the damping
   is not applied.
5. No `qctap-` target, no live tap afterwards.

## FAIL

- Balance moves ONEPLUS DOWN or PFFM10 UP = the sign is inverted against a commanded imbalance.
- `already even` at a 30/60 split = the estimator cannot see a gap this large, which contradicts
  the documented 7-8 dB working range.

---

# Addendum — run 3, written BEFORE the run

Run 2 result: sync `already aligned (Δ-21ms within deadband)`, PASS. Balance measured the commanded
imbalance in the RIGHT direction and a plausible size (PFFM10=1.00 vs ONEPLUS=0.43, -3.6 dB on the
speaker that was cut), then DECLINED: `only 1 of 1 capture(s) agree ... need 3`, because at 30 %
ONEPLUS sat under the detection floor in 3 of the captures. Correct behaviour, wrong experiment —
30/60 is outside the working range in the quiet direction.

Run 3 keeps BOTH speakers well above the floor and puts the imbalance at the top of the range
instead of the bottom.

Start: **ONEPLUS 60 % / PFFM10 90 %**, ONEPLUS latency -60 ms.

Reference point for the size: 30 % vs 60 % measured as 3.6 dB, so the percent-to-dB curve is
compressive and 60 vs 90 should land roughly 2-3 dB — above the action threshold that 0.2 dB fell
under in run 1, and below the 3 dB agreement window that run 2 fell outside.

## PASS

1. Balance harvests at least 3 agreeing captures. This is what runs 1 and 2 could not do at 60/60
   and 30/60 respectively.
2. It identifies ONEPLUS as the quiet one and WRITES a volume, moving ONEPLUS up relative to
   PFFM10. Read-back confirms the written value.
3. The correction is partial (6 dB cap, 0.7 damping).

## FAIL

- A third consecutive no-write outcome (`already even`, `DECLINED`, or `skipped`) = the actionable
  window between the floor and the agreement gate is too narrow to be useful on this rig, which is
  a real finding and must be reported as one rather than retried indefinitely.
- Any write that moves ONEPLUS DOWN = inverted sign.
