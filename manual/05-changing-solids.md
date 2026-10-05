# Changing solids
> Rounding, hollowing, holes, patterns and the other Modify tools.

These are under **Modify**. Pick what the tool works on first, or start it and pick while its panel is open.

## Edges

- **Fillet** rounds edges. **Radius** is the same all along, **Start to end** goes from one radius to another along the edge, and **Across** sets the width of the round instead of its radius.
- **Chamfer** cuts edges off at an angle. **Equal** is the same distance on both faces, **Two distances** a different one on each, and **With angle** a distance and an angle. **Swap sides** swaps which face gets which.

Edges that join smoothly are taken together, so one tap can pick the whole way round a rounded corner.

## Faces

- **Shell** hollows the body out to the wall thickness you give. The faces you pick are left open, and with none it's hollow inside.
- **Press pull** moves a face in or out, and the faces next to it follow.
- **Delete face** takes faces away and closes the gap, for getting rid of a round or a small feature.
- **Draft** tilts faces by an angle, for parts that need to come out of a mould. Pick the faces to tilt, then switch to **Pivot face** and pick the face they turn about.

## Holes and threads

- **Hole** puts a hole at each point in a sketch on a face. It's **Simple**, **Counterbore** (a wider, flat bottomed hole at the top for a bolt head) or **Countersink** (a cone at the top for a flat head screw). Set the **Diameter**, and the **Depth** or **All the way through**. For a screw in a printed part, pick **Insert** for a pocket that takes a heat-set insert, or **Self-tap** for a hole the screw cuts its own thread in, then the screw size, M2 to M5. Insert also sets the depth to fit a standard insert. Typing a diameter goes back to **Any size**.
- **Thread** cuts a modelled ISO metric thread into a round face, a hole or a shaft, with the **Pitch** you give.

## Ribs and emboss

- **Rib** grows a wall from an open line in a sketch until it meets the body, like a support inside a box. **Web** is the same but lies flat in the sketch's plane. Set the **Thickness**, and **Grow the other way** if it goes the wrong way.
- **Emboss** raises or sinks sketch areas into a face, flat or curved. Pick the areas and the face, choose **Raised** or **Sunk** and the depth. Text on a round part follows the curve. With no areas picked it uses all of the newest sketch, leaving the middles of letters open, which suits text drawn through the middle of a part where you can't tap it.

## Mirror and pattern

- **Mirror** copies across a plane or a flat face.
- **Pattern** makes copies **In a row** (and a second row for a grid), **Round an axis**, or **Along a path**.

Both work on **Bodies**, or on **Features**: the steps that made something, like a hole and its fillet, which are done again at each copy. Copies of bodies can be joined to the original with **Join to the original**.

Along a path, the copies are spread evenly along it or **Spaced** a set distance apart. **Turn with the path** turns each one to follow the path, and **Start from the other end** starts from the path's other end.

## Bodies

- **Combine** joins, cuts or intersects bodies with each other. Pick the body to keep first. **Keep the others** keeps the bodies used as tools.
- **Split** cuts a body in two **By a plane** or **By a body**, keeping both pieces as bodies or only the side you want. It also trims surfaces.
- **Move** moves and turns bodies by set distances and an angle. **Move a copy** leaves the original where it was.
- **Scale** makes bodies bigger or smaller, the same every way or by a different amount along each axis.
- **Align** moves a body so the face you picked meets another face or plane, with a **Gap**, and can **Line up the middles** and make them **Face the same way**.
