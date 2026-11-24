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

## v1.7
- 3D view: the Front / Top / Right / 3D buttons sit in one row along the top of the view instead of down its right edge, where they covered the end of a wide model on a phone.
- Build steps in 3D: the caption is the step's own title over up to two lines; the "Made on the bench" prefix went, as bench steps' titles already say so.

## v1.8
- A newly imported STL is tried three ways up (as saved, Y up, Y down) and stood up the way that makes the most sense: parts counted with roles that only arise the right way up (kick boards, legs, slats, treads, feet, drawer fronts, aprons: 2 each, up to 12 parts), weaker ones (posts, stretchers, back panels: 1 each, up to 6), drawers found (3 each) and doors (2 each), one each for a top and a bottom; unnamed panels count against (up to 3), and a whole face lying on the floor (over 85% of the footprint) costs 2. Another way must win by 3 points; Y down must also beat Y up by 3. On 24 test models and 10 templates saved Z up none was turned; saved Y up, 30 of 34 were stood up and the rest left as they were (an open box, a flat frame, a desk, a three-part model). OBJ files keep their Y-up rule. The project's notes say when the app turned it, and picking a way by hand clears that.

## v1.9
- Sub-assemblies with no name of their own (not a drawer, frame, cabinet, face frame, hutch or tenoned rail) are lettered A, B, C… in the order they are made, as a maker's manual does: "make sub-assembly B from items …", one built over several steps "(1 of 2)", and later "sub-assembly B from step 4". Before, they read "join items 3, 4, 7, 8, 15 and 16" and "the sub-assembly from step 4", which gave no handle to tell them apart.

## v1.10
- Stringers and treads need the boards to climb: each higher one stands further back (or forward) than the one below by at least a fifth of the stringer's depth. A desk's notched end panels with a top and shelves stacked over each other had been called stringers and treads since v0.98 (the desk's top was a "tread").

## v1.11
- The making steps give every groove, rabbet and mortise size and position with its unit ("1/4 in deep … at 3 in, 15-3/4 in and 29-1/2 in from the bottom end"); in millimetres a bare "25.4 deep" read as nothing in particular.
- Sliding-door track is listed as "at least" the opening's length, in whole millimetres in metric.
- Cutting plan viewer: the first page is labelled "What to buy" (it was cut off as "Summary and what to b…").

## v1.12
- A plain block with blind pockets (a leg's mortises: each opening on one face only, one per face) is drawn with them cut out, built as an exact mesh (the face round each opening as four trapezoids, so no T-junctions), so its views show the mortises, hidden where out of sight, and the dimensions place them.

## v1.13
- "Before you build" names grooves, rabbets and mortises together ("cut the grooves, rabbets and mortises that item 2 needs"); it said "grooves" for a leg that only has mortises.
- A part's second mortise of the same size is told in one line ("cut the other mortise … the same size, at …"), the method having just been given.

## v1.14
- Cutting plans number the cuts on each sheet in the order to make them, as a panel-saw cutting diagram does: each cut runs right across the piece of sheet being cut; a rip along the sheet's length comes first wherever one frees whole strips (as a store cuts sheets), otherwise a crosscut; the last piece in a region is freed across its far end, then along its side. The number sits in a small box at the start of each cut.
- Help: "Which way is up" now explains that the app stands up a model that plainly lies on its back.

## v1.15
- A bed's side or end rail that carries no slats, with one on the same side below that does, is a guard rail (a top bunk's). It hooks on with bed rail fasteners like the side rails, so the bunk still comes apart.
- Two parts of equal thickness screwed face to face (a cleat on a bed rail) are screwed through the one whose far face is inside the piece, so no screw heads show outside. Thinner-through still decides when they differ.
- A deck's slats are laid in one step after the cleats they rest on (up to 30), not spread over several steps of three to five; spacing them is one job with one block.
- A part is never fitted "from below" when the way from above is just as clear.

## v1.16
- A ladder is found by shape: two slim bars (under 90 mm across) standing side by side 250-650 mm apart, with three or more level bars between them spread over at least 0.4 of their height. They are "Ladder side" and "Rung", not legs and rails.
- The ladder is one sub-assembly, made flat on the bench in one step however many rungs it has, and fitted in the last step of the main build, sideways from the side it is fixed to (it is screwed to the side rail, so it waits for it).
- A strip lying along the face of a leg with a board resting on it (a desk top under a loft bed) is a cleat.
- A rail standing on edge above a bed's side rail, in the same plane and carrying no slats, is a guard rail even when it stops short of a leg (at a ladder opening). One hooked on at one end only reads "one pair where it meets it".
- Two parts of equal thickness screwed face to face: through the one on the inner side, towards the middle of the piece (replaces the "far face on the outside" test of v1.15, which a ladder outside the rail defeated).

## v1.17
- A flat triangle (fills 40-60% of its box) standing upright with a board resting on it is a corbel: the bracket under an island's seating overhang.
- An upright panel from near the floor most of the way up, flush with the back edges of the sides it stands between, is the back, even when a top or corbels reach further back than it.
- A flat strip across the top of a case is a stretcher when two sides end level with it; a divider ending under it no longer stops that.
- The main build of a case starts from a side, never from its back or kick board, however big: the back goes on a case already standing.
- "×N" tags in step pictures count parts of one item and one name: the same board used as kick board and stretcher is two things in the picture, not "×2".

## v1.18
- Upright narrow parts standing between the same two level rails, three or more of one length, are spindles (a crib's slats, a railing's balusters).
- Spindles go with their rails: into a leg frame when the rails are in one, else the two rails and their spindles are a "slatted panel" made on the bench.
- Any sub-assembly lying in one plane (its thinnest overall size no more than its thickest part's thickness) is made in one bench step however many parts it has: the whole of it is in view in one picture.
- Bench steps with spindles give the gap and a spacer block, with the end gaps when both ends match, instead of each spindle's place along the rail.

## v1.19
- On the bench, spindles are drawn lifted straight up off their bottom rail, with the top rail above them, so a slatted panel reads as it goes together.
- When some parts of one item are laid down and the rest joined on (a slatted panel's two rails, a frame's two legs), the text says so: "Lay down item 2", then "Join ... item 2 from above" after the parts that go between them, as the picture shows.
- "×N" tags count only parts going in the same way; a quantity tag that has no clear room beside its group tries further out, then the least-covered spot.

## v1.20
- A flat board 380-520 mm off the floor (15 to 20-1/2 in), with a part rising more than 250 mm above it from its back edge, is a seat. Rails above a seat are back rails.
- A ladder's rungs must be a climbable step apart (150-460 mm) and evenly so (the largest rise no more than 1.5 times the smallest): a chair's back, with one rail under the seat and two up the back, is not a ladder.

## v1.21
- On a bench with spindles, a rail resting on top of them is said after them, even when another part of its item (an apron, a bottom rail) goes on first; and before the second leg of a frame.

## v1.22
- A corbel must be solid (1 in or more thick) and bracket-sized (under 700 mm): a thin shaped panel in a real-world desk model was being called one.
- Projects saved less than a minute ago read "Just now", not "0 minutes ago".
- scripts/guide-diff.sh takes UNITS from the environment (UNITS="" lets the file's units be detected), so real-world samples can be compared too.

## v1.23
- A strip on edge across the back of a case, just under its top and against a thin back (6-10 mm), is a hanging rail: the cabinet is meant to hang on a wall.
- With a hanging rail the hardware lists screws to hang it (#10 x 3 in, or 5 x 75 mm; two per stud at 16 in centres, at least four) instead of an anti-tip strap, and "When it is together" says how to hang it on a batten into the studs. The check says to hang it from studs, not plasterboard.
- A face frame is in the front half of the piece: a strip across the back of an open case is not one.

## v1.24
- A ladder's evenly pitched rungs are given as where the lowest sits and the rise between them, marked on both sides clamped together, with a spacer block for the gap, instead of each rung's height up from the bottom and down from the top.

## v1.25
- A drawer can run on wood: strips its sides rest on are runners, strips just above its sides (within 8 mm) kickers, and, only when there are runners, the strips across the front just above and below its front are drawer rails.
- A drawer on two runners gets no slides: it slides in on them, waxed, with the kickers stopping it tipping. It still gets a handle.
- Runners and kickers go on the bench with the side frame whose apron they are screwed to. Their ends butting the rails are not glued.
- A drawer box with two ends and a front glued on: both ends are "drawer box end".

## v1.26
- Three or more boards on edge on the floor, joined in a ring, low (under a quarter of the height), with the case standing on them, are a plinth. It is made on the bench and the case built on it; the case is screwed down to it through its bottom, and the case panels' bottom edges resting on it are not fixed again.
- A case's bottom resting on supports at both ends is still screwed to the case's sides, front and back (the rule that a board resting on supports is not also fixed through its neighbours is for shelves).
- An upright panel flush with the front of everything standing up is the front, though a lid or top overhangs it.
- A top over a closed box (front, back, both sides under it, no doors or drawers, nothing above) is a lid. It hangs on a piano hinge 2 in (50 mm) shorter than the back, with a lid support that holds it open at any angle; the check says why (fingers, and a child in a toy box), and to leave air holes in a toy box. No brackets fix it down.

## v1.27
- A board standing on the floor whose length leans 5-35 degrees from upright, more than three times as long as it is wide and longer than half the piece's height, is a leg, though its tilted box is wide (a picnic table's 2x6 legs, a sawhorse's).
- A crosspiece called a cleat that joins two legs counts as a frame rail: a picnic table's ends (two legs, top cleat, seat support) are made on the bench as frames.
- A frame's part list no longer repeats a cleat that is also one of its rails.
- Slats go on from above whenever the way up is clear, like treads.

## v1.28
- Up to 24 drawers, drawer fronts or doors are hung in one step (was 12): an eight-cabinet kitchen run's sixteen doors were split 10 and 6.
- The command-line tool takes --up y|-y|x|z|auto; auto stands the model up as the app does on import. With it, every Y-up test model gives the same parts list and step titles as its Z-up original (flat objects such as a picture frame aside, which have no clear up).

## v1.29
- A square strip under a board (a kicker under a table top) is screwed up from underneath like any flat strip: which of its equal sides counted as thinnest was down to rounding, so two alike kickers were told two different ways.

## v1.30
- A build that starts from one leg frame sets out its twin frames standing on the floor in the same step ("set out the 2 frames"), even when the order found would only bring them in after an apron fixed to the first frame alone. Found by building a mirror image of the garden bench: the same bench drawn the other way round got a worse order.
- scripts/mirror-stl.py makes the mirror image of a model, to check that a piece and its mirror get the same guide with left and right swapped. Remaining differences are conventions (which leg is laid down first on the bench) and the order of a few steps in the bunk bed and garden bench.

## v1.31
- A piece that stands the right way up but faces sideways or backwards (drawers, doors or kick board on a side) is turned to face the front on import, a quarter turn at a time, and its notes say so. Turns are kept with the way up in the project ("y>1": Y up, then one quarter turn).
- What faces the front: drawer fronts and doors within 1 in (25 mm) of the front of the carcass (not of an overhanging worktop) with nothing in front of them (so a drawer box's end behind a back panel is not one), a kick board, stiles, sliding doors and drawer rails there, and a back behind. A turn is made only when one way beats the others clearly (by 3 points) and no other way ties with it.
- Which way is up now judges each axis also at its best facing, but only the part of the score that depends on facing: a cabinet stood up but facing sideways no longer loses to lying on its back.
- Which way is up also takes into account whether the piece would stand: if its balance point falls outside what touches the floor (a chair on its head), that way loses 8 points.
- More roles count towards a way up (plinth, lid, runners, kickers, drawer rails, hanging rail, corbels, rungs and ladder sides, back rails, guard rails), up to 24 parts; aprons count four at most (a desk on its head turns every drawer side into one). Spindles and seats do not count: a cabinet on its back is full of upright strips between level boards.
- Checked on every test model: 0 wrong turns or tips on 68 models standing as drawn (and their mirror images); 32 of 34 models saved with Y up stood up (a picture frame and a mitred box are flat, with no clear up); of 34 models turned a quarter and a half turn, the 24 with drawers, doors or a kick board turned back correctly and the rest were left alone.
- Which way is up (project menu) has Turn left and Turn right buttons.

## v1.32
- A first main step that would only put one part down ("start with the side") now also fits the next parts in the same step: "Place item 1 (side). Fit items 8 and 2 from the front." The part put down is not drawn pulled out, and the others' positions are given against it. A first step that sets out several units (frames, cabinets) keeps its own page, since it says how far apart they stand. One page less in most guides.
- Rewritten text for the first two steps of a project saved before this version no longer matches them (they are one step now) and falls back to the app's own text.

## v1.33
- In a bed (slats), a board (40 mm thick or less, over 300 mm tall) standing above the bed's rails between two legs or posts is the headboard; when there are two, the lower one is the footboard.
- Facing the front: a back counts only at the back of the carcass (within 1 in of its rear face); a modesty panel no longer counts, being set in from the back.
- Known weak case, left for now: a platform bed whose legs all stand apart (corner, middle and centre-beam legs, no leg frames) gets a poor order (centre beam and slats before the side rails). Making lone legs wait for their rail did not fix it.

## v1.34
- A bed with one deck of slats (no seat back) is built as a builder does: the frame round the outside on its legs first, then what runs inside it (a centre beam on its legs, the ledgers), then all the slats, then the headboard and guard rails. This fixes the platform bed noted in v1.33. Bunk beds (two decks, built deck by deck from the bottom) and benches with backs keep the general order.

## v1.35
- An L-shaped kitchen's second run, turned at right angles, is found as cabinets too (a second search for repeats among the parts the first run did not take), so both runs are built as cabinets on the bench and set out.
- A run of repeated cabinets starts at its first repeating part, not at whatever else stands further along that way (the other run, a corner cabinet); before, the first run's cabinets were cut short.

## v1.36
- A ladder's sides run on past its end rungs (at least 50 mm, top and bottom). Bars flush with the ends of two uprights make a frame, not a ladder: the real desk sample's O-shaped legs, once split into their boards, were being called a ladder.

## v1.37
- A model drawn turned on the floor plan (not square to the axes) is squared up on import: the angle is read from the upright faces of its parts (their normals, weighted by area, folded into one quarter turn), and the model is turned back when at least 60% of that area agrees and the angle is over 0.3 degrees. Its notes say so. Round pieces, or parts at many angles, are left alone. A turn of more than 45 degrees ends up facing sideways and is then turned to face the front as in v1.31.
- No test model drawn square is moved by this; a dresser turned 30 degrees and a kitchen base turned 60 degrees come back with the same guide as the originals.

## v1.38
- Fixed: parts of a table or workbench sometimes lost their "from the front / back / left / right" in the guide (and were drawn not pulled out). The search for repeated cabinets found the two leg frames' rails repeating, gave them a cabinet's key, and cabinets are drawn pulled along the row, which these were not. Now a "cabinet" left with fewer than three parts after the leg frames take theirs is no cabinet, and a cabinet key with no row position keeps the part's own pull.

## v1.39
- Lid hinges and worktop bolts go "about an inch from each end" (25 mm in a metric guide), not "1 in in from each end".
- A test now checks that every part fitted in every template's guide says which way it goes in.

## v1.40
- With spindles, the check gives the widest clear gap between them when it is over 2-3/8 in (60.3 mm): the US crib standard's limit for slat spacing (16 CFR 1219/1220), so a baby's head cannot pass. Over 4 in (101.6 mm) it adds the usual residential building-code limit for stair and balcony railings (the 4 in sphere rule). A CHECK, not a PROBLEM: the app cannot tell a crib from a bench back.

## v1.41
- A top bunk's guard rails are checked against the US bunk bed rule that their top stand at least 5 in (127 mm) above the top of the mattress: the check gives the thickest mattress the rails allow (their height above the slats less 5 in), and says to raise the rails when that is under 6 in (152 mm), thinner than most mattresses, or nothing at all.

## v1.42
- A board whose cross-section is the same all along, a rectangle with one long edge sloped right across its thickness, is "ONE EDGE BEVELLED AT n°" in the cut list (not "notched"), with how to rip it with the blade tilted, and how to make a French cleat pair from one board.
- Such strips at the back of a piece are a French cleat: the one fixed to the piece is the "French cleat", the one behind everything else the "Wall cleat". The wall cleat is not fixed to the piece; the last step screws it to the wall into the studs (the hanging screws on the shopping list) and hooks the piece over it.

## v1.43
- A board with a bevelled edge gets its own detail drawing (its end view shows the slope) with a note giving the angle and how to rip it, instead of being listed as a plain board; it gets no full-size template.

## v1.44
- Outdoor timbers in the material list: pressure-treated pine (600 kg/m3), cedar (380), redwood (420), teak (650). The item material chips show them.
- When any part is outdoor timber (or its material says treated, outdoor or exterior), the shopping list adds that all fasteners must be exterior-rated (coated deck screws rated for treated lumber, or stainless steel) and the glue waterproof: plain steel rusts outdoors, and the copper in treated lumber corrodes it.

## v1.45
- Frame-and-panel work: a panel 1/2 in (13 mm) thick or less whose edges sit up to 5/8 in (16 mm) inside four or more strips that are thicker than it on both faces is a panel in grooves. Two strips the whole length one way are the stiles; the rest are rails, or muntins when they run the way the stiles do. Panels sharing a strip are one frame.
- The overlap of a panel into its frame is the groove, drawn as housed: no clash finding, a groove on each strip's drawing, and a line giving the groove's width (the panel's thickness) and depth (the overlap).
- The panel is not glued when it is solid wood (it moves across its width with the seasons); a plywood or MDF panel may be. The app cannot tell which, so it says both.
- Stiles, rails and muntins are joined with pocket screws (holes in the end that meets the other), the usual hobbyist method; the guide still offers dowels.
- A frame that hangs as a door (found as one door from its outline) is made whole on the bench and hung on hinges last; its parts are never screwed to the case. The hinge cups go in one stile per door, the one on the hinge side, and the drawing says so.
- Any other panelled frame (a chest's front) is made on the bench and fitted like a board. Case parts meeting a strip's inside face are pocket-screwed from inside along their edge, so nothing shows outside; screws or brads through the strip would.
- A panelled front and back count as the front and back of a closed box for a lid; the piano hinge runs the panelled back's whole width less 2 in (50 mm).
- Door panels count as doors when guessing which way a piece faces.

## v1.46
- A strip of a frame lies flat in the panel's plane (its thinnest side is the panel's thickness) and is at most 0.6 times as wide as it is long; panels may be as small as 50 mm (2 in) each way. A carcass side holding a grooved back is on edge to it, so it is not a stile.
- A five-piece (frame-and-panel) drawer front is one front: its parts are named "Drawer front stile/rail/panel", it is made on the bench and screwed on as one from inside the drawer, with one line for it.
- A frame-and-panel door is measured whole when deciding whether it sits inset in a face frame, so both doors of a pair get the same hinges.
- The bench picture of a frame round a panel leaves the panel in place and pulls the stiles out to the sides and the rails out at the ends; muntins stay between the panels.

## v1.47
- A raised panel is recognised from its shape: flat back, a tongue of even thickness all round its outline, and a flat field set in the same distance on all four sides. The cut list calls it "PANEL t X w X l, RAISED (EDGE e THK, BEVEL b WIDE)"; it gets a detail drawing and the steps to raise it (panel-raising bit in a router table, or a tilted table-saw blade; end grain first).
- For frame-and-panel detection and for the groove sizes, a raised panel is measured by its tongue, not its full thickness; a panel up to 32 mm (1-1/4 in) thick may be raised.
- The drawing note says to leave about 1/16 in (2 mm) spare in each groove across the grain for a solid-wood panel, the usual allowance; the app does not size it into the cut list.
- A long description in a drawing's list of material is lettered smaller (down to 60%) to keep the list inside the border; a long item heading in the guide is set smaller, then broken after a comma.

## v1.48
- Square posts round a panel (a bed's headboard) are stiles for finding the frame, but stay posts or legs: named so, joined as posts (bed rail fasteners for the side rails), never pocket-screwed from inside like a board stile. A strip over 32 mm (1-1/4 in) thick counts as a post.
- In a bed, a panelled frame on posts is made on the bench as the headboard (the taller) or the footboard; the main build sets out the headboard and footboard.
- When one of two like parts is flush with an edge and the other set in from the opposite one (a base cabinet's front and back stretchers), the guide says which is which.

## v1.49
- Bed slats spanning more than 40 in (1016 mm) side to side with nothing under the middle get a specific check: a centre rail running head to foot under them, with at least one leg to the floor, as full-size and larger beds need and most mattress warranties ask. No thicker-slat fix is offered for this.
- When guessing which way a piece faces finds nothing to go by (no doors, drawers, back or kick board), a piece standing up (not lying flat) that is drawn more than 1.2 times as deep as it is wide is turned a quarter so its long side faces the front, as a chest, table or bench is usually drawn. Not for beds (slats, side rails, headboard, guard rails), which run head to foot away from the viewer.

## v1.50
- A tapered leg is found from its cross-sections: rectangles that stay the same from one end for a way, then shrink in straight lines to the other end. It is a board, "TAPERED ON <faces> TO <foot size>, STARTING <distance> FROM THE TOP", with a detail drawing, the steps to make it (joinery first while square, then a taper jig on the table saw or the band saw and a plane), and its own tool need ("tapered legs": table saw, band saw or CNC; slowly with a hand saw, jigsaw or circular saw).
- A raised panel likewise needs "raised panels": a router (table), table saw or CNC.

## v1.51
- A thin panel that covers a whole face of the piece, standing on the floor and reaching its top, is that face (a display plinth's side, a crate's lid), never an overlay door: a door clears what is round it.
- A narrow strip at the front lying against the face of a panel (not the front edges of a case) is a batten, not a face frame stile.
- The anti-tip check, for a piece with no doors or drawers, adds the other answer for something that must stand away from a wall: weight its base (a low shelf with sandbags or bricks).

## v1.52
- Contact between two simple parts (64 triangles or fewer) is also looked for all along the edges of their faces, every ~10 mm, half a millimetre in: a board can meet a sloping face along a line only (a ledge across leaning legs). Before, such parts were reported as floating.
- Leaning parts meeting edge to edge (a picnic table's legs at their tops) get no edge joint: their boxes say nothing about one.
- Easel / A-frame: with three or more leaning legs, one touching the rest only in its top fifth is a back leg. It is hinged on last with a pair of butt hinges (2 in / 50 mm) to the block or rail at the top (not to a leg when there is one), plus a light chain or cord to the front legs so they cannot spread under load. A wide board (3 in / 50 mm or more) across the front of two leaning legs, between a quarter and six tenths of the height, is the ledge.

## v1.53
- Doors and their panels, stiles and rails, drawer fronts (five-piece too), sliding doors, lids, headboards and footboards have their grain along their length by default in the cutting plan: a door cut with the grain across looks wrong. The item's grain tick still overrides it either way; a project saved before keeps what it stored.
- This can cost more stock: on the Shaker test cabinets the 1/4 in panels moved from a half sheet to a full one. The user can untick grain to save it.

## v1.54
- Boards on edge stacked into a wall, each across the same legs (a planter's sides): side boards along the long way, end boards across. They are joined to the legs, never edge-glued to each other; the legs and end boards make the end frames.
- A top piece 4 in (101.6 mm) wide or less (a cap rail) is screwed down, not held with brackets: it barely moves.
- A cleat beside the board it carries is screwed through from the cleat's side, so the heads stay inside.
- When one of two like parts is flush with an edge, that is said even when it rests on the other.

## v1.55
- A rod with one end sunk into a board by less than half its length, and wholly inside it the other two ways, is a peg glued into a drilled hole: housed as drawn (no clash), named "Peg", fitted along its own length out of the board, all alike in one step. The board's drawing lists the holes (diameter, depth, centres); the guide says to drill them square (or at the drawn angle) with a guide block and glue the pegs in.
- A piece whose only part reaching its lowest point is a full-width board at the back hangs on a wall: small upright boards under a shelf against it are brackets, the board the back board, what lies on the brackets a shelf (screwed down into them, not bracketed). Instead of "It would tip over" it gets "Hang it on the wall": into the studs with two #10 x 3 in (5 x 75 mm) screws at each, or masonry plugs; plasterboard anchors only for light loads.
- Flat parts at one height are spaced evenly with a spacer block even when other parts of the same item stand on edge (a planter's slats cut like its end boards).

## v1.56
- Between two stringers, an upright board under the front edge of a tread (its centre within the tread's front quarter) is a riser; one under the back edge stays a back.
- Nothing is placed by height against a stringer or other shaped part: its box has no plain top or bottom edge there (a tread sits in its notch). Leaning legs, whose level-cut ends are their top and bottom, still are.

## v1.57
- The holes for pegs are drawn on the board's detail drawing (front view, with the usual hole callout and ordinate dimensions), mapped through the same squaring-up as the board itself.

## v1.58
- A piece that touches the floor only along a strip at its back (no deeper than 40 mm or a quarter of its depth) is a wall piece: "Hang it on the wall" instead of "It would tip over".
- On a wall piece, the full-width back board is always the back board; flat boards against it or on brackets are shelves; an upright strip along a shelf's front edge standing above it is a lip (a picture ledge's).

## v1.59
- The newer hardware and notes follow the project's units instead of giving both: the easel's chain or cord ("about 24 in long" / "about 600 mm long"), the wall screws (#10 x 3 in / 5 x 75 mm), and the raised panel's spare room in the groove (1/16 in / 2 mm).

## v1.60
- Which way is up: every way up is now credited with the front it would have once turned to face the viewer, and judged facing that way; before, one already facing the viewer could lose to its upside-down twin that needed a turn (Shaker cabinets exported Y-up came out upside down).
- The strips of a frame round a panel, and pegs, are left out of the roles that vote for a way up: they come out the same whichever way the piece lies.
- A piece no deeper than 14 in (355.6 mm) that meets the floor only along a strip at its back, under a full-width back board, is made to hang: it does not count as falling over when the way up is guessed, and its back board, brackets and lip count for that way up. The same depth limit keeps a chest lying on its back from being taken for a wall rack.

## v1.61
- Way up: each way up is judged as it would stand once turned to face the viewer, without adding its front score itself (a picnic table on its side, its top boards reading as a face frame, beat the table on its legs). Standing on three or more small feet (each meeting the floor over under 3% of the footprint) earns 4 points.
- A wall piece's strip of floor contact may be along any side, since it may not face the viewer yet.
- Which way it faces: a back board at the back counts 3 points; stairs that climb away (lowest tread in front) 3; an easel's back leg standing behind its front legs 4. A panelled frame's stiles no longer count as a face frame at the front; a five-piece drawer front's panel counts as a face. Easels are left out of the long-side rule.

## v1.62
- A shape drawn as one solid may be taken apart into up to 30 boards (was 12): a real TV unit exported from OpenSCAD as one body needed 17. The volume check still rejects anything the boards would not match.
- The boards a box was taken apart into are each their own part in the build; the layers of a glue-up, and the members of a flat frame, still stay one unit made on the bench.
- Tested on real OpenSCAD exports of a living room (TV unit, shelving, tables) from a public repository, scaled to full size.

## v1.63
- Members split from one drawn part stay one bench unit as a flat frame only when the frame is 100 mm (4 in) thick or less: a whole bookcase taken apart (all its boards the full depth) is built board by board.

## v1.64
- An apron or rail (a board 1 in / 25.5 mm thick or less) meeting a leg or post (32 mm / 1-1/4 in thick or more) end-on is pocket-screwed from its inside face whatever the leg's size, as was already done for legs too thick to screw through: a screw driven through the leg into end grain holds little, and its head shows on the leg. The guide still offers glue and dowels.

## v1.65
- Which way it faces: shelves or a hutch standing on a desk or counter top, flush with one long edge of it and leaving at least 30% of its depth clear, are against its back: 4 points for that way.

## v1.66
- Which way it faces: a seat's back (back rails, slats or supports) standing behind the middle of the piece by more than 15% of its depth is 4 points for that way; back rails and slats at the very back count as back evidence too.

## v1.67
- Which way it faces: a thin back panel at the back counts one point more than other back evidence: it is a sure sign of the back.

## v1.68
- New-design templates for showing work: a display plinth (four sides round a flush top on cleats, a low ballast shelf on cleats, open bottom), a picture ledge (back board, shelf on its face, front lip) and an art crate (a frame of boards on edge round the work, plywood on both faces, battens across the lid). Sizes default to common gallery ones: a 16 x 16 x 42 in plinth, a 48 in ledge with a 3-1/2 in shelf, a crate for a 40 x 32 in frame.
- A crate (battens across a lid) gets no anti-tip check: it is packing, not furniture.

## v1.69
- A panel in a frame no thicker than 5 mm (3/16 in) at its edge is taken for glass or acrylic: no shop sells plywood that thin. It is named "Glass"; the door is glued up empty, a rabbet is routed round the opening at the back after glue-up (its corners squared with a chisel), and the glass goes in last, after finishing, held by glazing points or pinned stops, no glue. The strips' drawings show the rabbet out to the back face.
- Glass is left out of the cutting plan and listed to buy from a glass shop, 1/16 in (2 mm) under the drawn size each way with smoothed edges; it gets no thickness or tool checks.

## v1.70
- The Cabinet template has a "Glass doors" option: each door becomes a frame of stiles and rails 50 mm (about 2 in) wide round a 4 mm pane in a rabbet at its back, for a display cabinet. Doors under 160 mm either way stay plain.

## v1.71
- An "Easel" template: front legs leaning in and back with their ends cut level, a top block on them, a back leg hinged to the block, a ledge and a brace across the front. Default 72 in tall, legs 26 in apart at the floor, leaning back 15 in, the back leg's foot 30 in behind, the ledge at 30 in.
- On an easel the block the back leg hangs from is the top block; other boards across both front legs are braces.

## v1.72
- A square-ish strip along the inside face of a standing panel with a board resting on it is a cleat (a plinth's shelf and top cleats). Rails on edge (hanging rails, stretchers) are left alone.
- Cleats like that, on one panel, are screwed on with the panel flat on the bench before the case is closed: once the case is round them there is no room to drive the screws.
- The command-line tool writes UTF-8, so ×, ° and ⌀ show in its debug output.

## v1.73
- Squaring up a model drawn at an angle now also checks the outline: if turning it would make its plan's bounding box more than 3% bigger, it is already square (an oval table on legs set diamond-wise) and is left as drawn.
- Taking a solid apart into boards judges each cell by five points, not one, so a small hole through the middle of a cell (a bolt hole in a shelf) does not empty it.

## v1.74
- A "Canvas rack" template: a box open at the front and top (two ends, back, bottom), split into equal slots by plywood dividers standing on the bottom and against the back, with a rail across the front top resting on the dividers. Default 48 x 20 x 36 in, 6 slots, 1/2 in dividers, 3 in rail.

## v1.75
- Placing lines name the end a part is measured from ("from the left end", "from the front end"). "From each end" is kept only when copies sit at the same distances from both ends; when the two ends differ, one line gives each end its own distances. Distances are listed smallest first.

## v1.76
- A "Canvas stretcher" template, drawn lying face down as it is made: four bars mitred at the corners, a brace across the middle once a side passes 30 in (the usual stretcher-maker advice), a cross of braces when both do. Fixed corners (a strainer): expansion keys are not modelled, and the blurb says so.
- In a flat frame of one layer, a straight member inside it touching none of its outer edges is a brace.
- A screw's gauge is also capped by the part it bites into, by the same thickness rule: a #10 into a 3/4 in part splits it, so it becomes a #8.

## v1.77
- A flat frame's braces (all parts one layer, a stretcher's) are fitted in a step of their own after the frame is glued up, and placed ("Centre the brace on the width of the rail"). An easel's brace is not affected.
- A part is "held on both sides" (so needs no placing words) only by neighbours that do not run into it end-on along that axis: short braces butting a cross brace do not place it, it places them.

## v1.78
- A part bigger than a sheet gets concrete ways out: longer sheets (1220 x 3048 mm, 4 x 10 ft) when only the length is over and it would fit one, with where to set the sheet size; a thin back in two pieces meeting behind a divider or shelf; a thick panel glued up from solid boards.

## v1.79
- A panel with its cleats made on the bench is a "cleated panel" in the guide ("make 2 cleated panels", "set out the 2 cleated panels"), and its bench step does not ask for equal diagonals: a panel with strips on its face has no corners to square.

## v1.80
- Casters: three or more alike short round parts (6 in tall at most) standing on the floor under the piece. They are bought, not made: the list of material says "(BUY)" with the model's height, the cutting plan leaves them out with a buy line, no detail or full-size drawing, and they are not in a step's parts list (the hardware line has them).
- Casters go on last, in a step of their own, the piece upside down; the case is started from its own lowest parts, not set up on its casters. Each caster's plate is screwed to the board lying over it (not the edge of a side it also touches), centred about 2 in (50 mm) from the edges so the wheel swivels clear. The plate's screws come with it; the guide does not size them.
- A screw for a caster plate, brake advice ("two with brakes") and a load rating are advice, not geometry; nothing about the caster's mechanism is read from the model.

## v1.81
- A "Taboret" template: a carcass on four casters, drawers on slides over an open shelf, a top with a gallery rail round the back and sides. Default 20 x 18 x 32 in overall, 3 drawers, an 8 in shelf opening, 3 in casters, a 2 in gallery. The casters are drawn as round blocks their overall size, a little in from each corner.
- With casters, the case is planned without them and they are added as the last step, so nothing is ever set out on its casters.

## v1.82
- A "Picture frame" template, drawn face down as it is made: four face bars mitred at the corners and four narrower mitred back strips glued behind them, set flush with the outside, so the opening steps out into a rabbet without a router. Sized from the art: 1/8 in (3 mm) of play in all, a lip over the art (3/8 in default). Default an 11 x 14 in print, 2 in x 3/4 in face bars, 1/2 in strips.
- A frame drawn as two rings of four flat members with one outline: the narrower ring's members are back strips.
- With back strips, a note says what is not in the model: glass or acrylic and a backing board cut to the rabbet less 1/16 in (2 mm), framer's points, two D-rings and picture wire about a third of the way down. Standard framing practice, given as such.

## v1.83
- The new-design picker shows its designs in two labelled groups, "Art and studio" first (plinth, easel, stretcher, picture frame, canvas rack, taboret, art crate, picture ledge), then "Furniture", with "One board" at the end. The app's own user works in a studio; the grouping lives in core (Templates.groups) so the order is one list.

## v1.84
- A "Flat file" template: a low carcass on a plinth set back 2 in (50 mm) at the front, wide shallow drawers on slides (each slot at least 40 mm), fronts over the sides, the bottom one down over the bottom's edge and the top one up over the top's. Default 40 x 28 x 18 in, 5 drawers, a 3 in plinth. Listed with the studio designs.

## v1.85
- The plinth template can carry a clear acrylic cover (a vitrine): five sheets, the outside flush with the plinth, 12 in tall and 3/16 in thick by default.
- Any model with a clear cover on top (five sheets 1/4 in or thinner, at least 2 in across, four walls standing on the piece's top and a lid on them, inside its outline) has the cover named "Acrylic cover" and left out of the wood lists and drawings, before the rest is named, so the plinth under it is read as without it. A note gives the size to order (outside size and thickness), made up by a plastics fabricator, glued, with polished edges; it rests on the top and lifts off. The user's own exclusion choice does not bring it back in this version.

## v1.86
- A closed pedestal with a low shelf inside (a flat board in the bottom quarter, clear of the floor, walled in on four sides by parts standing on the floor, with a top over it) is a free-standing display piece: its tip check is "Weigh it down", naming the shelf to load with sandbags or bricks, and its hardware has no wall strap. A wall strap is still mentioned for one that stands against a wall.

## v1.87
- Things made to order elsewhere (so far a clear cover) are carried as a list on the analysis and shown under "To order" in the shopping list and its checklist, with the outside size and thickness.

## v1.88
- A "Cradled panel" template (a painting panel): a thin plywood face with a cradle of strips on edge round its back, flush with its edges, and braces across so no bay is over 24 in (610 mm). Drawn face down. Default 24 x 30 in, a 1/4 in face, a 1-1/2 in deep cradle of 3/4 in strips.
- A thin face (1/2 in or less) on the bench with strips on edge on its back, flush with its edges: the face is the panel face, strips at the edge the cradle, any inside braces. The cradle is screwed up first on the bench, then glued to the face, which is weighed down: nothing is driven through the face.
- The main build's first step after the bench steps, when it only puts one part down, takes the next step's parts too (as without bench steps). A joint line said word for word twice in a step is said once.

## v1.89
- Things to order elsewhere are also listed on the build guide's cover, under the hardware, as "To order: …".

## v1.90
- A "Wall plinth" template: a shelf-box that hangs on the wall to show a small work, open at the back, hung on a French cleat (the piece's half under the top at the back, the wall's half behind it, both 3 in by default). Default 16 x 12 x 10 in. The top is fixed with corner brackets from underneath, so no screws show on the display surface.

## v1.91
- The new-design picker shows one group at a time, under two tabs ("Art and studio", "Furniture"), so the preview and sizes stay near the top of the dialog instead of below twenty-odd chips.

## v1.92
- A cradled panel drawn face up (the cradle under the face) is recognised as well as one drawn face down.
- The main build's lone first step folds into the next also when what it puts down is one made sub-assembly ("start with the cradle from step 1 and the panel face").

## v1.93
- A part no other rule names and with no side over 3 in (76 mm) is a "Block" (a spacer, a glue block), so the guide and lists call it something instead of a bare item number.

## v1.94
- A crate: the panel with two or more battens across it, with a matching panel at the back, is the "Crate lid". The case is built first (back, frame), and the lid with its battens goes on in a last step, screwed only (no glue) once the work is packed in foam, marked so it goes back the same way round. Crates never get a wall strap in their hardware.
- Parts held back to a last step of their own (a crate's lid, casters) share one mechanism: the rest is planned without them, then each group is added as a final step.

## v1.95
- A crate gets a note of what it takes: its inside size, and the largest work that fits once lined with 2 in (50 mm) of foam all round (a usual allowance for shipping art, stated as such). Too shallow to line, it gets a check instead.
- The art crate template's default inside depth is 7 in (180 mm), so with foam it takes a work 3 in deep, the depth of a framed painting.

## v1.96
- A picture frame's glass note also gives the rabbet's depth (the back strips' thickness), which the glass, mat, art and backing must fit in together.

## v1.97
- A "Shadow box" template, drawn face down: a box of four sides, a mitred face frame glued on its front with a lip over the opening, four 1/4 in square glass stops inside 1/8 in back from the lip (room for 1/8 in glass), and a back panel. Default 12 x 16 x 3 in inside.
- Recognised by its layers: four flat face bars on the bench, four sides standing on them up to a thin back, and four thin strips between. The box is made first; the face frame is glued on (nothing through the face); the glass and stops go in next, the stops pinned without glue so the glass can be changed; the back is screwed on last without glue, the work mounted on it first. A note gives the glass size (the box's inside less 1/16 in, 2 mm).

## v1.98
- A clear cover the app leaves out can be brought back: including any of its sheets in the part's dialog keeps the whole cover in the lists. Stored as a "+id" mark in the project's excluded set, so old projects read the same.

## v1.99
- Help lists every design by group, and has a topic, "Making for showing art", on what the studio designs add beyond the wood (ballast, covers to order, glass sizes, crate capacity and lid, cradle gluing, casters).

## v2.0
- Version 2.0 follows 1.99 (the version code is major·10000 + minor·100 + patch, so 1.100 would equal 2.0).
- Picture frames and shadow boxes list their hanging hardware: two D-rings with their screws, picture wire about 1-1/2 times the longer side, and for frames framer's points (8, plus 2 for each foot of the longer side). A shadow box's back is fixed with two screws into each side, sized to go through the back and an inch into the side, counted for the whole back.

## v2.1
- A cradled panel lists D-rings and picture wire too, with a note to put the rings inside the cradle's sides about a third of the way down.

## v2.2
- A flat frame of mitred bars in one layer (with any braces) gets a note, hedged "if it is a stretcher", of the canvas to cut: the outside size plus, on every side, its depth and 2 in (50 mm) to wrap round the back and grip while stretching. A frame with back strips (a picture frame) does not get it.

## v2.3
- The build guide's "When it is together" page carries the check's finishing notes: glass and backing sizes, hanging, the canvas to cut, what a crate takes, and weighing a plinth down. They are what you do or get once the piece is made, so they belong in the printed guide as well as on the Check tab.

## v2.4
- In the new-design dialog a yes/no option toggles when its words are tapped, not only its small box.
- A device test makes a display plinth with its clear cover through the dialog, as a person would, and checks the cover is left out and ordered.

## v2.5
- Hanging notes (frames, shadow boxes, cradled panels) add where galleries hang work: its centre about 57 in (1.45 m) from the floor, the usual museum rule, stated as usual practice.

## v2.6
- Stretchers, cradled panels, picture frames and shadow boxes offer common sizes in the new-design dialog, one tap each: standard US canvas and print sizes in inches (8 x 10 to 36 x 48 in), or common metric canvas sizes in centimetres (20 x 30 to 80 x 120 cm). Each sets the design's width and height (outside for a stretcher or panel, the art for a frame, the inside for a shadow box). Every size builds (tested).

## v2.7
- The display plinth offers common gallery sizes too: 12 x 12 x 42, 16 x 16 x 42, 18 x 18 x 36 and 24 x 24 x 30 in (30 x 30 x 105 to 60 x 60 x 75 cm). These are typical, not a standard.
- A device test makes a stretcher at a common size through the dialog.

## v2.8
- The new-design dialog reads top to bottom as people use it: picture, description, common sizes, the sizes, then the optional material.

## v2.9
- A picture frame's glass and backing board, and a shadow box's glass, are on the "To order" list (shopping list and the guide's cover), cut to the sizes the notes give.
- On the guide's cover, a size that would run into the hardware heading goes under its label.

## v2.10
- The new-design dialog keeps the picture's room while the picture is drawn, so the chips and fields below do not jump when it arrives (a tap could land on the wrong thing; v2.8's device run failed on exactly that, and v2.9's passed).

## v2.11
- When the sizes typed leave some part no room, the dialog says so in the picture's place instead of adding a line above the description.

## v2.12
- A closed plinth with a ballast shelf has its top screwed down through its face into the cleats, a few countersunk screws, after the ballast is loaded: once the shelf is in, nothing can reach the cleats from inside, and the top has to come off again to empty the ballast when the plinth moves. The heads are filled only if it never will.

## v2.13
- A crate's note adds the usual markings ("This way up" with arrows, "Fragile") and, for one longer than 30 in, a handle at each end so two people can carry it. Shipping practice, given as advice.

## v2.14
- Any tall closed pedestal (a top over four walls standing on the floor, over 24 in tall, so its underside is out of reach from the open bottom) has its top screwed down through its face into its cleats, countersunk and filled, rather than up through the cleats from inside. With a ballast shelf, as before, the ballast goes in first and the screws stay removable.

## v2.15
- A flat piece drawn standing, as it will hang (a picture frame, a stretcher, a cradled panel), is laid down when the app guesses which way is up: thinner one way than 15% of either other way, and no more than 6 in, it gets that thin side up, as it is made on the bench. A Y-up export of a picture frame is now read lying flat too. A standing shadow box (deeper than 15% of its size) is still read standing; drawn lying it is recognised.

## v2.16
- The names a studio piece's parts get only when it lies the way it is made (face bar, box side, glass stop, back strip, panel face, cradle) count as strong evidence when guessing which way is up, so a shadow box drawn standing, as it hangs, is laid face down too.

## v2.17
- A "Print browser" template: an open bin for flipping through prints at a fair, low at the front and high at the back, its sides sloping between. Default 22 in inside, 14 in deep, 6 in at the front and 12 in at the back.
- A flat part whose outline is a right trapezoid (a rectangle with one edge sawn on a slope from end to end) is "ONE EDGE SLOPED FROM a TO b": it gets a detail drawing and a making step (mark both ends from the same long edge, saw the straight line), not full-size template pages.

## v2.18
- A flat part whose outline is a right triangle (a corner shelf, a corbel, a gusset) is "A RIGHT TRIANGLE" of its blank's size, not "shaped": a detail drawing and a making step (saw the blank corner to corner; one blank makes two), no full-size pages.

## v2.19
- The cutting plan holds half as many blanks (rounded up) for right triangles, and notes that each blank makes two, sawn corner to corner.

## v2.20
- Sloped sides cut in pairs: two nest head to tail in a blank as wide as their two end widths together, so the plan holds one such blank for each two (and a plain blank for an odd one), only when that is narrower than two separate blanks. The making steps cut the pair's blank and saw it along the slope.

## v2.21
- A flat part that is its rectangle with one or more corners sawn off straight, all the same size, is "N CORNERS CUT OFF a (X b)": a detail drawing shows which, and the making step marks the legs from each corner and saws the line. No full-size pages.

## v2.22
- A flat part that is its rectangle with one or more corners rounded to one radius (up to 40% of its narrower side, so a round top is still a circle) is "N CORNERS ROUNDED TO r RADIUS", r taken from where the straight edges stop: a detail drawing, and a making step (draw the curve with a compass or a tin that size, jigsaw outside the line, sand to it). No full-size pages.

## v2.23
- More one-tap sizes: the picture ledge by length (24 to 72 in, 60 to 180 cm), and the art crate by the work it is for (the standard canvas sizes, its inside the work's size plus 2 in or 50 mm of foam all round, the long side across).

## v2.24
- The flat-piece rule for which way is up leaves a shallow case standing (a spice rack): one with level boards right across its width and nearly its whole depth is not laid down.
- In a shallow case, a narrow board lying flat between two full-height boards at its ends, about as deep as they are, is a shelf (or the top, or the bottom), and those boards are its sides, rather than slats and uprights.

## v2.25
- The shape words (rounded corners, clipped corners, right triangle, sloped edge) share one outline per part instead of working it out four times.

## v2.26
- Two steps in a row that each fit one part from the same side are one step ("the rail and the shelf"), when the build has more than three steps; a part hung on hinges (a lid, a door, an easel's back leg, a crate lid) keeps its own step.

## v2.27
- The picture frame can take a mat: a border width (0 for none) makes the rabbet the art plus the border all round, and draws the mat in it, its window overlapping the art 1/4 in (6.35 mm) all round.
- A mat in any model (a sheet no thicker than 1/8 in, lying flat, with one window) is named "Mat", left out of the wood lists, and ordered: outside size and window, cut with a bevelled window by a framer or art shop.

## v2.28
- A crate's foam lining is on the "To order" list: sheet 2 in (50 mm) thick, enough to cover its inside with a tenth over for cutting.
- A crate's top frame board is screwed down like the rest of its frame, not fixed with corner brackets as a furniture top would be: screw heads do not matter on packing.

## v2.29
- Studio designs start with their usual material picked in the new-design dialog (birch plywood for plinths, cases and the taboret; plywood for crates; poplar for frames, shadow boxes, the easel and the ledge; pine for stretchers), so weights show from the start. "Not set" or another material can be chosen as before.

## v2.30
- A rod that stands upright, or a level one thinner than 1 in (a dowel divider, a towel bar), is glued into a hole drilled half the board's thickness deep at each end, not hung in closet rod sockets; those stay for a level rod 1 in thick or more. The model draws the rod butting, so the guide gives the rod's cut length with the holes added, where it first goes in.
- Spindles standing on a wide base board (a drying rack's dividers) are not a slatted panel made flat: the case goes together first, then all the spindles in one step, told as rows (how many, centres apart, where the first stands), then the rails that come down on their tops, with their holes marked from the base. Rails resting on rods are still screwed to the sides.
- A build's first step of one part alone (the bottom) takes the next step with it when that is two or three parts (its sides).

## v2.31
- A ring of boards on edge under a case is its plinth when what stands on it is the case's bottom (a recessed base under a gallery plinth), not only when the case's sides stand on it; before, its front board read as a face frame rail.
- A case's walls may stand on its plinth rather than the floor, and a door closes a side, for the "weigh it down" check: a storage plinth with a door in its back is weighed down on its bottom (sandbags or bricks), not strapped to a wall.
- Two or more like strips on edge in the back plane (above and below a door in a case's back) are back rails: one name for the item, not a rail and an apron.

## v2.32
- Separate pieces drawn in one file (nesting plinths, a pair of nightstands) are a set: groups of three or more parts that touch each other and stand at least 10 mm clear of every other group. Each piece is named on its own (one cube's sides are not another's dividers), built in turn in the guide ("Step 5, piece 2 of 3: start with …"), and drawn alone in its steps. The check notes "N separate pieces" instead of calling the groups a problem. Cut list, cutting plan and shopping list cover them together.
- A thin top over a deep open box is not a cradled painting panel: a cradle is no deeper than 4 in.
- A top over a closed box is a hinged lid only when the box has a bottom; an open-bottomed box (a display cube) keeps a fixed top.
- A case's bottom laid on its plinth: the guide says how far it overhangs the plinth all round, and to mark the outline first.
- Round spindles standing between level boards count towards which way is up (a drying rack stands on its base); square ones still do not, since a cabinet on its back is full of upright strips.

## v2.33
- A board with both long edges bevelled the same, through its thickness and sloping towards each other (45° or less), is a stave: "LONG EDGES CUT AT a (BEVEL), NARROW FACE n". Its making step rips both edges at one saw tilt, with a test ring of offcuts. A raised panel is still checked first.
- Five or more like boards standing evenly round a centre are the staves of a many-sided column: named Side, glued up in one first step (taped flat, rolled into the column, checked across opposite corners), every corner a mitre, including those whose boxes only just touch.
- A tall stave column is a closed pedestal: its top is screwed down through its face into the staves' top edges and filled, not bracketed from inside out of reach.
- A flat part whose outline is a regular polygon of 5 to 12 sides is "A REGULAR HEXAGON (OCTAGON, …), SIDES s": a detail drawing and a making step (a circle through the corners, a hexagon's side equal to its radius), no full-size pages.
- Build mode shows which piece a step belongs to in a set of separate pieces.

## v2.34
- New studio design: a six- or eight-sided plinth (any number of sides from 5 to 12), by the number of sides, the size across its corners, its height and board thickness. Sides bevelled at 180°/n and glued up round in one go, a top of the same shape screwed down into their edges. Usual material: birch plywood.
- In a stave column the top's step says it comes in from above (it was the base of the rest of the plan before the staves were taken out).
- A stave's making steps no longer add an empty "cut both ends at … off square" line.

## v2.35
- A folding screen: two or more tall flat panels (900 mm or taller) standing in a row in one plane, a hinge's gap apart (0.5 to 6.5 mm). Each panel's long members are stiles, the cross members rails (joined with pocket screws, as a face frame is), a thin sheet the panel. The panels are made alike on the bench ("make 3 screen panels") and hinged together: 2 in (50 mm) butt hinges, three at each joint on panels over 4 ft, two below, alternate faces for a zig-zag (or double-action screen hinges).
- A screen is not checked for tipping or strapped to a wall as drawn: a note says it stands folded in a zig-zag, and that a small block screwed to the floor inside each angle keeps it put on a busy floor. Its panels are not "parts joined to nothing".
- Which way up: a screen standing as drawn stays standing; one lying flat is stood up.

## v2.36
- New studio design: a folding screen, by the number of panels (2 to 8), panel width, height, stile and rail width, frame and sheet thickness, and an optional middle rail. Each panel is a pocket-screwed frame with a thin sheet glued over its whole back; panels stand 3 mm apart for the hinges. Usual material: poplar.
- A screen panel's bench step says to make the frame first, face down, then glue the sheet on its back, rather than laying the sheet down and building the frame on it (which would hide the pocket holes before they were screwed).

## v2.37
- Identical pieces in a set (four plinths alike: the same parts in the same places, to the millimetre) are built once: their first step says "make 4 alike", and each step's parts and hardware are counted for all of them. The other copies are not drawn or stepped through again; the cut list and hardware totals still count every one.
- A closed pedestal too deep to reach into from below, with no cleats under its top and no door, has its top screwed down through its face into the walls' top edges (countersunk, filled), as the stave columns' are, rather than corner brackets nobody could fix. In a set, each piece is judged on its own for this.
- Such a top's line gives the screws for each joint group, not the whole step's on every line.

## v2.37.1
- v2.37 with its new unit test corrected (it called the guide builder by a wrong name, so CI could not compile the tests). Includes everything in v2.37 and v2.36 (v2.36's phone test crashed inside Compose's layout while drawing, in a test that passed unchanged before; treated as a flake and run again here).

## v2.38
- A flat part that is its rectangle with one square notch out of a corner (a step stool's stepped side) is "ONE CORNER NOTCHED OUT a X b", not "shaped": a detail drawing and a making step (square both lines in, saw to where they meet, mark the second of a pair from the first). No full-size pages.
- Help, "Making for showing art", mentions the many-sided plinth, the folding screen, and sets of pieces built in turn (identical ones once).

## v2.39
- The New design dialog's Art and studio tab lists its designs under three headings, by what they are for: Showing work (plinths, the screen, frames, shadow box, ledge), Making work (easel, stretcher, cradled panel, taboret), Storing and moving (rack, browser, flat file, crate). With fifteen designs, one block of chips had become hard to scan.

## v2.40
- A table whose legs go straight into its top, with nothing else touching them (no aprons, rails or stretchers): the top is put down first (face down) and the legs go on last, each bolted with a leg mounting plate and a hanger bolt (or threaded inserts), one per leg in the board its end mostly sits under. Not corner brackets or screws, which work loose with legs held only at the top. Found on a real downloaded table after "make it from boards".
- Such legs over 18 in long get a CHECK: nothing between them, so a sideways push levers on one joint; aprons or a low stretcher stiffen it (a rule of thumb, not a calculation).
- The many-sided plinth is called "Many-sided plinth" in the list (shorter on a phone).

## v2.41
- "Make it from boards" keeps round parts standing out beyond the boards (turned feet under a downloaded TV unit), which its grid of flat faces could not see and used to drop: each becomes a cylinder from the board it stands under to its end. Anything else standing out beyond the boards that is not round stops the change ("it has curved parts the boards would leave out") rather than losing it; points between boards inside the piece are treated as before.
- Three or more alike round parts standing under a piece narrower than 2 in across are round feet, not casters (a caster's wheel and housing are wider): cut from dowel or bought turned, fixed last with hanger bolts (glued dowel feet as the alternative), listed as parts, with the hanger bolts on the shopping list. A foot's top may sit up to 4 mm into the board above (a thickness rounded up by the fixer).
- "Feet", not "foots", in step titles.

## v2.42
- Stacked shelves (boards with spacers between them, the same sizes level on level, as in a downloaded bookcase) are built from the bottom up, level by level, not with their upper levels made first as a "hutch": a case set on another is one whose parts differ from those under it.

## v2.43
- glTF 2.0 opens: a binary .glb, or a .gltf with its buffers inside it (base64). Each mesh primitive placed in the scene is its own part, moved by its node's transform (matrix, or translation, rotation and scale, through the node tree). glTF is metres with Y up: sizes come out in millimetres with Z up. Draco or meshopt compression, and geometry kept in a separate .bin file, are refused with a message saying how to export instead. Lines, points and strips are skipped with a warning. Many phone and web modelling apps export glTF.
- A file that states its unit (3MF, glTF) is no longer second-guessed as inches or centimetres: only whether it was designed in inches is still guessed, for how sizes are shown.
- The import screen, the app's "Open with" types and Help (File types) say so.

## v2.44
- The project menu has "Make a crate for this" and "Make a plinth for this": a new design sized from the open model taken as a work. The crate's inside is the work plus 2 in (50.8 mm) of foam all round (the wider of its two level sizes across, its height up, the narrower as depth). The plinth's top is the work's footprint plus 4 in (101.6 mm) each side (at least 300 mm), and its height puts the work's middle near 57 in (1.45 m), the usual gallery eye level, kept between 18 and 42 in. Sizes are rounded up to 1/4 in or 5 mm; the design's usual material is picked. Help explains it.

## v2.45
- PLY opens, text or binary (either byte order): vertex positions and faces, larger faces fanned into triangles, other properties and elements skipped. It records no unit, so units are guessed as for an STL. A PLY with no faces (a raw scan's point cloud) is refused with a message to mesh it first. Scanning apps export PLY, and a scanned work is what "Make a crate for this" is for.
- A phone test opens a design and makes a plinth for it from the project menu.

## v2.46
- Unit guessing: read in a unit other than millimetres, a model under 25 mm (an inch) is unlikely, so that reading loses 0.5 points. A scan exported in metres (0.31 long) now opens as a 310 mm work, not a 7.9 mm one in inches; a small millimetre file (a 20 mm knob) is unaffected. Every test model's units are unchanged.

## v2.47
- The glTF and PLY readers keep triangles in plain growing arrays rather than lists of boxed numbers (a phone scan can hold millions of triangles: boxed, that is several times the memory), and stop with the usual message past the app's triangle limit.

## v2.48
- A model of one to three parts, all solid shapes rather than boards (a scanned or sculpted work), gets a note pointing to the project menu's Make a crate for this and Make a plinth for this.

## v2.49
- The project menu also has "Make a shadow box for this": the box's inside is the work's face (the wider level size by its height) plus an inch (25.4 mm) of air each side, and its depth the work's thinner level size plus an inch in front, under the glass. The note on solid shapes and Help mention it.

## v2.50
- A project made from a design (New design, or Make a crate/plinth/shadow box for this) remembers which design and its sizes. The project menu's "Design sizes" shows them to change and builds the design again in place, so iterating on a size no longer means starting a new project. Materials stay; changes made to the model since, the step order and wording, and part names and exclusions (all tied to the old parts) are dropped, and the dialog says so in red when there are any.
- A phone test changes a plinth's height that way.

## v2.51
- Design sizes shows a small picture of the design at the sizes typed, or why they do not fit, as New design does.
- A rail lying on the tops of three or more spindles is their "Top rail" (a drying rack's were called stretchers).

## v2.52
- A stud wall (upright studs between level plates, a sheet over one or both faces, boards on the floor across it as feet) is named so: Skin, Stud, Plate, Foot. Its skins are not doors, its studs not spindles. It is built frame first in one step (laid flat, plates on edge, studs flush at each end and the others' centres given), then each face's sheets, then the feet. Sheets meeting on a stud are not joined to each other. No anti-tip strap: a CHECK says to screw the feet to the floor or sandbag them, and hang work evenly on both faces.
- New studio design, under Showing work: Display wall, by width, height, stud spacing on centre, stud size, skin thickness, feet length, and skin on one or both faces. Skins come in sheets no wider than 4 ft (1220 mm), joined on a stud's centre; a sliver under 25 mm at the end goes on the last sheet instead. Usual material: birch plywood.

## v2.53
- A stud wall gets a note, also on the guide's last page: fill the screw heads and sheet joints, sand and paint (gallery walls are usually white, matt or eggshell); hang heavy work on screws into the studs (their spacing on centre, from the inner studs), light work anywhere on the plywood with screws short enough not to come through. "Keep it on its feet" goes on that page too.
- Help mentions the display wall.

## v2.54
- A stave's detail drawing says in its notes how its long edges are bevelled: the angle off square, sloping towards each other, the outside and inside face widths, and to rip them all at one blade tilt.
