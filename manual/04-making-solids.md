# Making solids
> Extrude, revolve, sweep, loft, pipe, coil and the ready-made shapes.

These are under **Create**. Most start from sketch areas: pick the areas, then the tool. You can also start the tool and tap areas while its panel is open.

## Extrude

Pushes areas straight out of their plane. Tap the areas first, or with none tapped it takes the newest sketch when it has only one. When that sketch has more, **Take all** takes every one of its areas.

- **Distance** goes a set distance, **Through all** goes through everything in the way, and **Up to** stops at a face or plane you pick.
- With a distance, it goes **One side**, **Both ways** (the same each way) or **Two sides** (a different distance each way). **The other way** turns a one-sided extrude round, the same as a distance below 0. With the other two, pick **Forward**, **Back** or **Both ways**.
- **Through all** starts as **Cut**, since it goes through bodies.
- **Taper** leans the sides in or out by an angle.
- **Start at** starts it a distance away from the sketch's plane.
- **Thin wall** makes a wall of a set thickness round the outline instead of a solid. A sketch of just an open line, like the shape of a clip, extrudes as a wall that thick along the line, with round ends.

## Revolve

Turns areas round an axis: the sketch's **Y axis** or **X axis**, or a line in the sketch. **Angle** is how far it goes, 360 for all the way round. **As a surface**, here and in Extrude, makes the sketch's lines a surface instead (see Surfaces).

## Sweep

Moves areas along a path: a sketch's curves, or edges you pick. Pick the areas, start **Sweep**, then choose the path under **Along**.

## Loft

Joins areas in different sketches into one shape, in the order you pick them. Each sketch is usually on a plane above the last. **Straight between them** joins them with flat sides instead of a smooth curve through all of them.

- **Twist** turns the areas round, the last by the angle given and those between in step, for twisted vases and columns.
- **Follow a guide** makes the sides run along a line from a sketch, or edges picked in the view. The line should start on the first area and end on the last.

## Pipe

A round pipe along a path, with its **Diameter**. **Hollow** makes it a tube with the inside diameter you give.

## Coil

A spring or a spiral, with no sketch needed. Set the plane it stands on, the **Diameter**, the **Pitch** (the rise for each turn), the number of **Turns**, and the **Wire** size, round or square.

## Shapes

**Box**, **Cylinder**, **Sphere**, **Torus** and **Cone** make a shape from sizes, without a sketch. Pick the plane it sits on, its sizes and where its centre is. It sits on the side the plane faces, and **Grow the other way** puts it on the other side.

## Gear

A gear, under **Shapes**, with no sketch needed. Set the plane it stands on and:

- **Module**, the size of the teeth: the pitch circle, where it meets another gear, is the module times the teeth across. Gears that mesh have the same module.
- **Teeth** and **Thickness**.
- **Straight**, **Helical** or **Herringbone** teeth, with the **Helix angle** for the last two. Herringbone teeth go one way then back, so the gears don't push each other sideways.
- **Pressure angle**, 20° unless you're matching a gear made otherwise.
- **Bore**, a hole through the middle for the shaft, 0 for none.
- **Clearance** takes a little off each tooth so printed gears don't bind. 0.1 to 0.2 mm suits most printers.

**Beside** puts it next to a gear made before it, turned so their teeth mesh, with the same module and the helix the other way. **Round from it** sets where round that gear it goes. It follows that gear when you change it. The panel shows how far apart their centres are.

## New body, join, cut or intersect

Each of these asks what to do with the shape (from a sketch on a body's face it starts as Join): keep it as a **New body**, **Join** it to the bodies it touches, **Cut** it out of them, or **Intersect** to keep only where they overlap. A cut extrude is how you make most holes and pockets. Under **Changes** you can pick which bodies it changes, so a port cut through the case wall leaves the board's socket alone. **Any it reaches** is the default.
