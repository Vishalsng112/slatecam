# ADR 0001 — Project identity

- **Status:** Accepted
- **Date:** 2026-11-23
- **Phase:** 0

## Context

Phase 0 must fix the pieces that become expensive or impossible to change
later. The application ID is permanent once a build is distributed; the license
determines what contributions and dependencies are legal; the contribution
model determines how much friction a new contributor meets.

Name space check at decision time: no GitHub repository named `slatecam` and no
product named "SlateCam" found in general web/Play Store searches. No formal
trademark search was performed.

## Decision

| Item | Value |
|---|---|
| Name | SlateCam |
| Application ID / namespace | `io.github.vishalsng112.slatecam` |
| Repository | `github.com/Vishalsng112/slatecam` |
| License | MIT |
| Copyright holder | Vishalsng112 |
| Contribution model | DCO 1.1, no CLA |
| Versioning | SemVer `versionName`; `versionCode` derived from git tags in CI |
| Build flavors | Single `foss` flavor; `full` (ML Kit GenAI) deferred to Phase 6 |
| Distribution | GitHub APKs + F-Droid (`foss` only) |

## Rationale

- **MIT** was chosen for maximum contributor and integrator freedom. The
  project accepts that this permits closed-source forks and mitigates the
  branding risk through `TRADEMARK.md` rather than copyleft.
- **`io.github.*`** avoids a domain purchase obligation while remaining F-Droid's
  documented convention for projects without their own domain. The handle is
  lowercased because Android IDs allow no uppercase and no hyphens.
- **DCO over CLA** keeps contribution friction low and avoids collecting
  copyright assignments the project does not need. Relicensing is explicitly
  not a goal.
- **Single flavor** keeps Phase 0–5 free of Play Services and conditional AI.
  MIT leaves the door open to a `full` flavor later without a relicensing
  event.

## Consequences

- The application ID is tied to a personal GitHub handle. Moving the repository
  to an organization later is fine but leaves the ID semantically stale. It
  must not be changed after the first distributed build.
- F-Droid builds are signed with F-Droid's key; GitHub APKs with the project
  key. Users cannot migrate between sources by installing over each other.
- Any future product that links proprietary Google libraries must live in a
  non-F-Droid flavor and must be reviewed for license compatibility.
- Because there is no copyleft, the device database and profile formats should
  be documented well enough that a fork can interoperate without permission.

## Alternatives considered

- **GPL-3.0-or-later** — better protection against closed forks and a simpler
  single-build story, but incompatible with the planned ML Kit GenAI assistant.
- **Apache-2.0** — same permissiveness as MIT plus an explicit patent grant;
  rejected only because MIT was preferred for familiarity.
- **`org.slatecam`** — cleanest ID but requires owning `slatecam.org`.
