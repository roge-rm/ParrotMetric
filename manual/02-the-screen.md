# The screen
> Where things are, moving the view and picking things.

## Two layouts

ParrotMetric has a layout for phones and one for large screens. It picks one by the size of the window, or you can set it in Settings.

- On a phone, the tools are in five groups along the bottom: **Sketch**, **Create**, **Modify**, **Construct** and **Inspect**. Tap a group to see its tools. Tool settings open in a panel at the bottom.
- On a tablet, a computer or in a browser, every tool is in a toolbar along the top, the parts list is down the left and tool settings open on the right. Tools that come in a few kinds, like the planes, have a small arrow for the rest.

The bar at the top has the menu under the parrot, the design's name and how many bodies it has, the parts list, and undo and redo.

## Moving the view

- One finger turns the view.
- Two fingers move it, and pinching zooms.
- Double tap fits everything in view, and so does the button under the cube.
- The cube in the corner shows which way you're looking. Tap one of its faces to look straight at that side.

With a mouse, the middle button moves the view, and right drag or Shift and middle drag turns it. The wheel zooms towards the pointer, and a double middle click fits everything.

## Picking things

Tap a face, an edge or a corner to pick it, and tap it again to drop it. Tap empty space to clear what's picked. Hold on an edge to pick it and the edges running on smoothly from it, such as all of a rounded outline. Inside a finished sketch, tap an area to pick it, which is what Extrude and other tools use.

With a mouse, a click picks just that thing and Shift or Ctrl and a click adds to what's picked. A double-click on an edge picks it and the edges running on smoothly from it, such as all of a rounded outline. Drag on empty space to pick everything in a box: dragging to the right picks what's fully inside it, and dragging to the left also picks what it touches. A right-click opens a menu with **Repeat** for the last tool, the tools that suit what's picked, **Hide**, **Fit the view** and **Clear selection**.

## The parts list

The layers button in the top bar opens the parts list on a phone. On a large screen it's on the left. It has every body in the design, grouped by component, and the construction planes.

- The eye shows or hides a body.
- The dot sets its colour, which also goes into 3MF files.
- The menu beside it has **Rename…** and puts the body into a component or takes it out (see Components and joints).

Under **Planes** are the construction planes, each with an eye to hide it once you've drawn on it.

## The history

The chips along the bottom are the steps that make the design, oldest on the left. The orange bar is where the history ends right now. When their names don't all fit, the chips show just their icons, over up to three rows on a large screen and in one row that scrolls on a phone; hover or long press to see a step's name.

- Tap a chip to open that step's settings again.
- A long press on a chip (or a right-click) gives **Edit**, **Roll back to here**, **Turn off** and **Delete**. A sketch's also has **Move to picked face**: pick a plane or a flat face first, and the sketch moves onto it with what's drawn in it.

**Roll back to here** moves the end of the history back to that step, so the steps after it stop for now. Anything you add then goes in at that point. **Roll forward to here** brings them back.

**Turn off** keeps a step but leaves it out, to see the part without it.

A step that can't be built turns red and says why, and the steps after it still try. Usually it's because an edge or face it used isn't there any more, and opening it to pick again fixes it.

## Tool panels

A tool's panel has its choices along the top and its sizes below, with **Done** and **Cancel**. Most tools show a preview that updates as you change things. Fillet, Chamfer and the tools for faces show the body without the change while their panel is open, so you can keep picking edges and faces on it.

Any size can be a sum, like `20+5` or `wall*2`, using your parameters (see Parameters and configurations). Sizes are in millimetres and angles in degrees.

Most tools that make or change a shape ask what to do with it:

- **New body** keeps it as a body of its own.
- **Join** adds it to the bodies it touches.
- **Cut** takes it away from them.
- **Intersect** keeps only where they overlap.
