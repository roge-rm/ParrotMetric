# Sculpting
> Pushing, pulling and smoothing a shape like clay, with brushes.

Sculpting works on a mesh the way you'd work clay: push it in, pull it out, smooth it, pinch a crease. It suits figures, faces, creatures and anything organic that's hard to draw with sketches.

## Starting

**Sculpt** is under **Create**, or press K:

- With a face of a body picked, it sculpts that body. A solid is turned into even triangles first.
- With nothing picked, it starts from a ball 50 mm across. **Sculpt a ball** and **Sculpt a block** start from those whatever is picked.

**Done** puts what you've sculpted into the history as a Sculpt step, as a mesh body, with its mask. Tap the step later to sculpt it more. The cross at the top left leaves without keeping it.

A Sculpt step remembers its strokes. If you change a step before it, say the size of the box it was sculpted from, the strokes are made again on the changed body, where they fall on the screen as you made them. That takes a moment for a lot of strokes. While the body it came from is unchanged, the step just keeps its finished mesh.

## Strokes

On the model, a finger, a pen or the left mouse button makes a stroke. Off the model, it turns the view. Two fingers move the view and pinch to zoom; with a mouse, the right button turns it, the middle one moves it and the wheel zooms.

- Hold Shift to smooth with any brush, and Ctrl to do the opposite of the brush. A pen's eraser end does the opposite too.
- **Size** is the brush's size on the screen, so zooming in works finer. **[** and **]** change it.
- **Strength** is how much each stroke does. Each brush keeps its own.
- **Invert** does the opposite: Draw pushes in, Clay digs, Mask rubs off.
- **Mirror** **X**, **Y** and **Z** repeat each stroke across the planes through the middle of what you're sculpting, as it was when you started. A ball or block starts at the origin. Seen from the front, X makes the left and right sides the same.

## The brushes

- **Draw** pushes the surface out, or in with Invert.
- **Clay** builds up flat layers, like adding clay.
- **Crease** cuts a sharp groove and pinches its sides together; inverted, a sharp ridge.
- **Smooth** evens out bumps.
- **Flatten** presses it towards a flat plane.
- **Inflate** swells it out all round, like blowing it up.
- **Pinch** pulls it in towards the brush's middle, sharpening edges.
- **Grab** drags what was under the brush when the stroke began.
- **Pull** drags the surface along with the brush, stretching it into horns, limbs and tails. With **Detail** on it keeps adding triangles as it goes.
- **Layer** raises it to a set height and no further in one stroke, for even plates and scales.
- **Mask** paints a mask, shown blue-grey, that the other brushes leave alone. **More** has **Clear** and **Invert** for it.

## Detail

With **Detail** on, triangles are added under the brush where it needs them, so the surface stays smooth wherever you work. How fine is under **More**, from **Coarse** to **Fine**, and it goes with the brush's size: a small brush adds finer detail.

**More** also has, for the whole mesh: **Finer all over**, which about doubles the triangles, **Coarser**, which about halves them, and **Even**, which evens them out at the size they are. The count is under the title.

How many triangles it can take depends on the device: about half a million on a slow tablet, more on a fast one.

## More

- **Look**, under **More**, shows it as clay, stone, porcelain or terracotta, whichever shows the shape best to you.
- **Show the triangles** draws them over it (Shift+W), to see how fine it is where.
- **Steady lines** makes the brush trail behind the pointer on a string, so strokes come out smooth. Good for long lines with a finger or a mouse.
- **Pen pressure** can change the strength, the size or both.
- Undo and redo go back a stroke at a time.

What's being sculpted is kept if the app goes into the background or closes, as if you'd pressed Done.

A sculpted body is a mesh, so the mesh tools work on it afterwards, and it exports to STL or 3MF for printing like any other.
