# Changelog

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

