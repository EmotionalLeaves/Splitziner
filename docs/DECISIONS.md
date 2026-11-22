# Decisions

Departures from the build spec, newest first.

## 2026-11-21

- **Versions match the owner's Rescale app** (AGP 9.5.0, Kotlin 2.4.21, Gradle 9.8.2, compile SDK 37, JDK 21 in CI) instead of being looked up fresh. They are known to build in this owner's CI.
- **Releases are changelog-driven** (`## vX.Y.Z` in CHANGELOG.md, tag `vX.Y.Z`), the same way Rescale does it, instead of one release per CI run number. Fewer, named releases are easier to follow on a phone.
- **The APK is a release build signed with a public key** in `keystore/`, not a debug build. Same effect (every build installs over the last), smaller and faster app.
- **JSON is written with Android's built-in `org.json`**, not kotlinx.serialization, to avoid a compiler plugin for a few small files.
- **The oriented box** tries the world axes and boxes standing on the three largest flat faces. The spec's PCA candidate was replaced by the second and third face candidates, which find a board's true box more reliably.
- **T-junction repair tests only open edges** (edges used by one triangle). Edges already shared by two triangles cannot be part of a T-junction, so skipping them changes nothing and is much faster.
- **The first release ships before the 3D view.** v0.1.0 shows parts and the cut list as lists; the viewer follows.
- **The spec is linked from `docs/SPEC.md` rather than copied** into the repo; the document is too long to mirror by hand.
