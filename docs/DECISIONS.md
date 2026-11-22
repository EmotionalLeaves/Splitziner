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
- **General notes sit in the upper-right corner**, under the revision block. The standard prefers the upper left and names this as an alternate location. With the top view above the front view and the side view to its right, the upper right is always empty, so the views can be drawn at a larger scale.
- **The stroke font is this project's own alphabet**, not Hershey Simplex. Fifty-odd glyphs on a small grid were quicker to draw than a licence was to check, and carry no outside terms. Lowercase is drawn as small capitals.
- **Hidden lines covered by visible lines are removed by exact interval subtraction** along each line, rather than by testing midpoints against a spatial index. No JTS needed, and partial overlaps are handled.
- **The sheet chooser reserves the band above the title block across the full width** and treats the notes and revision block as a keep-out corner, instead of subtracting the notes' height from the whole sheet.
- **Golden-file tests are not in place yet.** Drawing tests check numbers and determinism, and the sheets are looked at by eye from `core/build/preview/` before each release.
- **The build order is rebuilt forwards after peeling.** Read backwards, the peeling order can schedule a part before the neighbours that hold it up (a cabinet's top before its sides). The planner now takes, at each step, the earliest group that can slide in past what is built, preferring parts that stand on the floor, then parts that touch something already there. Every part is still proven to slide into place. This departs from the Workshop library, whose order is the reversed peel.
- **Exploded parts travel at least a set share of the assembly's size** (12% on the drawing, 20% in guide steps). The library's minimum clear travel moves a thin panel only a hair, which does not read as "pulled apart".
- **Guide step pictures share one framing**: each leaves room for the whole finished object, so the picture does not jump in size from step to step.
- **Lowercase text in the guide is drawn as small capitals**, since the stroke font has capitals only.
- **A lone solid part is not sectioned** unless the cut finds a cavity. The library sections any view whose hidden lines outweigh its visible ones, which on a sculpted solid produces a hatched silhouette that shows nothing new.
- **A file is read as inch-designed only on real evidence**: at least half its sizes on an inch grid, and inch roundness ahead of millimetre roundness by 0.2. The library's inch grids go down to 1/32, which random sizes hit about a third of the time, so sculpted models were being labelled inches by chance.
- **Flat levels must cover 0.2% of the model's surface** to be dimensioned, as well as 15% of the largest level, so stray facets on curved models are ignored.
- **Step wording is keyed by the parts in the step**, not by step number, so it follows the step when the order is changed.
