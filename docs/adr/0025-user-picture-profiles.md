# ADR 0025 — User picture profiles

- **Status:** Accepted
- **Date:** 2026-11-25
- **Phase:** 4/5 (colour and console)
- **Depends on:** ADR 0020 (picture profiles), ADR 0024 (state channels)

## Context

The look pipeline had two built-in profiles (Standard, SlateLook) and one
editable `custom` profile whose settings lived in a single preference value.
There was no way to keep more than one custom look, no name, and no delete. The
custom editor did have a whole-profile reset, but not per-parameter reset.

Users want several saved looks, each selectable as its own chip in the Picture
profile card, and expect edits to persist. Built-in looks must stay
reproducible and read-only.

## Decision

1. **Library, not a single custom slot.** `PictureProfileLibrary` in
   `:core:profile` is the pure editing model. It stores `UserPictureProfile`
   entries (id, name, `ProfileSettings`) and enforces normalisation, unique
   lookup, rename/delete and per-parameter reset rules.
2. **Built-ins stay constants.** Standard and SlateLook are `PictureProfile`
   constants and are never written. Only user profiles are editable.
3. **Ordered preference persistence in the bridge.** User profiles are stored in
   the existing `shoot-profile` SharedPreferences: an ordered id list plus
   `picture_profile_name_<id>` and `picture_profile_settings_<id>` keys. The
   settings use the existing pipe-encoded `ProfileSettings` format. The active id
   stays in `picture_profile`.
4. **One-time migration.** On first load with no library, the old
   `custom_picture_profile` value becomes a user profile named **Custom**; the
   legacy key is removed. An active id that no longer exists falls back to
   Standard.
5. **Contract.** `CameraState.pictureProfiles` replaces `profileNames`: each
   entry has id, display name and `builtIn`. HostApi gains
   `createPictureProfile`, `renamePictureProfile`, `deletePictureProfile`,
   `resetProfileParameter`; `setProfileSettings` and `resetProfile` now act on the
   active user profile. Built-ins reject edits.
6. **UI.** The Picture profile card lists built-ins + saved looks + a **New**
   chip. Selecting a user profile shows the existing editor. Editing persists on
   each committed slider/choice. The active user profile exposes rename and
   delete actions (delete asks for confirmation); each editor row has a reset
   icon and the card has **Reset all parameters**.

## Consequences

- Multiple named looks survive restart and camera/mode changes.
- Built-in looks remain stable and read-only.
- The Custom profile from earlier builds is preserved as a normal user profile.
- Profile settings still travel in the full `onState` snapshot; slider edits are
  committed on `onChangeEnd`, so the high-frequency telemetry path is unaffected
  (ADR 0024).
- Deleting the active profile switches to Standard; deleting the last user
  profile leaves only built-ins + New, which is valid.
- IDs are generated with a short UUID suffix, so names can be duplicated without
  colliding.

## Alternatives considered

- **Fix the single `custom` slot and add a name only** — does not allow multiple
  saved looks.
- **Store profiles as one JSON blob** — workable, but the bridge already has a
  settings codec; per-key preferences avoid a new parser and keep the order list
  explicit.
- **Let built-ins be edited in place** — breaks reproducibility and the meaning
  of a preset.
- **Auto-save every slider tick** — would push a full state on every drag tick;
  the editor already uses local drafts and commits on release.
