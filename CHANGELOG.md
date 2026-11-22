# Changelog

## v0.40.0 - Professional cutting plan, applied drawer fronts, new guide cover

- **Cutting plan, much tighter.** In metric, drawer sides, drawer fronts and other boards of sheet thickness are now ripped from the sheet instead of bought as lengths of an odd width (12 x 191.5 is not sold anywhere). A board that would need a whole extra panel just for itself is bought as a length after all. In inches, boards that would have to be ripped from lumber come from the sheet you are buying anyway. The packer now tries several layouts and keeps the best one, so the chest of drawers' 18 mm parts fit one sheet instead of a sheet plus a quarter sheet. Sheets hang from the top of the page, and short pieces get their size on one line.
- **Applied drawer fronts are fitted the way cabinetmakers do it.** The drawer box is made and hung first. Then the front is held in place with even gaps and screwed on from inside, with the right screw length. Before, the front was glued and pinned to the box on the bench, which makes the gaps impossible to set.
- **Drawers and doors come out of the front** in exploded views and step pictures, whole, instead of flying up through the carcass. A step that fits four drawers says "×4" once, not one tag per board.
- **Rabbets where they belong.** A thin panel at the edge of another part (a back panel at the back of the sides, a drawer bottom at the bottom edge) is set in a rabbet, not a groove. A thin part held in several parts is explained once ("cut a rabbet in items 6 and 7"), and "Before you build" reminds you to cut grooves and rabbets while the boards are loose.
- **New guide cover.** A parts table (item, quantity, name, size, material) on the cover, hardware beside the summary, and the picture sized so everything fits. No more page with a single leftover line.
- **Names in the drawings' list of material** ("SIDE, PANEL 18 X 450 X 900"), and a Name column in the cut list CSV.
- **The sag check covers shelves on pins**, which are held only at their ends.
- Includes v0.39.0 (3MF files, named guide steps, fastener count fix).

## v0.39.0 - 3MF, named guide steps, hardware count fix

- **Open 3MF files.** 3MF is the zipped format many modelling and slicing apps export. Each object becomes its own part, placed where the file puts it, with sizes converted to millimetres from the file's unit. Import it, or open it from another app.
- **The guide names the parts**: "Drill pilot holes through item 1 (side) and screw it to item 2 (bottom)", and each step's parts list says what each item is.
- **Fix: fasteners for identical sub-assemblies were counted twice.** A chest of 4 identical drawers asked for 32 brads where 8 are needed. Each bench step now tells how to make one copy, and the hardware list counts every copy once.
- **Graduated drawers.** Chest of drawers has a new option for deeper drawers lower down (each a fifth deeper than the one above).
- **Wide doors.** The check queries a single door much over 600 mm (24 in) wide. That's a rule of thumb: such doors tend to sag, and a pair is usual.
- "Making the parts" now lists only parts that need more than cutting to size. "Slide all 4 drawers in" replaces "both drawers" when there are more than two.
- Includes v0.38.0 (add a drawer, hinge cups, part names).

## v0.38.0 - Add a drawer, hinge cups, part names

- **Add a drawer.** More… → Add a shelf, divider, door or drawer → Drawer fills an opening with a drawer on slides: an inset front, and a box behind it with 1/2 in (12.7 mm) each side for ball-bearing slides. Slides and a handle go on the hardware list, and the guide hangs it last.
- **Hinge cups on door drawings.** Every door now gets its own drawing showing where the 35 mm hinge cups go, located and called out (usually 22 mm in from the edge, 13 mm deep, about 100 mm from each end). The note says to check these against your hinges' template before drilling.
- **Parts have names.** Parts with no name of their own are now called what they look like: Side, Top, Bottom, Shelf, Divider, Back panel, Kick board, Leg, Apron, Door, Drawer front, Drawer side, Drawer bottom, Adjustable shelf. You see the names in the Parts list, the Cut list and when you tap a part. It is a guess from where each part sits; rename any part from the Parts list.
- Includes v0.37.0 (chest of drawers, right-length slides).

## v0.37.0 - Chest of drawers


## v0.36.0 - Adjustable shelves on pins

- **Adjustable shelves on pins.** Add shelf now has "Adjustable, on shelf pins": the shelf is 1.5 mm short at each end and rests on 4 pins. New design → Bookcase has "Shelves on pins" too. Shelves like this are also found in models you open.
- **Pin holes on the drawings.** The sides that carry adjustable shelves get their own drawing with the hole rows: 37 mm in from the front and back edges, 32 mm apart (the common 32 mm system that shelf-pin jigs use), and where the first hole goes. "Before you build" reminds you to drill them while the boards are loose.
- **Shelf pins in the hardware list and guide.** 4 per shelf, fitted in the last step: "push four shelf pins into the holes at the height you want".
- **Identical doors or shelves in one step.** A pair of doors, or a stack of adjustable shelves, now go in together in one guide step instead of one step each.
- Includes v0.35.0 (doors, hinges and handles).

## v0.35.0 - Doors, hinges and handles

- **Doors.** Add a door to any opening (More… → Add shelf, divider or door, then pick Door). It is set in the opening with a 2 mm gap all round. Doors are found in models you open too: set in an opening, or over the front of a box (overlay doors), alone or as a pair.
- **Hinges and handles.** Each door gets 2, 3 or 4 concealed hinges by its height (a common cabinetmakers' rule), shown in the check, the guide step and the shopping list. Every door and drawer now adds a handle or knob to the hardware list; the style is up to you.
- **Doors and drawers go on last.** The build guide now hangs doors and drawers after the box is finished, instead of before the top goes on.
- **Cabinet template with doors.** New design → Cabinet has Doors (0, 1 or 2) and "Doors over the front" (0 = set in, 1 = over the front). With set-in doors the shelf is set back so the doors close.
- A door that sits over the front is never screwed on in the guide; it only hangs on its hinges.

## v0.34.0 - Add shelves and dividers

Add shelves and dividers in one go.

- **Add a shelf or divider.** On the 3D tab, More… → **Add a shelf or divider**:
  - **Shelf:** type the height of its top. It spans between the sides on either side of the middle, as deep as they reach, stopping at a back panel.
  - **Divider:** type where its centre goes. It stands between the shelf (or top and bottom) above and below it.
  - If there's no room (nothing to hold it on both sides, or something in the way), it says so instead of adding a part that would float or overlap.

## v0.33.0 - Safety and stiffness checks

Safety and stiffness checks.

- **Fix it to the wall.** A piece taller than 30 in and less than half as deep as it is tall (a bookcase, say) can be pulled over by a climbing child or a heavy drawer. The Check tab now says so, an anti-tip wall strap is added to the hardware, and the guide ends with fixing it to the wall.
- **Racking.** A case with two upright sides and no back panel (or wide rail across the back) leans like a parallelogram when pushed. The Check tab now spots it and says what stops it.
- The note on joints now points to Joining on the Guide tab, and says plainly that joint strength is never calculated.

## v0.32.0 - Tidier screens

Tidier screens.

- **More room for the list and the model.** On the 3D, Cut list and Drawing tabs, the less-used actions now sit behind a **More…** button:
  - 3D: Overall size, Changes.
  - Cut list: Prices, CSV, CNC profiles.
  - Drawing: sheet image, DXF, full-size templates.
  The cut list on a small phone used to lose a third of the screen to buttons.
- The home screen now says what the app does: a 3D model or a template in; checked parts, drawings, a build guide and a shopping list out.

## v0.31.0 - Pocket holes, hardware per step, gaps

Pocket holes done right, hardware per step, and gaps you can read.

- **Pocket holes in the right part.** With pocket screws, the guide now puts the pocket holes in the end of the part that meets the other's face (the shelf, not the side), on a face that won't show. The screw length follows that part's thickness.
- **Drill first.** "Before you build" now says how many pocket holes to drill in each item before assembly.
- **Hardware with each step.** Each step's parts list also lists the screws, brackets, brads or slides that step uses, so you can lay them out together.
- **Gaps in 3D.** Tap a part on the 3D tab and it shows the clear space to the nearest part on each side, e.g. "Gap left 1/2 to item 3, above 1/4 to item 1". Handy for checking a drawer's room for slides, or a shelf's fit.

## v0.30.0 - How to make each part

How to make each part, with your tools.

- **How to make it.** Tap an item on the Cut list tab and it now lists the steps to make that part, using the tools you have ticked:
  - cut the blank from the right stock (with the grain the way you set);
  - cut notches, curves and openings, drilling a starter hole for the blade;
  - drill holes over a scrap board;
  - round the edges, plane to thickness, and cut any grooves with their sizes.
  When a step needs a tool you haven't ticked, it says so and suggests the slow way.
- **Making the parts** is a new section of the build guide, before the steps. It covers every item that needs more than cutting to size.
- The phone test now also opens an OBJ file and checks that a shared project comes back with its changes. Both pass.

## v0.29.0 - Prices, grain and project files

What it costs, grain that stays put, and projects you can back up.

- **Prices.** On the Cut list tab, **Prices** lets you type what each sheet and board costs where you shop. The Cut list then shows what the wood comes to, and the shopping list prices every line and gives a total. Prices are remembered for your other projects.
- **Grain.** Tap an item and tick "Grain runs along its length". The cutting plan then never turns that part on the sheet, and marks it G, so a visible panel's grain runs the way you want.
- **Share project.** Tap the summary at the top of a project, then **Share project**. You get one file with the model and everything you set and changed. Open it on any phone with the app (Import, or Open with) and the project comes back whole, changes included.
- **OBJ files** are recognised by their contents as well as their name, and stand the right way up when opened.
- Import is now one button: **Import** (STL, OBJ or a shared project).

## v0.28.0 - Drawers, nightstand, turning parts

Drawers that work like drawers, a nightstand to start from, and turning parts.

- **Drawers.** The app now recognises a drawer box (an open box sitting in an opening):
  - The guide builds it on the bench ("Step 1: make the drawer box from items 4, 6 and 7") and later says "Slide the drawer from step 1 in", with a line on fitting the slides.
  - The hardware and shopping lists include a pair of slides of the right length for each drawer (14 in for a 15-3/8 in deep box).
  - The check no longer calls a drawer "unconnected" just because its slides aren't in the model, and it warns when there isn't room for slides at the sides.
- **Nightstand template:** carcass, a drawer on slides with the 1/2 in clearance slides need, a false front and an open shelf.
- **Turn a part.** The Change dialog can turn a part a quarter turn: flat, tipped front to back, or tipped sideways. It stays on the same floor.
- Back panels no longer count toward edge banding, since they face the wall.

## v0.27.0 - Faster big models, drawer checks

Faster on big models, drawer checks, and dialogs that speak your units.

- **Big assemblies draw about twice as fast.** On a 600-part model the drawing went from 12 s to about 6 s. Repeated parts (hundreds of identical bolts) are now traced once, and plain boards use a quick outline.
- **Drawer slides.** The Check tab now spots drawer boxes (an open box sitting in an opening) that leave no room for slides. Ball-bearing slides need about 1/2 in each side; the check says so before you cut the drawer parts.
- **Ready to build.** When nothing stops the build, the Check tab says so in green and points to the next steps: read the guide, then share the shopping list.
- **Sizes in your style.** In fraction projects, the size boxes in Change, Add a board and Overall size start in fractions (3/4, 36-1/2). The Change dialog is titled by item number, and its buttons wrap instead of running off the edge.
- **Fixed:** a speed-up made earlier in this release could undo small notches when parts were edited. It was caught by a new test that runs your desk through Fix what can be fixed and checks nothing is left that stops the build. The speed-up now only applies to pictures.

## v0.26.0 - Full-size templates, before and after

Full-size paper templates, and a guide that covers before and after the build.

- **Full-size templates.** On the Drawing tab, **Full-size templates** makes a PDF of every strongly shaped flat part (a round top, a bracket, a curved leg) at full size, split across letter or A4 pages.
  - Print at 100% and check the 1 in line on each page with a rule.
  - Trim at the dashed boxes, line up the crosses, tape the pages together and trace onto the wood.
  - Plain rectangles, even with rounded corners or a notch, are left to their drawings.
- **Before you build / When it is together.** The guide has a new page after the cover:
  - Before: cut and label every part, cut any grooves while the boards are loose, band the edges that show, sand faces that will be hard to reach later, and dry-fit each step.
  - After: fill screw holes, sand and finish.
- The shopping list is now worked out off the main thread, so a big model doesn't freeze the screen while it's made.

## v0.25.0 - Shopping list, changes list, OBJ

A shopping list for the store, every change undoable, and OBJ files.

- **Shopping list.** On the Cut list tab, **Shopping list** shares plain text you can keep on your phone at the store: sheets and lumber to buy, edge banding, and the screws, brackets and glue the guide calls for.
- **Changes.** On the 3D tab, **Changes (N)** lists every change made to the model in plain words, oldest first, including the ones Fix what can be fixed made. Take back any one of them and the rest are replayed without it. **Take all back** goes back to the file as it was.
- **OBJ files.** Import (and Open with) now takes Wavefront OBJ as well as STL. Each object or group in the file becomes a part. OBJ files start Y-up, the way most programs save them; change it under Which way is up.
- **Fractions in the cutting plan** sizes ("1/8" for the saw cut). The cutting plan in the app now names standard lumber (1x4, 2x6) in inch projects, as the drawings and CLI already did.

## v0.24.0 - Overall size, fractions and a desk template

Resize the whole piece, fractions by default, and a desk to start from.

- **Overall size.** On the 3D tab, **Overall size** makes the whole piece wider, deeper or taller (or smaller). The app finds a line across the middle that crosses only plain lengths of board. Those parts get longer or shorter and everything beyond moves with them, so board thicknesses and joints stay as they were. Undo takes it back. This works on templates and on imported models: the test desk can be resized all three ways.
- **Fractions.** New inch projects show 3-1/2 and 22-5/8 rather than 3.50 and 22.63. Change it under Units. You can type sizes as fractions too: "30-1/2", "30 1/2" or "3/4".
- **Desk template:** a top on two panel ends, braced by a modesty panel. The table template's overhang is now a round 1 in.
- **Sag fix in one tap.** A shelf the check thinks may sag now offers the thinnest stock thickness that keeps it within the rule.
- **Fewer leftover overlaps.** Fix what can be fixed now settles every hairline overlap on large models. On the test desk only one is left.

## v0.23.0 - Housing joints and edge banding

Grooves as real joints, and the desk comes out with no problems left.

- **Housing joints.** Where a smaller part runs part way into a bigger one (no deeper than half its thickness), the app can now keep it as drawn and treat the overlap as a groove cut to take it. **Fix what can be fixed** does this by itself. On the Check tab it's offered as "Keep it: groove item ...". After that:
  - the overlap is no longer reported as a problem;
  - the grooved part gets its own detail sheet giving the groove's depth, width and length;
  - the guide says to glue the part into its groove.
- **Your desk:** Fix what can be fixed now leaves **no problems**. The last two overlaps (the 2 in panel with the strip and the blocks running into it) become grooves in that panel. What's left are a few hairline overlaps to trim while building, and two shelves that may sag under load.
- **Edge banding.** The cutting plan now estimates how much iron-on edge banding to buy for plywood or particleboard edges that show. Edges facing the back and the floor aren't counted.
- **Paper you can print.** Title block → Sheet size has a new choice: "Choose, but printable at home (A or B)". It keeps every sheet to letter or 11 x 17 in.
- **Build steps in 3D.** Bench steps now show the rest of the piece faint, so you can see where the sub-assembly goes. The pull-apart slider is hidden while playing.
- Drawings of large assemblies use all of the phone's cores for hidden lines.

## v0.22.0 - Build steps in 3D and CNC profiles

Watch it go together, and send parts to a CNC.

- **Build steps in 3D.** On the 3D tab, tap **Build steps**. The model then appears one step at a time with the arrows: this step's parts in colour, what's already built in grey, later parts hidden. You can still turn and zoom it as usual. **Show all** goes back.
- **CNC profiles.** On the Cut list tab, **CNC profiles** shares a DXF with every flat item's real outline at full size in millimetres, openings included, labelled with how many to cut and how thick. Outsides and openings are on separate layers. Blocks and rods are left out, and the app says which.
- **Bigger step pictures.** Early steps used to draw a few parts tiny, in a frame sized for the finished piece. Each picture now frames what is built so far, but never less than about half the finished piece, so it doesn't jump around.
- **A more natural starting part.** The build starts from a cabinet's bottom when it lies flat on the floor, otherwise from the biggest part standing there: a bookcase's side, not its kick board.
- **3D view buttons** (Front, Top, Right, 3D) are smaller and sit down the edge, off the model.

## v0.21.0 - Templates, materials and weight

Start from a real piece of furniture, and know what it weighs.

- **New design templates.** New design now offers a bookcase, cabinet, table, bench or open box. Type the main sizes (width, height, depth, board thickness, number of shelves) and you get a complete model in stock thicknesses: 3/4 in boards, a 1/4 in back and 4x4 legs in inch countries, 18 mm and 6 mm in metric ones. Every part can be changed afterwards. "One board" is still there.
- **Materials in one tap.** Tapping an item in the cut list shows chips for common materials (birch plywood, MDF, pine, oak...). Tick the box to give every other item the same material.
- **Weight.** The Cut list tab says roughly what the piece weighs from the materials you set, and the drawing's title block fills in WT once every item has one. Typical densities are used, so it says "about".
- **Balance check by material.** When every item has a known material, the tipping check weighs each part by it, instead of treating everything as the same wood.
- **Legs too thick to screw through** (a 4x4) are fixed to their aprons with brackets in the inside corner, rather than with a 3 in screw that would not reach.
- Standard 4x4 and 4x6 posts are now in the lumber list for the cutting plan.

## v0.20.0 - Drawings you can cut from

Drawings a maker can cut from, and a shorter guide.

- **Panels with rounded edges draw properly.** A top with a rounded-over (bullnose) edge used to lose its outline in the side views, and on its own sheet. Those lines are back.
- **Cut-outs and corners on detail sheets.** A flat part's sheet now says the corner radius and where each opening is and how big: "OPENING A: ROUND-ENDED, 6.00 X 2.00 THRU, ITS BOX STARTING 36.00 FROM LEFT EDGE AND 36.83 FROM BOTTOM EDGE."
- **A pictorial view on sheet 1**, in the largest empty corner, so the three flat views are easy to read. Left off assemblies over 150 parts to keep drawing quick.
- **Views stay clear of the notes.** When a note would sit on top of a dimension, the drawing moves to a bigger sheet.
- **Shorter guide.** Two boards from one drawn part are fitted in the main build, not given a bench step of their own. A step never places more than five things from one side, so a pedestal and its drawers are no longer one crowded step.
- When there is no groove, the guide says why: no router or table saw ticked, or the part is too thin to groove.

## v0.19.0 - Grooves, edge joints and smaller sheets

Joints you can actually make, and a shopping list that doesn't buy eight full sheets.

- **Grooves with sizes.** Where a part is too thin to screw into, the guide now gives the groove: "Cut a groove 1/2 in wide and 3/8 in deep in item 11 for its edge, make item 40 3/8 in bigger on that edge than the cut list says, and glue it in." If you have not ticked a router or table saw, it tells you to glue a cleat instead. Depth is 0.4 of the grooved part, at most 3/8 in, a common rule for housings.
- **No more screws along inside a thin board.** A 1/4 in board met on its edge is set in a groove, and two boards lying side by side in one plane are edge-glued and clamped, not screwed.
- **Joints in the right step.** A frame built on the bench is no longer told to be screwed to parts it has not met yet; joints between sub-assemblies are made when they come together in the main build. Bench steps now say how their own joints are made.
- **Plain counts:** "screw it to item 7 with two 2-1/2 in screws", and the end of a busy step lists every remaining joint and how it is made, instead of "the other 8 the same way".
- **Half and quarter sheets.** When everything of one thickness fits on half or a quarter of a sheet, the cutting plan buys that size (most stores sell 2 x 4 ft and 4 x 4 ft panels). The test desk went from 8 full sheets to 4 full sheets and 4 quarter sheets.
- **Copy diagnostic report** moved into Help, giving every screen another line.

## v0.18.0 - Which way is up, and leaner drawings

Models saved the wrong way up, fewer pointless drawing sheets, a cleaner guide, and a roomier screen.

- **Which way is up.** Many programs save STL files with Y as up, so a desk arrives lying on its back. Tap the summary at the top of a project, then **Which way is up**, and pick the axis that should point up. The floor, build order and drawings all follow it.
- **No detail sheet for plain boards.** A board with no notch, hole or shaping is fully given by its three sizes in the list of material, so it no longer gets a sheet of its own. A note on sheet 1 says which items those are. The test desk went from 47 detail sheets to 11.
- **Every part is labelled somewhere.** A part hidden in every exploded view (under a top, inside a frame) now gets a last sheet showing it on its own.
- **A guide that reads like one.** "Fit items 3, 4 and 7 from above, in that order" instead of a line per part. A step with a page to itself gets a full-width picture and its parts in two columns, and long part names wrap neatly.
- **Thin frames made from strips.** A frame or U-shape that would waste most of a sheet (the desk's 1 in wide frames cut from 24 x 48 in) is now made from strips by Fix what can be fixed, and the strips are not glued back into one sheet piece afterwards.
- **More room on the screen.** The project summary is two lines; tap it for units, notes and which way is up. Buttons on the Drawing and Guide tabs wrap instead of squashing ("Ima ge", and a Share button one letter wide on narrow phones).

## v0.17.0 - Lumber-yard shopping list and butt joints

A shopping list for a real lumber yard, and overlaps settled as butt joints.

- **Standard lumber.** In inch projects the cutting plan now buys boards as 1x, 5/4x and 2x lumber: "2 lengths of 1x3 (actual .75 x 2.50), some pieces ripped narrower". Boards in thicknesses lumber is not sold in (1/2 in, 1/4 in strips) are cut from sheet, as they are in practice.
- **Sheets by their real names:** 1/4 in, 3/8 in, 3/4 in. A thickness drawn a hair off (1.02 in) counts as the stock one.
- **Panels thicker than any sheet** (a 2 in slab) are listed as glue-ups or butcher-block, not "buy a 2 in sheet".
- **Butt joints.** Where one part stops partway into another, Fix what can be fixed now cuts the smaller part back to the other's face. The Check tab offers that cut either way, with how much of each part it removes.
- **Much faster on big assemblies:** the 600-part test's guide went from 40 s to under 6 s.

## v0.16.0 - Joining and hardware

The guide now says how to fix each joint, and what hardware to buy.

- **Joining.** On the Guide tab, tap **Joining** and pick screws, pocket screws, glue and dowels, or glue and brads. It starts with what your ticked tools allow.
- **Every step says what holds it**, joint by joint: "Drill pilot holes through item 11 and screw it to item 13 with 4 2-1/2 in screws (2 joints)."
  - Tops are fixed from underneath with corner brackets, never through the top.
  - Pieces laid face to face are glued, clamped and pinned.
  - A part too thin to take a screw is glued into a groove or against a cleat, and the step says so.
- **Hardware list on the cover:** how many of each fastener and length, plus glue.
- How it chooses: a screw goes through the thinner part and into the other about 1.5 times that thickness, never far enough to come out the far side, one every 10 in or so. These are workshop rules of thumb, not a strength calculation.

## v0.15.0 - Sub-assemblies, and every part labelled

A build guide that builds like a person would, and drawings that label every part.

- **Sub-assemblies first.** Frames, boxes and other pieces made of several boards are now built on the bench in their own steps ("Step 1: make 2 by joining items 11, 13 and 23"), with the first board laid down and the rest joined to it. Later steps fit them whole: "Fit both sub-assemblies from step 8 from the front."
- **A more sensible order.** Parts that stand on the floor go in before parts that rest on them, so a second leg frame is stood up before the top that spans both.
- **Long steps no longer overlap.** A step with a long parts list starts its instructions below the list and carries on to the next page if needed.
- **Every part is labelled on the drawings.** When the main exploded view cannot show a part (inside a cabinet, or crowded out), further exploded sheets show the build steps it belongs to, with earlier parts in grey for context.
- **The list of material always exists.** When it was too long for the first sheet, the drawing used to say "see separate parts list" and none was made. It now has sheets of its own, and the first sheet says which.

## v0.14.0 - Real boards, and editing in the app

Real boards, and editing in the app.

- **Frames, boxes and channels drawn as one solid become real boards.** 3D programs often join a frame or an open box into one piece that nobody could cut. The check now finds these and offers **Make from N boards**. On the test desk: each leg frame becomes 4 boards instead of "a 3-inch slab with a hole", the drawer cabinet becomes 6 boards, the long rail 3. Frames with a step for a panel become two stacked layers, which make the same step. Rounded corners come out square.
- **Fix what can be fixed does this too**, for shapes that cannot be bought or cut in one piece. Its list is now one line per change, named by the item numbers you see on the Cut list, with how much any piece is trimmed. No part loses more than a quarter in total.
- **Change parts yourself.** On the 3D tab, tap a part and choose **Change**: set its size, move it, **slide it until it touches** the next part (up, down, left, right, back, front), copy it, square it up, make it from boards, or delete it. **Add a board** puts a new board above the model to slide into place. **Undo** takes the last change back.
- **New design** on the home screen starts a project from a single board.
- **The changed model keeps its parts.** "Whole model (STL)" marks each part in the file, so opening it again gives the same parts. Text STL files with several named solids are now kept as separate parts too.
- **Cut list wording:** a stick with a notch sawn in it is now listed as a notched board, not a "solid block".

## v0.13.0 - Much larger STL files

Much larger and more complex STL files.

- **Big files open.** Files are now read straight from storage instead of being loaded into memory whole, and kept at the precision STL files store, which halves the memory needed. Tested under a phone-sized memory limit with a 2-million-triangle sculpted shape (was: out of memory), a 1.5-million-triangle cabinet, and a 600-part assembly with 2.1 million triangles (was: out of memory).
- **Heavy models are simplified for drawing, and say so.** Above about 400,000 triangles, the app works with a lighter copy. It keeps sharp corners and flat faces exact, and never lets a surface move by more than 0.05% of the model's size (0.24 mm on a 600 mm assembly). The notes under the file name state the figure. If simplifying a part would move it more than that, the part is left as it is.
- **Planning assemblies with hundreds of parts** is about 80 times faster (600 parts: 139 s → 1.7 s).
- Known limits: a 600-part guide still takes tens of seconds on a phone, and sizes on tiny parts in a huge model can shift in the last decimal (a 4.0 mm ring read as 4.1).

## v0.12.0 - Update from inside the app

Update from inside the app.

- **Update button.** When a new version is out, the home screen shows "Version X is available" with an **Update** button. Tap it: the app downloads the new version and Android asks you to install it. Your projects are kept.
- The first time, Android asks you to allow Splitziner to install apps. The app takes you to that setting; switch it on, come back and tap Update again.
- **Check for updates** at the bottom of the home screen looks straight away. Otherwise the app looks once a day when you open it; untick "Look for updates once a day" in that dialog to stop it.
- The app now asks for internet access. It is used only to look for and download updates from this project's GitHub releases. Your models never leave the phone.
- **All tabs now fit on screen.** On a narrow phone, Guide and Check were hidden off the right edge; the tabs now wrap onto a second line.

## v0.11.0 - Design check, tools and fixing

The app now examines a design, says what is wrong with it, and can fix part of it. Built and tested against a real desk file.

- **New Check tab.** It looks at the model the way someone about to build it would and lists what it finds, worst first: parts that pass through each other, parts that touch nothing, hollow shapes drawn as one solid, thicknesses shops do not stock, shelves that run a long way between supports, and whether the thing would tip over. Each finding can be shown in 3D.
- **Tools.** On the Check tab, tap **Tools** and tick what you own. The check then tells you which parts you cannot make, or can only make slowly, and what would do the job. The build guide's cover lists your tools for this build. Your choice is remembered for the next project.
- **Fix what can be fixed.** One button proposes changes and shows them before anything happens: loose parts moved to rest on the model, overlapping flat pieces of one sheet joined into one, parts trimmed where they run into a neighbour, odd thicknesses moved to ones you can buy. What it will not decide for you is listed too. Some findings have their own fix (for example "Make .25 thick" or "Square these parts up").
- **Undo, and export.** Your file is never altered. **Undo** removes changes; **Parts > Whole model (STL)** shares the changed model.
- **Better cut list for rough files.** Parts whose surface has small gaps are now described by their real shape ("PANEL 1.00 X 42.04 X 78.00") instead of only an outer box.
- **What it cannot do:** it reasons from shapes only. It knows nothing about wood, glue, screws or loads; its sag and balance findings are rules of thumb. It cannot cut a recess for a joint, or turn a hollow one-piece shape into boards. Moving, resizing and adding parts by hand is not in this version.
- Behind the scenes, every release is now also run on an emulated phone, opening the desk and a cabinet and going through every tab.

## v0.10.0 - Cutting plan

A cutting plan: how much to buy and where to cut.

- **Cutting plan.** On the Cut list tab, tap **Cutting plan**. Enter the sheet size, the length your boards come in and the width of your saw cut, and share a PDF that shows:
  - **what to buy** (how many sheets of each thickness, how many lengths of each board size),
  - **each sheet drawn to scale** with every panel numbered and sized,
  - **each board length as a bar** with its cuts and what is left over.
- Every sheet can be cut with straight cuts right across, the way a circular or table saw works.
- Untick "Panels may be turned" to keep grain running along the sheet. Your sizes are remembered for next time.
- It is a good layout, not a proven best one, and it knows nothing about grain, defects or offcuts you already own. Parts bigger than your stock are listed as left out.

## v0.9.0 - Holes on the drawings

Drawings now tell you where to drill.

- **Holes are found and dimensioned.** Each part's own drawing shows its round holes with centre lines, a size note such as **3X 8 DIA THRU** or **8 DIA 10 DEEP**, and every hole centre measured from the same corner as the other dimensions.
- **The cut list names them.** A drilled board now reads "BOARD 18 X 40 X 160, 4 HOLES" instead of "NOTCHED".
- **What it can't tell:** threads, countersinks and what a hole is for. Holes drilled at an angle are drawn but not dimensioned, and a chamfered rim measures at the chamfer's outer edge. Diameters come from the facets in the STL, so check critical ones against your design. Help has a new **Holes** entry.
- The README now describes everything the app does.

## v0.8.0 - More ways to share

More ways to get your work out of the app.

- **Share parts as separate STL files.** On the Parts tab, one button packs every part as its own STL in a ZIP, in millimetres, with a list saying which file is which item. Handy for printing or re-using single parts.
- **Share a sheet as an image.** On the Drawing tab, **Image** sends the sheet you are looking at as a PNG, for a quick message or a post.
- **DXF export.** **DXF** packs every sheet as a DXF file for CAD programs, with line types on separate layers. These record the drawing; they are not cutting profiles.
- **Share PDF** is unchanged and still contains the whole set.

## v0.7.0 - Part drawings and pull-apart 3D

A drawing for every part, and a pull-apart slider in 3D.

- **Detail drawings.** After the assembly sheets, the drawing set now has one sheet per item in the cut list: that part on its own, drawn from the front, top and side with its dimensions, numbered after your drawing number (for example CAB-001-3) and noting how many to make. Parts that sit at an angle in the model are squared up first, so their true length, width and thickness are shown. Use the arrows under the preview to page through; the PDF contains them all.
- **Pull apart slider** under the 3D view. Slide it to watch the model come apart along the same directions the build guide uses. You can still tap parts while it is apart.
- **Help** button on the project screen: a plain list of what you can trust (sizes, counts) and what you need to check (units, round features, tolerances, build order).

## v0.6.1 - Sturdier part splitting

Much sturdier part splitting for awkward files.

- **Files that store the whole model several times over** now split correctly whatever order the copies come in. Before, only back-to-back repeats of each body were recognised.
- **Bodies whose faces don't share edges properly** (a common export quirk) are kept whole instead of falling apart or being lumped together.
- **Parts that touch face to face**, like two boards stacked on each other, stay separate even when the file mixes their triangles together.
- **Triangles in any order**: a file no longer has to list one body after another for the app to tell the parts apart.

If a file of yours still comes out as one part, or as too many, tap **Copy diagnostic report** and send it with a note of how many parts it should have.

## v0.6.0 - Edit the build steps

Edit the build steps: the guide is now yours to correct.

- **Edit steps** on the Guide tab opens the build order. Move a whole step up or down with the arrows, or tap a part to move it to an earlier or later step, or give it a step of its own.
- **Wording** on each step lets you name the step ("Sides"), write your own instructions (one per line) and add a check ("Diagonals are equal"). Leave a box empty to keep the app's wording. This is where glue, screws and clamping go; the app never guesses those.
- **Automatic** puts the app's own order back.
- Your order and wording are saved with the project and used in the guide PDF and the exploded sheet of the drawing.
- When you set the order yourself, the app still works out which way each part slides in past the parts already there, so the arrows stay right.

Also fixed:
- Sculpted or organic models are no longer mislabelled as "designed in inches" by chance, no longer get pointless section cuts through solid material, and no longer pick up stray dimensions from tiny flat facets.

## v0.5.0 - Section views

Section views: when the inside of your model is hidden, the drawing cuts it open.

- **Automatic sections.** If the front or side view would mostly be hidden edges (a closed cabinet, a box with shelves inside), the app draws that view as a section instead: sliced through near the middle, with the cut material hatched and the shelves behind shown clearly. The view is labelled **SECTION A-A** (and **B-B** for the side).
- **Cutting-plane marks on the top view** show where each cut was taken and which way you are looking, with matching letters.
- **Touching parts are hatched at different angles** so you can tell them apart; very thin parts are filled solid.
- The cut is placed to avoid slicing along a face or through small pieces.
- Open models that already show their inside (like an open-front bookcase) are left as normal views. To turn sections off, open **Title block** and choose **Never** under Section views.

## v0.4.0 - Exploded view and build guide

Exploded view and a step-by-step build guide.

- **New Guide tab.** The app works out an order to build your model in and draws one picture per step: the new part in bold with an arrow showing which way it goes in, the parts already in place in grey, and a dashed line tracing it to its seat. Each step lists the parts to gather and what to do.
- **Share build guide (PDF)**: a cover with the finished object, its size and the materials list, then the steps, ready to print.
- **Second drawing sheet: exploded view.** The whole model pulled apart, with numbered balloons that match the list of material on sheet 1. Switch between sheets under the preview; the drawing PDF now has both.
- **The order makes physical sense**: parts that stand on the floor go first, and nothing is placed before the parts that hold it up.

Please read this before trusting it: the build order comes from the shapes alone. The app checks that each part can slide into place, but it knows nothing about glue, clamps, screws or what is easiest to hold. Treat the guide as a draft and read it through before you build. Editing the steps yourself is coming.

This version has not been tried on a real phone yet. If a tab stays blank or the app closes, tap **Copy diagnostic report** and send it over.

## v0.3.0 - Engineering drawing

The first engineering drawing: a real sheet with views, dimensions and a title block, exported as PDF.

- **New Drawing tab.** The app draws your model from the front, top and right side, lined up the way a drafter would, on a bordered sheet with zone marks.
- **Dimensions are added automatically**: overall width, depth and height, plus the main levels such as shelf heights, all measured from the lower-left-front corner, in the units you chose.
- **List of material on the sheet**, straight from the cut list, with item numbers, quantities, descriptions and the materials you entered.
- **Title block and revision block.** Tap **Title block** to fill in the title, drawing number, revision, names, date and extra notes. Tolerances stay "not specified" until you pick a preset; the app never chooses them for you.
- **Sheet size and scale are picked for you** (sizes A to E, standard scales such as 1/2, 1/4, 1/10), or pin a sheet size yourself.
- **Share drawing (PDF)** makes a sharp vector PDF you can print or send. Pinch to zoom the preview, double-tap to fit.
- If the engine had to simplify something (for example leaving out dashed hidden lines on a busy view), a **notes** button on the tab tells you.

Not here yet: section views for models whose inside is hidden, the exploded view and the build guide.

## v0.2.0 - 3D view

See your model in 3D, with every part in its own colour.

- **New 3D tab** on the project screen. Drag with one finger to turn the model, pinch to zoom, drag with two fingers to move it.
- **Tap a part to select it.** It lights up, and its name, item number and size appear underneath, with an Edit button to rename it or leave it out.
- **Identical parts share a colour**, so matching boards are easy to spot.
- **Front, Top, Right and 3D buttons** jump to standard views.

This version has not been tried on a real phone yet. If the 3D tab shows nothing or the app closes, tap **Copy diagnostic report** and send it over.

## v0.1.0 - Import, parts and cut list

First version. Open an STL of a whole assembly and see what it is made of.

- **Import an STL** with the Import button, or use "Open with" from your file app.
- **Parts are separated automatically.** If the file stored the same body more than once, the extras are removed and the app tells you how many.
- **Units are guessed and can be changed.** STL files don't record units; tap **Change** on the project screen if the size looks wrong, or to switch between millimetres, decimal inches and fractional inches.
- **Cut list.** Identical parts are grouped and counted, and described as panels, boards, rods or shaped pieces with their stock size. Tap an item to set its material. A "stock to buy" total shows the length needed per board size.
- **Share the cut list** as a CSV file that opens in any spreadsheet app.
- **Rename or leave out a part** by tapping it in the Parts tab.
- **Copy diagnostic report** gives you text to paste if something goes wrong.

Not here yet: the 3D view, drawings, exploded view and build guide. They are next.

