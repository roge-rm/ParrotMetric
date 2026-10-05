# Meshes
> STL, 3MF and OBJ files, and what you can do with them.

A mesh is a shape made of triangles, like most files for 3D printing. ParrotMetric keeps one as a mesh body, which works with most of the tools for solids.

## Bringing one in

**Import** under **Create**, or **Open…** in the menu, with an STL, 3MF or OBJ file adds it to the design you're in as a mesh body. From the start screen it starts a new design. It's a step in the history like any other.

On the way in it's repaired: points that should be shared are joined, faces are turned the right way out and small holes are closed. If it still isn't closed up, it says so, since some tools need a closed mesh.

## Tools for meshes

With a mesh picked, **Modify** shows the tools that work on it:

- **Plane cut** cuts it with a plane and keeps one side or both.
- **Reduce** uses fewer triangles, within the **Allowed error** you give.
- **Remesh** makes the triangles even, none longer than the **Longest edge**.
- **Smooth** rounds it off over a number of **Steps**, keeping edges sharper than the angle in **Sharp over**.
- **To solid** turns a small mesh into a solid, joining flat triangles into faces, so Fillet and the other solid tools work on it. A big mesh gets slow, so reduce it first.
- **Hole**, **Mirror**, **Pattern**, **Combine**, **Split**, **Move**, **Scale**, **Align** and **Joint** work as they do for solids.

The panel shows how many triangles there are.

## Meshes and solids together

Combine and the join, cut and intersect choices work between a mesh and a solid. The result is a mesh.

To sketch on a mesh, pick a flat area of it and tap **On selected**. Then extrude, cut and so on as usual.
