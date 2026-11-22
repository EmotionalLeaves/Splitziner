# Changelog

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

