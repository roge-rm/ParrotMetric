# Sketching
> Drawing shapes, and setting their sizes and how they relate.

A sketch is a flat drawing on a plane. The closed areas in it are what Extrude, Revolve and the other tools turn into solids.

## Starting a sketch

- **Sketch** then **Top**, **Front** or **Right** draws on one of the three main planes.
- To draw on a flat face of a body, pick the face and tap **On selected** (the pencil). It works the same on a construction plane, and on a flat area of a mesh.

The view turns to look straight at the plane. The fit button at the top brings the whole sketch into view, and undo and redo there work inside the sketch. **Finish** ends the sketch.

## Drawing

The tools are along the bottom: **Select**, **Line**, **Rectangle**, **Circle** and **Arc**, and **More** has **Point**, **Spline**, **Polygon**, **Slot**, **Ellipse**, **Conic** and **Text**. On a large screen they're all in one row.

Tap to place each point. Points snap to the ends and middles of what's already there, and lines snap to level and upright, and the matching constraints are added as you draw.

- **Line** carries on from the last point until you tap the first point again, which closes the shape, or tap **Line** again to stop.
- **Rectangle** is corner to corner, from the centre, or three points for one at an angle.
- **Circle** is a centre and a point on it, two points across it, or three points on it.
- **Arc** is a centre then its ends, three points along it, or tangent, which carries on smoothly from the end of a line or arc.
- **Polygon** has as many sides as you set, with its corners on the circle you draw or its sides touching it.
- **Slot** is centre to centre, end to end, or from the middle.
- **Spline** goes through the points you tap, or is pulled by them as control points. Tap **Spline** again to end it.
- **Ellipse** is a centre, then the end of one axis, then the width.
- **Conic** is two ends and a point the curve leans towards. Pick one and **Fullness** sets how full it is, between 0 and 1.
- **Text** asks for the words, the height and whether it's bold. Its letters are areas like any other, so they extrude. **Change text** edits it later.

The styles of each tool show above the tools while it's in hand.

As soon as a shape is placed, its sizes show at the bottom as you drew them: a rectangle's width and height, a circle's diameter, an arc's radius, a line's length, and so on. Type the sizes you want and tap **Set**, and they become dimensions. To leave it free, just carry on drawing.

**Construction** makes new curves construction curves, drawn dashed. They help you place things but don't make areas. With curves picked, it switches those instead.

## Dimensions

Tap **Dimension**, then tap what to measure:

- a line for its length
- a circle for its diameter, or an arc for its radius
- two points, or a point and a line, for the distance between them
- two lines for the angle between them

Type the size and tap **Set**. A size can use your parameters, like `width/2`. Tap a dimension later to change it.

## Constraints

Constraints keep things in place relative to each other. Pick the things it's about and **Constrain** shows what fits them. On a large screen they show straight away in the row above the tools.

The constraints are:

- **Coincident**, **Horizontal**, **Vertical**, **Parallel**, **Perpendicular**, **Tangent** and **Equal**
- **Fix**, which holds a point where it is
- **Midpoint**, **Symmetric**, **Concentric** and **Collinear**

The count at the top shows how much in the sketch can still move. When it says **Fully set**, everything is held by a dimension or a constraint, and the curves change colour to show it.

Constraints show as small marks beside what they hold. Tap one to pick it, and **Delete** takes it off. If a new one can't fit with the rest, the sketch says so and leaves it out.

## Changing what's drawn

With **Select** in hand, drag points or curves to move them. Tap a curve twice quickly to pick the whole outline it's part of. The row above the tools has these, some only once the right things are picked:

- **Trim** takes away the piece of a curve you tap, up to where it crosses other curves.
- **Extend** stretches the line end you tap to the next curve it would meet.
- **Break** cuts a curve in two where you tap.
- **Offset** makes a copy of the picked curves a set distance away.
- **Round corner** and **Cut corner** round off or cut a corner, with the corner's point picked.
- **Mirror** copies the picked curves across a line. Pick the line last. They stay mirrored when you change them.
- **Move** and **Scale** move or resize the picked curves.
- **Pattern** and **Pattern round** copy them in a row or round a point.
- **Delete** takes away what's picked.

## Bringing things in

- **Project** brings a face's edges into a sketch drawn on that face, or where the bodies cross the sketch's plane, as fixed curves to draw against.
- **Add drawing** brings in an SVG or DXF file's curves, at their size in the file in millimetres.
- A picture to trace over goes in with **Canvas**, under **Construct**.
