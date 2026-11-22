# Splitziner build spec

The full spec (19 sections: algorithms, constants, data model, sheet format, tests, milestones) lives here:
https://claude.ai/artifact/5U2kWukekg1vKPNVBbM2oA

It is too long to mirror in the repo by hand. The rules from it that every change must follow:

1. The owner works from a phone only. Everything reaches them as an APK from GitHub Releases.
2. All geometry and drawing logic lives in `:core`, which has no Android imports, so it is tested on a plain JVM.
3. Prove geometry with numbers and with pictures: run the engine on fixtures and look at the output before pushing.
4. Never invent engineering facts. Tolerances, materials, fastening methods come from the user.
5. Never put NASA, GSFC or a NASA logo on any output. The app borrows the drawing format, not the identity.
6. Record every departure from the spec in `docs/DECISIONS.md` with the date and reason.
7. A version counts as shipped only when the Release workflow has published its APK.

Source documents (supplied by the owner, not in the repo): the "3D Model Workshop" skill PDF (eng3d 2.2), whose
Appendix A holds the Python algorithms re-implemented here, and the GSFC Engineering Drawing Standards Manual
X-673-64-1F, which defines the sheet format.
