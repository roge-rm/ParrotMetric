# A first part
> A plate with a hole and rounded corners, from start to finish.

ParrotMetric builds a part as a list of steps. You draw a flat sketch, turn it into a solid, then change the solid with more steps. Every step is kept, so you can go back and change any of them later and the rest rebuild.

The manual says tap. On a computer a click is a tap, and a right-click opens a menu where a phone would use a long press.

## Starting

On the start screen, tap **New design**. The model screen opens with nothing in it yet.

## A sketch

1. Tap **Sketch**, then **Top**. The view turns to look straight down at the top plane.
2. Tap **Rectangle**, then tap two corners. Its width and height show at the bottom.
3. Type `60` for the width and `40` for the height, and tap **Set**.
4. Tap **Circle**, then tap a centre inside the rectangle and a point for its size. Type `8` for the diameter and tap **Set**.
5. Tap **Finish**.

The count at the top of a sketch shows how much is still free to move. It doesn't have to be fully set, but a sketch that is stays the way you meant it when you change a size.

## A solid

1. Tap inside the rectangle, outside the circle. The area lights up.
2. Tap **Create**, then **Extrude**. A preview shows.
3. Type `5` for the distance and tap **Done**.

You now have a plate with a hole through it.

## Rounding the corners

1. Tap the four short upright edges at the corners. Each one lights up as you tap it. Turn the view to reach the one at the back. With a mouse, hold Shift as you click to add each one.
2. Tap **Modify**, then **Fillet**.
3. Type `4` for the radius and tap **Done**.

## Changing your mind

Each step is a chip along the bottom: the sketch, the extrude and the fillet. Tap the sketch's chip to open it again, tap the 60 and change it to 80, and tap **Finish**. The extrude and the fillet rebuild on the longer plate.

## Saving and printing

Your design is saved as you go, and comes back next time with **Continue**. To keep it under a name, use **Save as…** in the menu under the parrot in the top corner. To print it, use **Export…** in the same menu and pick STL or 3MF.
