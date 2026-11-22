# Splitziner

Splitziner is an Android app that opens an STL file of a whole assembly (a cabinet, a shelf, an enclosure), separates it into its individual parts, and produces what you need to build it: a cut list, dimensioned engineering drawings, an exploded view and a step-by-step build guide. Everything runs on the phone, offline.

## How to install

1. On your phone, open this repo's **Releases** page and tap the newest release.
2. Under **Assets**, tap the `.apk` file to download it.
3. Open the download. If Android asks, allow installs from your browser.

New versions install over the old one and keep your projects.

## What it does

- **Imports** binary and ASCII STL files, from the file picker or "Open with".
- **Separates the file into parts**, whatever order the triangles are in, removes bodies the exporter stored more than once, and repairs small surface gaps.
- **Guesses the units** (STL has none) and lets you correct them; shows sizes in millimetres, decimal inches or fractional inches.
- **3D view** with each part in its own colour, tap to select, and a slider that pulls the model apart.
- **Cut list**: identical parts grouped and counted, described as panels, boards, rods or shaped pieces, with a total length to buy per board size. Share it as CSV.
- **Engineering drawings** as a vector PDF: front, top and side views with hidden-line removal, automatic section views when the inside is hidden, dimensions from one datum corner, a title block, revision block, notes and a list of material; an exploded view with item balloons; and a detail drawing for every part. Also as PNG or DXF.
- **Build guide** as a PDF: a drafted build order with one picture per step, which you can reorder and reword.
- **Split parts** shared as separate STL files.
- **Cutting plan**: panels laid out on sheets and boards along bought lengths, with what to buy.
- **Design check**: finds parts that overlap, parts that touch nothing, hollow shapes drawn as one solid, thicknesses shops do not stock, long unsupported spans and a design that would tip. Tick the **tools** you own and it says which parts you cannot make with them.
- **Editing**: select a part in 3D to resize, move, slide it against its neighbours, copy, square up, split into boards or delete; add new boards; start a new design from scratch. Every change can be undone.
- **Fixing**: splits frames, boxes and channels drawn as one solid into real boards, and proposes changes that make a model buildable (close gaps, join pieces of one sheet, trim overlaps, stock thicknesses), shows them first, and can be undone. Share the changed model as STL.

## What it cannot know

An STL holds only triangles. Round features are approximate, materials and tolerances are yours to enter, and two boards that were fused into one solid in your 3D program stay one part. The build order is worked out from the shapes alone: it checks that each part can slide into place, and knows nothing about glue, clamps or screws. The drawings follow a standard format but nobody has checked them; the in-app **Help** says what to trust and what to verify.

## Reporting a problem

Tap **Copy diagnostic report** in the app and paste the text into an issue or a chat with whoever is helping you.

## For developers

- `core/` is the engine in plain Kotlin with no Android code: `./gradlew :core:test`.
- `cli/` runs the engine on a file: `./gradlew :cli:run --args="model.stl"`.
- `app/` is the Android app. It is only configured when an Android SDK is present.
- A release is cut by adding a `## vX.Y.Z` heading to `CHANGELOG.md` (see `scripts/release.sh`); the CI run for that push tests, builds and publishes the APK.
- The APK is signed with a deliberately public key in `keystore/` so every build installs over the last. Do not reuse it for a store release.
- The build spec is in `docs/SPEC.md`; choices that differ from it are in `docs/DECISIONS.md`.
