# Contributing to SlateCam

Thanks for wanting to help. SlateCam is a camera app, which means bugs are
expensive and hardware is unforgiving. Please read the architecture rules
before writing code.

## Before you start

- Open an issue before starting substantial work. Someone may already be on it,
  and we would rather argue about design in text than in a review.
- Small, focused pull requests. One concern per PR.
- Fixes that require a physical device must say which device you tested on.
- Do not submit generated or undocumented code you cannot explain.

## Developer Certificate of Origin (DCO)

Every commit must be signed off, certifying you have the right to submit it
under the MIT license. Full text: [dco.txt](dco.txt).

```sh
git commit -s -m "core: describe the change"
```

That appends:

```
Signed-off-by: Your Name <you@example.com>
```

Forgot it? `git commit --amend -s --no-edit` (last commit) or
`git rebase --signoff main` (branch). CI enforces this on every pull request.

## Architecture rules

These are not style preferences; breaking them causes real regressions.

1. **Kotlin owns state.** Dart may hold a copy for rendering, never the
   authority. Do not add camera logic to Dart.
2. **No pixels over the bridge.** Preview and analysis stay native. If you
   find yourself base64-encoding a frame, stop and reconsider.
3. **One pixel path.** All frames go camera → GL → destination. A new feature
   must not introduce a parallel camera-to-encoder path.
4. **Probe, don't guess.** Never gate a feature on `Build.MODEL`, `Build.SOC_MODEL`,
   or a brand name. Add it to the probe schema and the quirks table instead.
5. **The bridge is an API.** Pigeon definitions live in `:bridge` and are
   versioned. Renaming a field is a breaking change.

## Adding a device

Device support is data, not code:

1. Run the prober and attach its JSON output to a device report issue.
2. If the device needs a workaround, add an entry to the quirks table with
   evidence: affected Android version, the failing request, and the workaround.
3. Quirk entries are reviewed for specificity. "Always disable 10-bit" will be
   rejected; "ignore `DYNAMIC_RANGE_TEN_BIT` on this model below build X"
   is acceptable.

## Code style

- Kotlin: official Kotlin style, 4-space indent. `ktlint` and `detekt` gate CI
  once Phase 2 lands.
- Dart: `dart format`, 2-space indent.
- Commits: Conventional Commits (`feat:`, `fix:`, `docs:`, `core:`, `build:`).
- No commented-out code, no debug logging left enabled.

## Testing expectations

| Change area | Required evidence |
|---|---|
| `:core:common`, `:core:profile` | Unit tests |
| `:core:probe` | Unit tests + probe output from at least one real device |
| `:core:camera`, `:core:codec`, `:core:gpu` | Real-device test notes; CI cannot validate these |
| `:bridge`, `:ui` | Manual test on device |

Before pushing, run the module boundary check:

```sh
cd android && ./gradlew checkModuleGraph
```

Emulator results are not accepted as evidence for camera or encoder behavior.
Remember that the Gradle root is `android/`; `./gradlew` does not exist at the
repository root.

## Reporting security issues

Do not open a public issue. See [SECURITY.md](SECURITY.md).
