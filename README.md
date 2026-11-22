# Splitziner

Splitziner is an Android app that opens an STL file of a whole assembly (a cabinet, a shelf, an enclosure), separates it into its individual parts, and tells you what each one is: a cut list with stock sizes and quantities today, with dimensioned drawings, exploded views and a step-by-step build guide on the way. Everything runs on the phone, offline.

## How to install

1. On your phone, open this repo's **Releases** page and tap the newest release.
2. Under **Assets**, tap the `.apk` file to download it.
3. Open the download. If Android asks, allow installs from your browser.

New versions install over the old one and keep your projects.

## What it does now

- Imports binary and ASCII STL files, from the file picker or "Open with".
- Separates the file into parts, removes bodies the exporter stored more than once, and repairs small surface gaps.
- Guesses the file's units (STL has none) and lets you correct them.
- Builds a cut list: identical parts grouped and counted, described as panels, boards, rods or shaped pieces, with a total length to buy per board size. Share it as a CSV.

## What it cannot know

An STL holds only triangles. Round features are approximate, materials and tolerances are yours to enter, and two boards that were fused into one solid in your 3D program stay one part.

## Reporting a problem

Tap **Copy diagnostic report** in the app and paste the text into an issue or a chat with whoever is helping you.

## For developers

- `core/` is the engine in plain Kotlin with no Android code: `./gradlew :core:test`.
- `cli/` runs the engine on a file: `./gradlew :cli:run --args="model.stl"`.
- `app/` is the Android app. It is only configured when an Android SDK is present.
- A release is cut by adding a `## vX.Y.Z` heading to `CHANGELOG.md` (see `scripts/release.sh`); CI builds and publishes the APK.
- The APK is signed with a deliberately public key in `keystore/` so every build installs over the last. Do not reuse it for a store release.
- The build spec is in `docs/SPEC.md`; choices that differ from it are in `docs/DECISIONS.md`.
