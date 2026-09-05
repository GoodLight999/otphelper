# OTP Helper current handoff

The canonical current-state handoff is now [`PROJECT_CONTINUITY.md`](PROJECT_CONTINUITY.md).

Future work on `GoodLight999/otphelper` should read that file first, then inspect Draft PR #1 and current-head GitHub Actions results. OTP false-positive/false-negative invariants discovered from real notifications are recorded in [`OTP_DETECTION_REGRESSIONS.md`](OTP_DETECTION_REGRESSIONS.md) and must be preserved during extractor changes.

This short file intentionally contains no duplicated architecture, signing, upstream-sync, or OTP-rule state so it cannot silently drift behind the canonical ledger.
