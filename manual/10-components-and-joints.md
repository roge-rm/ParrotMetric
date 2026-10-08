# Components and joints
> Grouping bodies into parts, designs made of other designs, and parts that move.

## Components

A component is a group of bodies that belong together, like the lid of a box. In the parts list, the menu beside a body has **Into a new component…** and **Into** each component there is already, and **Out of** to take it out again. The parts list shows bodies under their component, and Export can pick a whole component.

The eye beside a component's name shows or hides all its bodies at once. Its menu has **Put new bodies here**: until you turn it off again, every body a step makes goes into that component. It lasts until you close the design. To start one before it has any bodies, tap **New component…** under the list and name it: new bodies go into it from then on.

## Designs made of other designs

**Insert a design**, under **Create** with **Import**, builds another of your designs into this one as a component, named after its file. It lists the designs in your projects folder, and **From a file…** takes one from anywhere. You can insert the same one more than once.

A design keeps a copy of each one it has in it, so it still opens anywhere. When you open it from the projects folder, the copies are brought up to date from their files, and **Update inserted ones** does it while it's open. Change the box's own design and every design with that box in it follows.

Only the bodies shown in the inserted design come in. Its step's **Move x**, **Move y** and **Move z** move it; to put it in place against another part, use a joint.

## Bill of materials

**Bill of materials**, under **Inspect**, lists the parts: each component, each body not in one, and the screws, nuts and washers made with Fastener, with how many there are, their size and volume. Copies of the same inserted design count as one line. **Copy the list** copies it as text for a spreadsheet.

**Explode** below it draws the components apart, out from the middle of them all, to see how they go together. It only changes the view.

## Joints

A joint says how one component sits on another and how it can move.

1. Start **Joint** under **Modify**.
2. Under **Moving**, pick the component that moves, and under **Joined to** the one it's held to, or **In place** to hold it where it is.
3. Pick the kind: **Rigid** holds it fixed, **Turn** turns about an axis, **Slide** slides along one, **Both** does both, **Planar** slides across a flat face and turns on it, **Ball** turns any way about a point, and **Slot** is a pin in a slot: it turns about the pin and slides along the slot.
4. For turning and sliding, pick an edge or round face for the axis, or **Use an axis** for an axis you made. For **Planar**, pick the flat face it slides on. For **Ball**, pick a ball, a round edge or a flat face for its centre; with nothing picked it turns about the origin.
5. The slider and the number at the top move it. **Planar** has two slides, **Ball** a turn round x, y and z, and **Slot** a slide and the **Slot angle**, the way the slot runs across the pin. Tap **Done**.

To move it again later, open the joint's step and use the slider. Rigid joints keep components together when you move the one they're held to.

For a pin in a slot, use **Slot** round the pin, set the **Slot angle** to the way the slot runs, and give it a slide limit to the slot's length.

### Limits

Turn on **Turn limits** or **Slide limits** and give the lowest and highest it can go. The slider then stops there, and a number past them is held at the limit.

### Gears

A **Turn** joint can be geared to an earlier Turn joint: under **Geared to**, pick the one it follows. Turning that one then turns this one too. With **Ratio from the teeth** on, and both components holding a gear made with **Gear**, it turns by the teeth of the other gear over the teeth of its own, the other way round, as meshed gears do. Turn it off to give your own **Ratio**; a negative ratio turns it the other way. The joint's own turn is added on, which helps line the teeth up.
