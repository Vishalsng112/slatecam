# Security policy

## Reporting a vulnerability

Do **not** open a public issue for security problems.

Use GitHub's private reporting:
<https://github.com/Vishalsng112/slatecam/security/advisories/new>

If that is unavailable, contact the maintainer through their GitHub profile
and ask for a private channel.

Please include:

- Affected version or commit
- Device and Android version
- Steps to reproduce
- Impact assessment (what an attacker gains)

You can expect an acknowledgement within 7 days and a status update within 30.
There is no bug bounty; credit is given in the advisory unless you ask
otherwise.

## Scope

In scope:

- Camera pipeline and permission handling
- Encoder, muxer, and file output handling
- Storage and MediaStore interactions
- The Flutter/Kotlin bridge and any external input handling
- Anything that could expose captured media

Out of scope:

- Bugs in Android platform code or vendor camera HALs (report those to the
  vendor, but tell us if SlateCam needs a workaround)
- Denial of service by pointing the camera at the sun
- Issues requiring a rooted or physically compromised device

## Handling of captured media

SlateCam records to MediaStore or a user-chosen folder and does not upload
media anywhere. The optional device database sends capability metadata only,
never media, and only after explicit opt-in.
