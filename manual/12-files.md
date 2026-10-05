# Files
> Saving, opening, exporting, and keeping designs in sync.

## Saving

The design you're working on is saved as you go: every 30 seconds while there are changes, and whenever the app goes to the background or closes. **Continue** on the start screen picks it up again.

To keep a design under a name, use **Save as…** in the menu under the parrot. After that **Save** writes to the same file. With a projects folder, designs are saved there without asking (see below).

ParrotMetric's own files end in `.pmet` and keep the whole history, so everything can still be changed when you open one again.

In a browser, **Save as…** downloads the file.

## Opening

**Open…** takes:

- a `.pmet` design, which replaces the one that's open
- an STL, 3MF or OBJ mesh, which is added as a mesh body (see Meshes)
- a STEP or IGES file, which comes in as solids you can keep working on, as one step with no history of its own
- a PNG or JPEG picture, which goes on a plane as a canvas to trace

From the start screen, a mesh, a STEP file or a picture starts a new design.

## Exporting

**Export…** in the menu saves the shown bodies for printing or for other programs:

- **STL**, **3MF** and **OBJ** are meshes for slicers. 3MF keeps each body's colour. Choose **Fine**, **Medium** or **Coarse**, which is how closely the triangles follow curved faces.
- **STEP** and **IGES** keep the exact shapes, for other CAD programs.

It exports **All shown bodies** together, or tap bodies or components in the list to export just those.

## Keeping designs in sync

In Settings, **Projects folder** lets you keep your designs in one place so they're the same on every device.

- **Choose folder…** picks a folder on the device. Use one that a sync app looks after, such as Nextcloud, Syncthing or Dropbox.
- **WebDAV server…** uses a folder on a WebDAV server, such as Nextcloud, directly. Enter the folder's address, your user name and password, and tap **Connect**.

For Nextcloud, the address looks like `https://your.server/remote.php/dav/files/USERNAME/ParrotMetric/`. Make the folder first, and use an app password from Nextcloud's security settings.

With a projects folder, the start screen lists the designs in it, newest first, and tapping one opens it. New designs are saved there as you work, named after the design.

When you come back to the app, it loads a newer copy if another device has changed the design you have open, as long as you haven't changed it here too. If it was changed in both places, both are kept: yours is saved beside the other with the device's name added, like `Box (Pixel 5).pmet`.

The browser version can only use a server that allows requests from other sites (CORS), which most don't unless set up for it.

**Stop using** goes back to no projects folder. The designs stay where they are.
