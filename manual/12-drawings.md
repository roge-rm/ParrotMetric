# Drawings
> A sheet of views with sizes and notes, to print or send as PDF, DXF or SVG.

A drawing shows the part flat, from the front, the top and the side, with its sizes written on. Each design has one. Open it with **Drawing** under **Inspect**, or Shift+W.

The first time, it lays out the front, top and right views with an isometric one in the corner, at the largest standard scale they fit on the sheet. The views follow the part: change the model and they're worked out again when you come back.

## Views

- Drag a view to move it. Views line up with each other when they come close, so the side views stay in line with the front one.
- Tap a view to pick it, then turn its hidden lines on or off, or delete it. Hidden lines are dashed; the isometric view starts without them.
- **Views** adds another view: the back, left or bottom, or one taken away.
- **Section**, with a view picked, adds a section of it: the part cut through its middle, looking the same way, with the cut faces hatched. It's lettered A, B and so on, and the line it's cut along shows on the views that see it edge on, with arrows the way it looks. **Cut at** moves where it's cut, in mm along the way the view looks.
- Only solid bodies are drawn, not meshes. Bodies hidden in the parts list are left out.
- Threads, knurls and other twisting shapes are worked out from their triangles, which is much quicker, so their curves come out as short lines.

## Dimensions

With **Dimension**:

- Tap two corners, ends or centres in a view to show the distance between them. The points they catch on show as dots. It goes across or up depending on how the points lie; after making it, **Across**, **Up** and **Along** change which.
- Tap the edge of a circle for its diameter, or of an arc for its radius. **Diameter** and **Radius** switch between them.
- A hole made with Hole or threaded with Thread gets a callout instead: how many there are, its size and whether it goes through or how deep, its counterbore or countersink, or its thread, such as M4×0.7. **Hole** turns a diameter into a callout and back.
- Drag a dimension to move it further from the part, or round the circle.

The numbers are the part's real sizes in mm, whatever the scale. A dimension across the part keeps to its outline, so if the part grows the dimension does too.

## Notes

With **Note**, tap where it goes and type it; more than one line is fine. Drag it to move it, and tap it twice or use **Change** to change it.

## The sheet

**Sheet** sets:

- the paper: A4, A3, A2, Letter or Tabloid, and landscape or portrait;
- the scale: from 10:1 to 1:100;
- **Third angle** (the top view above the front, as in North America) or **First angle** (the top view below);
- the title and who drew it, for the title block. The date is filled in.

**Lay out the views again** puts the standard views back in their places, fitted to the sheet. It takes away the dimensions, since their views move.

## Saving the drawing

The button at the top right saves the sheet:

- **PDF** to print or send. It prints at its true size: at 1:1, a 40 mm part measures 40 mm on the paper.
- **DXF** for laser cutters and other drawing programs, with seen lines, hidden lines and notes on their own layers.
- **SVG** for the web or to edit.

The drawing is saved with the design, and undo works on it the same as on the model.
