# Archived next-thread handoff — 2026-09-02

**SUPERSEDED — do not use this file as an active project instruction.**

This snapshot was written before several major changes were completed. In particular, it predates:

- synchronization to upstream `v1.20.6` / versionCode `54` and review of later upstream language fixes;
- creation and repository pinning of the fork's permanent Android signing identity;
- replacement of the old five-Secret signing design with exactly four private GitHub Actions Secrets plus a public repository-pinned certificate SHA-256;
- the guarded `tools/configure-otphelper-signing-secrets.ps1` path for restoring the **existing** permanent JKS without generating a replacement key;
- fixed-signing CI verification and the optional one-command Android CI dispatch from that configurator;
- candidate-ranked OTP extraction, notification-field isolation, and the `244080` / `923030` regression fix while preserving grouped OTPs such as `123 456`;
- conservative persisted phrase-default migration and the current privacy/evidence contracts.

The authoritative restart contract is now:

1. [`CURRENT_HANDOFF.md`](CURRENT_HANDOFF.md), which deliberately stays short;
2. [`PROJECT_CONTINUITY.md`](PROJECT_CONTINUITY.md), the canonical non-secret project ledger;
3. [`OTP_DETECTION_REGRESSIONS.md`](OTP_DETECTION_REGRESSIONS.md) before changing OTP extraction;
4. Draft PR #1 and current-head GitHub Actions results;
5. [`SIGNING_MIGRATION.md`](SIGNING_MIGRATION.md) before any physical signer migration.

Historical statements in the former 2026-09-02 handoff — especially instructions to create a new permanent key or configure a fifth `OTPHELPER_SIGNING_CERT_SHA256` Secret — are obsolete and must not be revived. The existing permanent signer must not be rotated merely because its private backup or GitHub Secrets are temporarily unavailable.
