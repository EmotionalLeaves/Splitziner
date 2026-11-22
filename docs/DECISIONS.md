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
- **Exact repeats are removed triangle by triangle before splitting**, keeping winding into account, instead of only body by body afterwards. A whole assembly written two or three times over (A B C, A B C, A B C) is then seen once, whatever order the copies come in.
- **The connected-components method was extended in two ways.** Pieces left open by T-junctions are joined when one piece's open-edge vertex lies along another's open edge. And at an edge shared by four or more triangles (where bodies touch), each triangle is paired with the first neighbour met when turning about the edge into its own solid, so touching bodies stay apart and each stays whole. With both, a file whose triangles are in any order still splits correctly; the library falls back to "single body" there.
- **Detail drawings were added: one sheet per cut-list item.** The spec's version 1 stopped at the assembly sheets. Each item is squared up (length across the sheet, broad face to the viewer) and drawn with its own dimensions, numbered `<drawing number>-<item>`. They follow the assembly sheets in the same PDF.
- **Round holes are recognised and dimensioned on single-part drawings.** The spec's version 1 dimensioned flat levels only. A hole is a closed ring of edges around a flat face, eight or more corners all on one circle (within 2% of the radius), with the wall behind it facing inward; rings on one axis are paired into through and blind holes. Diameter is the circle through the rim's corners. Hole centres join the datum dimensions in the view that shows the hole as a circle, with centre lines in every view and a note per size ("3X 8 DIA THRU"). Notes use the word DIA, as the drawing standard's period did, since the stroke font's lettering is plain capitals. Not handled, and said so in Help: threads, countersinks and chamfered rims (the chamfer's outer edge is what gets measured), holes at an angle (reported, not dimensioned), holes in assemblies (left off; they would bury the views).
- **A drilled board is described by its holes** ("BOARD 18 X 40 X 160, 4 HOLES") and called NOTCHED only when the holes do not account for the missing material.
- **A cutting plan was added** (not in the spec). Panels, and shaped parts thin enough to be cut from sheet, are laid out on sheets by thickness and material; boards and rods along bought lengths by section. Sheet layouts use guillotine cuts only (each cut runs right across the piece being cut), biggest piece first into the tightest space; lengths use first-fit, longest first. Stock sizes are the user's, remembered between projects; the defaults (1220 x 2440 mm, or 4 x 8 ft, and a 3 mm or 1/8 in saw cut) are common sizes and the plan says they are assumed. It is not an optimal packing and says so.
- **A design check was added** (not in the spec), prompted by testing a real desk drawn in a phone 3D app. From shapes alone it reports: parts that overlap (sampled every 6 mm in the space two parts share, each point tested with three rays so a gappy surface cannot fool it), parts or groups that touch nothing, hollow shapes drawn as one solid, thicknesses outside a list of common stock, flat parts running more than 40 thicknesses between the parts that hold them, and a balance point outside the footprint. Overlaps under 3.5 mm are "check", deeper ones "problem". Balance treats every part as the same material and sag is a rule of thumb; both findings say so. It never judges joints, loads or materials.
- **Tools are the user's to tick**, per project, remembered as the default for the next one. Each part's work is read from its outline (curves, notches, openings, holes, shaped edges). A missing tool is a problem, a tool that can only do the job slowly is a check. The tool-to-work table is ordinary workshop practice written down in `Tools.kt`, not something measured.
- **Models can be changed in the app, as a list of edits replayed on the untouched file.** Flat parts are handled as an outline pushed through a thickness, which gives clean trims, merges and thickness changes with 2D geometry. Automatic fixing is deliberately timid: it moves loose groups no more than 30 mm, joins overlapping flat pieces only when they share a plane and thickness, trims only when the other part passes right through and under a quarter of the part is lost, and moves odd thicknesses to the nearest stock size. Everything else is listed as left for the maker. It does not cut recesses or rebuild hollow one-piece bodies as boards.
- **Parts with gaps in their surface are now described by their measured shape** when under a fifth of their edges are open, instead of by outer size only; small gaps barely move the volume.
- **The app is run on an emulated phone in CI** (a second job after the build, not blocking the release). It opens a small cabinet and the real desk file (kept, with exact repeats removed, under `app/src/androidTest/assets`), visits every tab, applies the fixes and makes the exports.
- **Solids drawn as one piece are split into boards** by cutting the part, in its own box frame, along the planes of its large flat faces into a grid, testing each cell for material (three rays, majority), and taking the largest all-material blocks first. Flat parts use their outline instead, so rounded corners do not leave slivers, unless the part has a step in its thickness, when the layered result is used because flat members would fill the step. A result is refused unless it accounts for the part's volume (or outline) within 12%. It is applied automatically only where one piece cannot be bought or cut: hollow solids and frames 3/4 in (18.5 mm) or thicker. Thinner frames are offered with the sheet waste stated.
- **Exported STL files carry each part's number in the facet attribute bytes**, and files whose header begins "Splitziner" are split by those marks when read. Other programs use those bytes for colour, so they are not trusted from other files. Text STL files with several solids keep each solid as a part.
- **Editing is part-level**: resize along the part's own box axes, move, slide until touching, copy, square up, split into boards, delete, add a rectangular board. There is no free-form modelling. A new design starts from one board, because the project keeps an STL as its source and STL has no empty file.
- **Sub-assemblies are the boards that came from one drawn part.** They are made first, each kind in one bench step ("make 2"), and the main build places them whole. Parts standing on the floor go in before parts resting on supports not yet placed.
- **Fastening is planned from contact geometry with rules of thumb**, stated in Help: through the thinner part along the meeting direction, into the other by 1.5 times that thickness but never within 3 mm of its far side, at the longest stock length that fits; one per 250 mm, at least two. Face-to-face pieces are glued and pinned; tops are fixed from underneath with brackets; a receiving part under 15 mm across the screw gets glue in a groove or a cleat, never a fastener. No strength calculation is made.
- **Drawings get extra exploded sheets** per build step until every listed item has a balloon (at most six), and a list of material too long for sheet 1 gets sheets of its own.
- **In inches, boards are bought as standard lumber.** A board 3/4, 1 or 1-1/2 in thick is cut from the narrowest 1x, 5/4x or 2x board at least as wide (ripped narrower if needed); boards of other thicknesses are cut as strips from sheet. Thicknesses within 3% of a stock one count as it. Panels thicker than any sheet are listed as glue-ups. Metric projects keep cutting boards from lengths of their own section, because planed timber sizes differ too much between countries.
- **The automatic fixer cuts back only the smaller of two overlapping parts** (the one entering the other), except for overlaps under 3.5 mm, where either may be trimmed. Shortening the larger part is offered on the Check tab with how much it removes.

- **Which way is up is the user's choice per project**, Z up by default because STL has no "up". The file is turned by whole quarter turns (never mirrored), before edits are replayed; edits made the old way up are kept as they are, and the dialog says moves will follow the new axes. Nothing guesses the axis: furniture lying on its back is still valid furniture, and a wrong guess would silently reorder the whole build.
- **Plain square boards get no detail drawing.** A board or panel with no notch, hole or shaping is fully given by the three sizes in the list of material, which is how shops treat them. A general note on sheet 1 names those items in ranges. Shaped, notched, drilled, round and leaky parts still get a sheet each.
- **Every listed item is pointed to.** Items hidden in every step view (inside a frame, under a top) get a last exploded sheet drawing only them.
- **Guide instructions name parts fitted the same way together**, in build order ("Fit items 3, 4 and 7 from above, in that order"). A step that needs a page to itself gets a full-width picture and its parts list in two columns underneath.
- **Groove sizes are given as a rule of thumb**: as wide as the thin part, 0.4 of the grooved part deep, at most 9.5 mm (3/8 in). The thin part is not enlarged in the model or cut list; the guide says how much to add. Without a router, table saw or CNC ticked, the guide offers a cleat only.
- **Smaller sheets are offered by halving the stock sheet** (half: full width by half length; quarter: half by half), because those are the sizes stores sell. The user's own sheet size is halved, so a metric 1220 x 2440 sheet gives 1220 x 1220 and 610 x 1220.
- **Joints between different sub-assemblies, or between a sub-assembly and a loose part, belong to the main step in which the later of the two is placed.** Only boards of one sub-assembly are joined on the bench.
- **New designs come from five templates built of plain boards with butt joints** (bookcase, cabinet, table, bench, open box), with default sizes in stock thicknesses for the user's units. They are starting points to edit, not a parametric modeller: once created, a template is an ordinary model.
- **Weights use typical densities for common materials** matched from the material text (longest matching word wins: "birch plywood" before "plywood"). Unknown materials are left out and named; the title block's WT is filled only when every item's material is known, and always says ABOUT.
- **Where the part a screw must pass through is thicker than the longest stock screw less 20 mm of bite**, the joint is made with inside-corner brackets.
- **An overlap is accepted as a housing joint (groove) only when the smaller part enters the bigger no deeper than half the bigger part's thickness.** The model is not changed: the overlap is the groove. Its size is read from the overlap's box (depth along the direction the housed part sticks out). Trims and cut-backs that cost little are still preferred by the automatic fixer; housing is its last resort before leaving an overlap to the maker.
- **Edge banding is estimated for square sheet panels aligned with the model**, counting free edges except those facing the back (+Y, taken to be against a wall) and the floor, with 10% for trimming.
- **The whole model is resized by stretching, not scaling.** Every vertex beyond a plane moves; the plane goes through the middle of the widest corner-free band between 25% and 75% of the size, so only plain lengths are cut across and every board keeps its thickness. Shrinking is limited to 90% of that band.
- **New inch projects default to fractions** (to 1/16 in), as woodworkers in inch countries read sizes; drawings in decimal inches remain one setting away.
- **OBJ files are read as well as STL**, objects and groups becoming parts, and are taken as Y-up by default because that is the OBJ convention; the user can turn them like any project.
- **A drawer is an open-topped box (bottom, walls on left, right and front or back, nothing over it) sitting between parts on both sides within 100 mm.** It is built on the bench as one sub-assembly and slid in whole; it floats in the model because slides are hardware, so the check does not call it unconnected. A pair of slides is added to the hardware, at the longest 2 in (50 mm) step that fits its depth, and less than 12 mm a side is flagged for ball-bearing slides.
- **Back panels (thin front to back, at the back of a piece with depth) are taken to stand against a wall** and get no edge banding.
- **A door is a thin panel at the front of an opening, 0.5 to 6 mm clear of the parts either side and touching nothing**: it hangs on hinges, which are hardware. Hinges are counted by height (two to 900 mm, three to 1500 mm, four beyond) as concealed cup hinges, the common kind for sheet-goods cabinets. Doors added in the app are inset with 2 mm clear all round.

## v0.35–0.36: doors, handles, shelf pins
- Hinge count by door height (2 to 900 mm, 3 to 1500, 4 beyond) is a common cabinetmakers' rule, not a calculation; hinge cup size (35 mm) and positions follow the hinges' own template.
- Every door and drawer adds one handle or knob to the hardware list. Style and size are left to the maker, as the model has none.
- Overlay doors are found only when a box's front edges are behind all four sides (or a twin door on one side), so a desk's modesty panel on legs is not taken for a door.
- Doors, drawers and adjustable shelves are taken off first when planning, so the guide fits them last, once the box is finished. Identical ones go in together in one step.
- Adjustable shelves: 1.5 mm short each end, resting on 4 pins. Hole rows follow the common 32 mm system (37 mm in from the edges, 32 mm apart), given in millimetres in inch mode too, because jigs are made for it. Hole size is left to the pins bought (5 mm most common).

## v0.40–0.43: professional outputs and look
- Cutting plan: in metric, boards of sheet thickness are ripped from sheet when wide (150 mm or more), thin (12 mm or less), or when that sheet is bought anyway; filler boards that would cost an extra panel go back to lengths. In inches, boards that would need ripping from lumber come from a sheet already bought. The packer tries 3 piece orders x 3 split rules and keeps the fewest panels, then the emptiest last panel.
- Applied drawer fronts (wider than the box) are not part of the drawer's bench unit: the box is hung, then the front is set with even gaps and screwed on from inside. Screw length is through the box end and up to 4 mm short of the front's face.
- Doors, drawers and adjustable shelves leave the model straight out of the front in the plan and the exploded views. A whole drawer, merged into one mesh, is trusted to slide out on its slides.
- A thin part flush with an edge of the part holding it sits in a rabbet; otherwise in a groove.
- Drawing scales follow the units: inch halves (1/2, 1/4, 1/8, 1/16...); metric uses ISO 5455 (1/2, 1/5, 1/10, 1/20...). The largest view must be 80 mm or more before a sheet is accepted.
- A part hidden in all three principal views (doors' clearance gaps ignored, 3 mm) makes the right view a full section, so it can be located.
- Part names (Side, Top, Shelf, Rail...) are guesses from position and shape; a name from the file or the user always wins. Narrow boards (under 150 mm) are never shelves, sides or tops.
- UI: fixed colours (drafting blue, amber) instead of wallpaper colours, so screenshots and guides look the same everywhere; all six tabs in one fixed row (smaller lettering under 380 dp) rather than a scrolling row that hid Guide and Check.

## v0.47–0.53: build order like a woodworker's, outputs to use in the shop
- Exploded views keep front-to-back order along a shared pull direction. A part in front of another that moves the same way (an applied front before its box) goes at least as far, plus a gap of 1/8 of the box's depth.
- Cutting plans never turn digits (a sideways 8 reads as ∞). A strip too narrow for its number gets a small upright number, or one beside it with a short leader.
- Drawing views may come down beside the list of material, to just above the title block, when they stay clear of the list's left edge.
- Tables and benches on legs are built from end frames: two legs and the rails between them that pair every leg once, taking the shortest such rails (the ends). The frames are made on the bench, then joined by the long rails.
- A step that stands something on the floor touching nothing yet built goes in with the next step, which joins it. Hung parts are never merged this way.
- A top with nothing above it is never screwed through its face; it is fixed from underneath with brackets. A leg's end under a top that aprons carry gets no joint of its own.
- Thin back panels go on after the shelves they cover (within 25 mm), so the case is squared with the back. They move past only steps that don't come in from behind, and they stay before hung doors, drawers and pin shelves.
- Consecutive steps that fit one more of the same item the same way are one step (up to 5 parts). Like parts in one step move together in pictures, with one arrow and a count.
- A thin part's rabbet or groove is explained in full the first time; later steps refer back to that step.
- In-app outputs: the cutting plan is shown full screen and redrawn as stock sizes change. The shopping list is a checklist whose ticks are kept per project. Build mode shows one step per screen, and the steps marked done are kept per project. Advice such as "buy 10% extra" is a note, not a line to tick.

## v0.59–0.61: legs, slats and cleats
- Parts touch when their faces meet anywhere, including across the middle of both faces (a slat over a centre rail). Before, contact was only found near the parts' own corners and face middles.
- Legs are square posts standing on the floor, at least 1.5 times as tall as they are thick, so a bed's short legs count. Legs are named before the rest, so that rails can be recognised by the legs they join.
- Four or more identical narrow boards lying flat are slats. A narrow strip fixed face-on along a bigger board and butting two legs is a cleat. Only the joint along its length is fastened; its ends need nothing.
- A board resting on separate supports under both ends, or along both long edges, is fixed down to them. Where its ends only butt legs or rails, nothing is driven in sideways. A cabinet bottom on a single kick board is neither, so it is screwed as before.
- A rail against a leg is screwed, never "glued face to face". Slats are screwed down; only tops (named so, or 150 mm or wider) count as visible tops that must not be screwed through.
- A step that rests on supports fitted later has the step with those supports moved before it. Sub-assemblies are judged by their own boards, not their overall box.
- New templates: Workbench and Bed frame. Both are built from end or side frames, with any cleats included in the frame.
- Boards and rods may be bought in the stock length or a quarter and a half longer (8, 10 and 12 ft, kept exact; metric rounded down to 100 mm, so 2.4, 3.0 and 3.6 m). Each kind of board uses the one length that buys the least wood (ties go to fewer pieces, then the shorter length). A length only partly used is bought in the shortest length that still holds its pieces. This can be turned off in the cutting plan.

## v0.62–0.69: printing, text and spacing
- Printing goes through Android's own print screen, using the same PDF as Share. Each drawing sheet keeps its own paper size where the printer allows.
- Guide step titles name what goes on, with role names and counts ("4 shelves", "hang the 4 drawers"). A step that fits more than three different things keeps a plain "Step N".
- Plain fixings from one part into several are written as one line, with the total count and the number of joints. Hung parts (doors, drawers, slides, pins, applied fronts) keep a line per part.
- Counts up to twelve are written as words. Above twelve they are written "24 × 5/8 in", so a count never runs into a size.
- Template sizes are checked before a project is made: a part with less than 1 mm on any side means the sizes don't fit.
- Rails joining legs are side rails (along the long way, at the outside within a leg's thickness), end rails (across), or a centre rail (not at the outside).
- Even spacing is stated once, in the first step that lays any of the parts. It applies to three or more like parts lying flat with gaps of at least 3 mm that are equal within 1.5 mm.

## v0.77
- Dowels are named as sold (3/8 x 1-1/2 in, 1/4 x 1-1/4 in; 8 x 40 mm, 6 x 30 mm) and "Before you build" says how to mark and drill them with a jig: a little over half the length into the end or edge, never more than two-thirds through a board.
- Glue and brads: brads forgive a thinner edge than screws (9 mm, not 15 mm), but a 1/4 in panel's edge still splits, so it goes in a rabbet or against a cleat. The "too thin to take …" line names what the chosen method drives (screws, brads, dowels).
- Pieces that stand on the floor next to the first piece, touching nothing yet (a table's second end frame, a bed's other side frame and centre frame, a desk's second side), are set out in the first step instead of being "fitted from the front" with whatever ties them. That step's title says "set out the 3 frames" or "start with the bottom", and when the pieces stand apart it says to prop or clamp them until the next step joins them.
- A pair of one item fitted from opposite sides (front and back aprons, left and right sides) is one step: "Fit 2× item 3 from the front and back".
- In a step that adds parts to a made frame, a board is only blocked by the frame boards that lie across its path. A slat resting on a frame's cleat now goes in from above instead of being slid in sideways.
- Bench steps use the bench picture's directions in their text, including step 1. A rail sitting on its legs is lowered onto them.
- A step title too long for the page is set smaller rather than running off the edge.

## v0.78
- The thickest sheet taken as sold for furniture is 1 in (25.4 mm) in inches and 30 mm in metric. A panel thicker than that (a 1-1/2 in workbench top) is cut as 2 or 3 equal layers of a stock sheet thickness, preferring 3/4 in / 19 mm. Each layer is cut 1/4 in (5 mm) over size and the glued panel is trimmed together. The cutting plan, shopping list and "Before you build" all say so, and also offer buying a butcher-block or worktop panel that thick. No layer split → the old "not in the plan" note.
- Edge banding must be at least as wide as the edge is thick; when the banded edges differ (3/4 in and a 1-1/2 in top), the line says which needs the wider banding. No banding product widths are invented.
- Guide pictures prefer a front view more strongly (0.6 instead of 0.4). One part going in from the back no longer turns the whole guide around (the desk). A picture still drawn from behind is labelled "SEEN FROM THE BACK".
- The first step gives the spacing of pieces that stand apart, read off the model: outside to outside, any piece between them "centred" or measured from the first, and "ends lined up" when the outer two line up.

## v0.79
- The guide says where a part sits when touching other parts doesn't fix its place. Distances are read off the model, along the two directions across the face where it meets the longer part it is fixed to:
  - from the end(s)
  - up from the bottom or down from the top edge
  - from the front, back, left or right edge
  - "Centre … on the width of …" when it is centred
- Skipped:
  - flush edges
  - a part held between two others along that direction (a cleat between legs)
  - items whose even spacing the step already gives
- Bench steps measure against the longest touching part of the unit. Build steps measure against a part already built or a different item going in with it. The first step, which only sets things out, gets none.
- The shelf-spacing line is named from the parts going in ("the shelves (item 3)"), not from every part of that item.

## v0.80
- Positions in build steps are only given when the step adds at most two kinds of item and they take at most two lines. A busy step of a sculpted STL listed a dozen offsets that the picture shows better.
- Drawer slides:
  - The line now says where the cabinet half goes, read off the model. Its centre line is level with the middle of the drawer box's height, measured down from the top of the cabinet side beside it. Its front end is flush with, or set back from, the front edge, as the box is.
  - Drawer halves are centred on the drawer sides, flush with the box front. This is the usual practice for side-mount slides. The slide maker's instructions are still the authority.
  - Several drawers hung in one step are one line, with their lines listed top first, even when the boxes are different sizes (graduated drawers).

## v0.81
- Screws are named as sold, gauge and length: "#8 x 1-1/2 in", "4 x 40 mm". The gauge follows the board the screw passes through, the usual rule of thumb: #6 (3.5 mm) under 5/8 in, #8 (4 mm) up to 1 in, #10 (5 mm) thicker. Brads and pocket screws are unchanged.
- "Before you build" gives pilot-hole bits for the gauges used, from common woodworking charts (#8: 3/32 in softwood and plywood, 1/8 in hardwood and MDF), with countersinking.
- Doors:
  - A pair meeting in the middle hang from their outer edges.
  - A single door's hinge side is the maker's choice, and the line says so. Before, every door was quietly marked "hinged left".
  - The end hinges go about 3 in (75 mm) from the top and bottom, a common cabinetmakers' rule, with any others evenly between.

## v0.82
- The material chosen for an item now goes with each of its parts, so joining, banding and the guide can tell solid wood from sheet goods.
- Solid wood panels (oak, pine… at least 5/8 in or 15 mm thick) are not planned on a 4 × 8 sheet: solid timber isn't sold that way.
  - They are glued up edge to edge from boards no wider than a 1x6 (5-1/2 in actual), which keeps them flat. The count allows about 1/8 in lost per jointed edge. In metric, equal strips no wider than 150 mm, plus 5 mm for planing.
  - Boards are cut 1 in (25 mm) long and the panel cut to size once dry.
  - "Before you build" gives the usual glue-up practice: alternate growth rings, joint the edges, clamp above and below.
  - The plan's notes also offer buying an edge-glued panel.
  - Thinner "oak" (a 1/4 in back) is taken to be veneered plywood and stays on a sheet.
- Edge banding skips solid-wood panels: their edges show no layers.
- A top of solid wood fixed with brackets gets the seasonal-movement advice: slotted brackets or tabletop fasteners, with the screws a little loose across the grain.
- Pins instead of rabbets: with screws chosen, an edge too thin for a screw but thick enough for a brad (a 1/2 in drawer side) is glued and pinned, as drawer boxes usually are.

## v0.83: tested on complex models
`scripts/complex-models.py` writes five multi-part models (a two-column dresser, a kitchen base cabinet with a drawer over two doors, a bunk bed, a pedestal desk, and a face-frame bookcase with an overhanging cap). Running the whole pipeline on them found and fixed:
- Face frames: flat strips across the front of a case, backed by its front edges, are stiles and face frame rails, not legs. The sides behind an overhanging top are sides, not dividers. A side is a part with no panel beyond it that overlaps it.
- An applied drawer front is screwed through the box end only, never "through the drawer side" end-on with 3 in screws.
- A top between the sides is screwed through them. A full-depth divider and a 1/4 in back panel under a top are not "supports along both edges". Brackets are never fixed into a thin panel's edge.
- Parts are named bottom up within a step (kick board, bottom, then the divider on it).
- Drawers and their fronts hang in one step, up to 12 (was 5, which split a chest of six).
- Slide lines are measured up from the top of the panel the drawers stand over, which every column shares. Rows of drawers side by side give each height once.
- An overlay drawer under a rail is found: the "lid" test uses the box, not its taller front.
- Doors and drawers are never merged into a structural step. Doors and drawer fronts get no offsets: hinges and even gaps place them.
- Where there is a choice, the build goes bottom up, so a bunk bed's lower deck is finished before the upper rails go on. "Rails before slats" only moves rails at the slats' level.
- Spacing is given per level (each deck of slats), and parts resting on supports get no height offset.
- Exploded pictures cap a part's travel by its second-largest size (a door's width), not its length. A 6 ft cleat no longer flies a cleat-length away and shrinks the picture.

## v0.84
- A face frame (stiles and rails flat across the front) is its own sub-assembly. It is made flat on the bench, with pocket screws through the rails' ends on the back (or glue and dowels). It goes on the finished case before doors and drawers, glued to the front edges and pinned with brads set below the surface. Screws through a stile's edge, which would show, are no longer suggested, and a face frame is never the starting piece.
- Equal starting candidates (a pedestal desk's three uprights) go by how many parts they touch. A piece standing alone that nothing ties in until later (a desk's far end panel) waits for the step that joins it, instead of being propped up from step 1 or fitted with an unrelated back panel.
- Pocket screws are used only where a pocket can go: an end or edge meeting a face.
  - Two faces together take plain screws, and so does a board lying on its support (a slat on its cleat, a bottom on its kick board), screwed down through it.
  - A thin panel (a 1/4 in back) is pinned.
  - An edge too thin for a screw but fine for a brad is glued and pinned, as with screws.
  - Found by running the complex models with every joining method: a bunk bed asked for 168 pocket screws, many of them through slats.

## v0.85
- More complex test models: a corner shelf with triangular shelves, a round table on legs set well in, and a stool with legs splayed 8° both ways.
- A straight stick whose ends are sawn off square is described with its angles: "ENDS CUT AT 8° AND 8° (COMPOUND)", not "NOTCHED". It gets a detail drawing with a note to set both the mitre and the bevel; its length is measured along the long edge. Detected from the end faces: flat, parallel, tilted.
- A "centre leg" is judged against the other legs' outline, not the whole model. A round table's legs, set well in from an overhanging top, are just legs.
- A thick stick of no standard lumber size (a 2-1/2 in square leg) is planned from the next standard size up (4x4), planed or ripped down. Before, it fell to "thicker than any sheet sold". The cut plan's notes heading is now "NOTES".
- Step titles count parts by their own names ("the bottom and 3 shelves", "the front and the back").

## v0.86
- Repeated modules: a set of parts repeated two or more times, each copy shifted by the same step sideways and standing on the floor (four base cabinets under one worktop). Each copy is one unit. Doors, drawers and their fronts stay out of the units and go on at the end. Parts across them (the worktop) are fitted after. A vertical repeat (shelves and dividers) is not a module.
  - A unit of more than 6 parts is built over several bench steps (at most 5). Later steps show earlier parts greyed. A lone first piece is merged into the next step. Every copy's parts are matched to the first copy's by item and position in the unit, so their joints land in the right step.
  - All the cabinets are set out together and clamped flush. Neighbouring sides are screwed together with screws shorter than both sides, no glue, so the run can come apart to move.
  - A top on flat stretchers is screwed up through them, through clearance holes, with screws that stop well short of its face.
  - A long, thick "Top" bigger than a sheet suggests a ready-made worktop.
- A door with doors on both sides (a row of cabinets) is still a door if an upright stands behind one of its edges. Doors hang together in one step, up to 12.
- Boards alike that lie end to end in line (stretchers across a run of cabinets) are not slats.

## v0.87
- Modules must be boxes that meet only side to side (two panels back to back). Bays that share an upright (a library whose shelves run into the next bay's side) are one piece of furniture.
- The device tests now open a 69-part kitchen run (four cabinets, eight doors, four drawers, one worktop), build its guide and export it as a PDF, so complicated projects are checked on a phone on every release.

## v0.88
- Ran every complex model with every joining method and scanned the guide text for anomalies.
- Dowels follow the pocket-screw rule: only an end or edge meeting a face. Two faces, a board lying on its support, or a thin panel take plain screws or brads. A plain screwed or pinned joint is worded as such whatever method is chosen.
- Even spacing is only given across empty gaps (not across a divider between two bays' tops) and is also looked for within one stack (a bay's shelves). Ties in build order go to the group that reaches less high.
- Metric boards snap to whole-millimetre stock thickness: a 3/4 in board is 19 mm, not "19.1".
- A step with more than three kinds of part is titled with the first two "and N more", instead of a bare "Step 3".

## v0.89
- Dados and rabbets drawn as overlaps are kept as housings by the automatic fixes. The overlap must be 3/16 in (4.5 mm) or more deep, no deeper than half the panel, and run across the board's whole end or edge (it is shorter than the board along exactly one direction).
  - Before, a deliberately dadoed bookcase had its shelves trimmed back to butt joints.
  - A tenon (only part of an end) still goes to the cut-back and trim choices.
- The housed-joint line says "dado or rabbet" and points to the part's drawing for the size.
- Project names come from file names: underscores and hyphens become spaces, and an all-lowercase name gets a capital first letter ("kitchen_run.stl" becomes "Kitchen run").

## v0.90
- Detail drawings of a part with housings show them:
  - The grooves and rabbets that run right through the part one way are cut into the drawn part, along the way most of them run. A dado stopping within 1/2 in of an edge, where it meets the back rabbet, counts as through.
  - The views draw them, and the ordinate dimensions place each one.
  - The rest stay in the notes, which now give each groove's position from the bottom end (as assembled) and name each rabbet by the edge it runs along.

## v0.91
- Hardware totals give gauged screws as sold, after the count: "26 screws, #8 x 1-1/2 in" or "18 screws, 4 x 40 mm". Before it read "26 × 4 x 40 mm screws".
- `scripts/fuzz-templates.py` builds every template with random sizes (half to double each default, random options) and checks for crashes and odd text ("null", "item -", "(0 joints)"). 120 runs found none. Sizes that cannot fit are refused with a message, as intended.

## v0.92
- Full-size paper templates are made only for outlines with curves or angled edges. An outline of square-cut straight edges only (a frame, an L, a notched slab) is quicker and truer to mark out with a rule and a square, so its detail drawing is enough. On the sample desk this cut the full-size pages from 100 to 25.

## v0.93
- Round and oval tops get no paper template: a circle is marked with a trammel from its centre, an ellipse with two nails at its foci and a string as long as the oval (the gardener's method). Detected when every outline vertex sits within 1% of the ellipse; only flat parts (thickness under 0.3 of the width), so rods are not "circles".
- A part too thick for a 3 in screw (a 3 in trestle post) is counterbored 3/8 in (10 mm) so 1-1/2 in of wood is left to pass, and plugged; brackets only when even that fails. 1-1/2 in is ordinary framing stock thickness.
- Trestles: a post on a foot and under a top rail is one end frame, built flat on the bench, then joined by the stretcher.
- A hanging rod sits in closet rod sockets screwed to the sides (lift-out), placed by its centre below the shelf above.
- All back panels go on together in one late step, after shelves and top, unless a later part goes in the same way through where the back would be.

## v0.94
- Ends sawn at an angle are told apart: parallel (a splayed leg), mirrored across the width (MITRE, a picture frame) or across the thickness (BEVEL, a mitred box side). Mirrored is read from the slope of each end face seen from its own end. No paper template for any of them: the saw is set instead. Sticks only 1.8 times as long as they are wide now count, so a box side qualifies.
- Mitred corners are found where two pieces with mirrored ends of the same kind overlap at a corner. Box corners: tape-and-fold glue-up, then brads or splines. Flat frames: band clamp, then brads or a V-nail. Brads about twice the thickness, as they cross the mitre on a diagonal.
- A case standing on the top of the piece below, with nothing passing through that top (a desk hutch), is built whole on the bench ("the hutch"), its back panel last, then set on from above and screwed up through the desk top from underneath.
- A top counts as open (no screws through its face) while less than half of it is covered by what stands on it.
- A board laid on top of what is already built, with nothing beside it at its level, goes on from above.

## v0.95
- A rail flush between two stiles is part of the face frame even with an opening behind it (the rail between a drawer and the doors).
- Doors set in a face frame opening are inset doors: they hang on inset face-frame hinges (cup hinges with face-frame plates, or butt hinges), shimmed with 1/16 in (2 mm) card for even gaps.
- Drawer slides behind a face frame whose stiles stand proud of the inside of the sides get a spacer strip as thick as the overhang, so the slide clears the frame.
- Narrow boards lying flat between two sides, level with their tops, are stretchers (a base cabinet's front and back strips under the worktop).

## v0.96
- Beds come apart: in a piece with slats and a side rail over 4 ft long, rails that meet a leg, post or side rail end-on hang on bed rail fasteners (hooked plate on the rail's end, slotted plate let into the other), no glue; a rail so hooked is not also screwed to the leg in that corner. A centre rail hooks onto the end rails the same way.
- Parts set at an angle (leaned-back slats) are checked for face contact in their own frame, so they get fixings instead of "then fasten".
- Slats are screwed down into what they rest on, never bracketed from below.
- Counterbores stop at 2 in deep; deeper than that the joint falls back to brackets.

## v0.97
- The assembly drawing gets a note giving each item set off upright (more than 1°, up to 45°) and which way: leaning back or forward, left or right, or splayed when it leans both ways. The views show the angle but do not dimension it. Read from the part's own box axes, rounded to 1/2°.
- Boards leaned back more than 3° above the lower third of the piece are a seat's back: long level ones are back slats, upright ones back supports.

## v0.98
- Parts whose boxes overlap but which meet face to face (a tread on a notched stringer's step) are found by matching opposite flat faces in the same plane, so they get fixings instead of "then fasten".
- A notched side carrying flat boards at two or more heights is a stringer; the boards are treads. Treads are screwed down (people stand on them), never bracketed from below, and go on from above.

## v0.99
- A small block (under 120 mm, no thicker than 16 mm) sunk wholly in one part and butting the end of a rail at least twice its length is a tenon. It is the rail's: glued into a mortise in the rail's end on the bench (or cut on a longer rail), then glued into the mortise in the leg; no screws, brackets or overlap problem. The overlap is housed automatically, and the leg's drawing and making steps give each mortise's size, which face, and how far from the end; mortises are drilled out and pared.
- Grooves cut the same but measured from different edges (a leg's two mortises) are listed apart.
- When planning what blocks a part's way in, a notched part (under 85% of its box) counts only where it has material ahead of that part: a tread drops onto a stringer's step from above.
- Lean angles and back slat names are only read from boards (at least 90% of their oriented box): a rod's box can turn any way about it.

## v1.0
- Version numbers go on from 0.99 to 1.0.0 (version code 10000, above 0.99's 9900); nothing else is meant by it.
- Check tab: a finding's action buttons wrap onto a second line instead of running off the card, and the severity filters wrap too ("Checks" for short).

## v1.1
- A piece that lies flat on the bench as a whole (under 100 mm high, every part on the floor and lying flat: a picture frame) is laid out and glued up in one step.
- A rail built on the bench with only its tenons glued in is called by the rail's name ("glue the tenons into 2 aprons", "the 2 aprons from step 2").

## v1.2
- Sliding doors: thin panels (over 150 mm each way) where one runs just behind another (under 6 mm apart), overlapping it by more than 5 mm but less than half its width over most of its height, each with something above and below. They are hung last, nothing is screwed to them, and they run in a double track set as long as the opening, with flush pulls so they pass each other.
- Two panels at least 300 mm wide meeting edge to edge over 300 mm or more (an L desk's tops) are pulled together with worktop connector bolts from underneath, one per 300 mm, at least two, with biscuits or dowels to keep the faces level. Narrower edge joints stay glued.
- A 3/4 in panel standing at the back but less than half the piece's width (an L desk's return end) is a side, not a back.

## v1.3
- A board rail (no thicker than 1 in) meeting a leg too thick to screw through end-on is pocket-screwed from its hidden face into the leg instead of counterbored: a long screw through a leg into a rail's end grain holds poorly. Counterbores stay for thick-to-thick joints (a trestle's post on its foot).
- Brads: the shortest sold is taken when it is within 10% of the ideal length, so a 6 mm back panel gets 5/8 in (15 mm) brads instead of "glue only".
- Parts that touch without sharing a face or overlapping more than 3.5 mm (a square-cut end against a sloping face) are screwed through the part met end-on.

## v1.4
- What blocks a part's way in, for a leaning or notched part, is now its material clipped to the space ahead of the moving part (each triangle cut at that plane), not whole triangles: a leaning back support's long faces reach both sides, so a back slat in front of it goes in from the front, not through it from behind.
- Test models moved to `scripts/models/` (gen.py writes the first set; gen2-9 add the rest), and `scripts/guide-diff.sh` compares every model's guide text between a commit and the working tree, to catch unintended changes.

## v1.5
- Cabinets in a row are drawn in the exploded views pulled apart along the row, evenly out from the middle (by 0.3 of a cabinet's width per place), so each reads whole; before, the peel order scattered them front and back under the worktop.

## v1.6
- In the exploded views, parts of one item pulled the same way move as one group even when they are fitted over several steps (a bookcase's shelves), and alike drawer boxes move as one group: pulled out the same distance, side by side, instead of each clearing the last and spilling down the page.
