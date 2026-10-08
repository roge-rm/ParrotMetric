# Changing solids
> Rounding, hollowing, holes, patterns and the other Modify tools.

These are under **Modify**. Pick what the tool works on first, or start it and pick while its panel is open.

## Edges

- **Fillet** rounds edges. **Radius** is the same all along, **Start to end** goes from one radius to another along the edge, and **Across** sets the width of the round instead of its radius.
- **Chamfer** cuts edges off at an angle. **Equal** is the same distance on both faces, **Two distances** a different one on each, and **With angle** a distance and an angle. **Swap sides** swaps which face gets which.

Edges that join smoothly are taken together, so one tap can pick the whole way round a rounded corner.

## Faces

- **Shell** hollows the body out to the wall thickness you give. The faces you pick are left open, and with none it's hollow inside.
- **Lip** stands a lip round the inside of an opening, on the top of its wall: tap the top face, then set the **Width** and **Height**. Under **Groove in**, pick the lid and it gets a groove the lip fits into, with the **Gap** you give all round (0.2 mm to start). The lid should sit on the top of the wall.
- **Snap fit** puts a clip at each point in a sketch on a face, such as points along the inside of the walls on a lid's underside. Each is a beam standing out of the face with a hook at its end, pointing away from the middle of the part, so it catches the wall. Set its **Length**, **Width** and **Thickness**, how far the hook sticks out (**Overhang**) and how long it is (**Hook**). Under **Catch in**, pick the other part and it gets a recess for each hook, with the **Gap** you give.
- **Press pull** moves a face in or out, and the faces next to it follow. A distance above 0 adds to the body, which makes a hole smaller. The panel says which way it goes.
- **Delete face** takes faces away and closes the gap, for getting rid of a round or a small feature.
- **Replace face** moves faces onto another face or a surface, growing the body out to it or cutting it back, for a top that follows a curve. Tap the faces, then **Surface to meet** and the surface. It goes straight out from each face, so the surface has to be over all of it.
- **Draft** tilts faces by an angle, for parts that need to come out of a mould. Pick the faces to tilt, then switch to **Pivot face** and pick the face they turn about.

## Holes and threads

- **Hole** puts a hole at each point in a sketch on a face, and at the corners and centres of its construction lines and circles, the origin too when a construction circle is round it. It's **Simple**, **Counterbore** (a wider, flat bottomed hole at the top for a bolt head) or **Countersink** (a cone at the top for a flat head screw). Set the **Diameter**, and the **Depth** or **All the way through**. For a screw in a printed part, pick **Insert** for a pocket that takes a heat-set insert, **Self-tap** for a hole the screw cuts its own thread in, or **Clearance** for a hole it passes through, then the screw size, M2 to M5. Insert also sets the depth to fit a standard insert, and Clearance with Countersink or Counterbore makes room for the screw's head. Typing a diameter goes back to **Any size**. **Changes** picks which bodies it cuts into. A new hole starts the same as the last one made.
- **Thread** cuts a modelled thread into a round face, a hole or a shaft, with the **Pitch** you give, or the pitch of the size picked: **Metric**, M2 to M64, or **Inch**, UNC #2-56 to 1-8. **Clearance** makes a shaft smaller or a hole bigger by that much first, so printed threads screw together (0.2 to 0.4 mm on each part). Make a hole for a thread at the nut size first: the panel shows it for a shaft, and for a hole the bolt it takes. A hole's thread sits half a turn round from a shaft's, so a lid and its jar mesh where they stand. **Only draw it** leaves the face as it is and draws the thread on it as a fine spiral, for parts that aren't printed with their threads, and it's much quicker.
- **Fastener** puts a standard **Socket cap**, **Hex bolt** or **Countersunk** screw, a **Nut** or a **Washer** in a hole: tap the hole's round side, or a countersink's cone so the head sits flush. It picks the biggest size that goes through and makes a screw as long as the hole is deep. It sits at the hole's top end, or with **From the other end** at the bottom, where a nut goes. With no hole tapped it stands on a plane where you set it. Sizes are **Metric**, M2 to M12, or **Inch**, #4-40 to 1/2-13. Its thread is only drawn unless you turn on **Model the thread**. With **Cut** and a little **Clearance** it cuts a pocket it fits in, such as a nut trap.

## Ribs and emboss

- **Rib** grows a wall from an open line in a sketch until it meets the body, like a support inside a box. **Web** is the same but lies flat in the sketch's plane. Set the **Thickness**. It grows towards the body by itself; when both sides reach the body, **Grow the other way** picks the other one.
- **Emboss** raises or sinks sketch areas into a face, flat or curved. Pick the areas and the face, choose **Raised** or **Sunk** and the depth. Text on a round part follows the curve. With no areas picked it uses all of the newest sketch, leaving the middles of letters open, which suits text drawn through the middle of a part where you can't tap it.

## Mirror and pattern

- **Mirror** copies across a plane or a flat face.
- **Pattern** makes copies **In a row** (and a second row for a grid, with **Stagger the rows** for every other row half a step along), **Round an axis**, or **Along a path**.

Both work on **Bodies**, or on **Features**: the steps that made something, like a hole and its fillet, which are done again at each copy. A mirror or pattern of features can itself be picked, so a groove mirrored the other way round can be patterned round a knob for a diamond knurl. Copies of bodies can be joined to the original with **Join to the original**.

Along a path, the copies are spread evenly along it or **Spaced** a set distance apart. **Turn with the path** turns each one to follow the path, and **Start from the other end** starts from the path's other end.

## Bodies

Tools that work on whole bodies also list them by name in their panel, to pick one that's hidden or hard to tap.

- **Combine** joins, cuts or intersects bodies with each other. Tap the body to keep first, then each body to use on it (with a mouse, Shift-click those). **Keep the others** keeps the bodies used as tools.
- **Split** cuts a body in two **By a plane** or **By a body**, keeping both pieces as bodies or only the side you want. It also trims surfaces.
- **Move** moves and turns bodies by set distances and an angle. **Move a copy** leaves the original where it was.
- **Scale** makes bodies bigger or smaller, the same every way or by a different amount along each axis.
- **Align** moves a body so the face you picked meets another face or plane, with a **Gap**, and can **Line up the middles** and make them **Face the same way**.
