# Missing features

What's left from the feature review after 0.6.0, for an agent to work through. Do them in the order below, one commit each. Stop and ask before anything marked **Ask first**.

## How the code is laid out

- `core/`: C++ (OCCT for solids, Manifold for meshes). Operations in `core/src/model/operations.{h,cpp}`, meshes in `core/src/mesh/`, tests in `core/tests/` (Catch2).
- `app/src/main/cpp/jni.cpp`: the bridge for Android and desktop. Each native call is also declared in `shared/src/commonMain/kotlin/com/rm/parrotmetric/app/NativeCore.kt` and `shared/src/jvmShared/kotlin/com/rm/parrotmetric/Core.kt`. Add new calls at the end of NativeCore, then run `python3 web/core/gen_bridge.py` to regenerate the browser's half.
- `model/`: the design model, no UI. Features in `design/*Features.kt`, building in `design/Rebuild.kt` (through the `Kernel` interface, with a `FakeKernel` in `model/src/jvmTest/.../RebuildTest.kt`), saving in `io/DesignFile.kt`, number fields in `design/Parameters.kt`.
- `shared/`: Compose UI. `ui/design/DesignEditor.kt` (drafts for each tool's panel), `ui/design/FeaturePanels.kt` (the panels), `ui/Tools.kt` (toolbar), `ui/Icons.kt`, `ui/design/Sheets.kt`.
- `manual/*.md`: the user manual. After changing it, run `python3 tools/gen_manual.py` to regenerate `ui/Manual.kt`.

## Building and testing

- Core tests: `cmake -S core -B core/build/host -DCMAKE_BUILD_TYPE=Release && cmake --build core/build/host --target pmcore_tests -j 8 && core/build/host/pmcore_tests`. The first build compiles OCCT and takes a long time.
- Kotlin tests: `./gradlew :model:jvmTest :shared:desktopTest`.
- Desktop compile: `./gradlew :shared:compileKotlinDesktop`.
- Each feature needs a core test if it touches the core, a model test (rebuild with the FakeKernel, and a save-and-read round trip in `DesignFileTest`), and a manual update.

## Rules for the text

- Never name other CAD programs anywhere in the repo, commits or app text.
- Plain, short, everyday words. No em dashes. Canadian spelling (colour, centre, behaviour), but never change identifiers.
- Panel controls get a clear title and no help text under them; a note under a control is only for a warning or a live readout.
- Code comments: a line or two on what the code does, with a reason only where it isn't obvious. No history.
- Commit messages: a short plain title, then a blank line and the attribution lines the session gives.

## The features, in order

Done so far: joint limits, Planar and Ball joints and geared joints; parameters to and from CSV; Isolate; named views; draft analysis; see-through bodies.

### 1. Joint limits and more joint kinds

`JointFeature` (`design/ModifyFeatures.kt`) has Rigid, Turn, Slide and TurnSlide.

- Add **limits**: a lowest and highest value for turning and sliding, so the slider stops there. Keep it in the feature, saved in the file.
- Add **Planar** (slides in two directions on a face and turns about its normal) and **Ball** (turns about a point, three angles). Pin-slot is TurnSlide along an edge, so check that's covered before adding it.
- Add a **Gear link** between two Turn joints: turning one turns the other by a ratio (teeth over teeth when both are gears made with the Gear tool, see `GearFeature`).
- Tests: the matrices for each kind in `RebuildTest`, limits clamping, the link moving the second joint.

### 2. Draft analysis

A check view like Print check and Surface check (`ui/design/Sheets.kt`, renderer analysis modes in `core/src/render/renderer.{h,cpp}`). It colours each face by the angle between its normal and a pull direction: green where it has at least the draft angle given, red where it's under it, another colour for faces square to the pull. The pull direction is a plane or flat face picked, Top by default.

### 3. Surface extrude and revolve, offset surface

- Extrude and revolve of an open sketch line as a surface, not a thin wall (there is already a thin-wall extrude of open lines; see `openWall` in `operations.cpp`). Surface bodies already exist (Patch, Stitch, Thicken).
- **Offset surface**: a copy of a face or surface moved out by a distance (`BRepOffsetAPI_MakeOffsetShape` on a shell).
- **Replace face**: a body's face swapped for a surface it's extended to meet. **Ask first** if it gets complicated; it's the least used.

### 4. Parameters to and from CSV

In the Parameters sheet: **Save as CSV** (name, expression, value) and **Read CSV**, which sets parameters with matching names and adds new ones. Use the existing `saveFile` and file-open actions. Test the parsing in the model.

### 5. Named views, isolate and see-through

First check what's already there; some may exist. Then:
- **Named views**: keep the camera under a name, listed in a menu, saved with the design.
- **Isolate**: show only the picked bodies until turned off.
- **See-through**: a body drawn see-through, a toggle in its menu in the Parts panel (`DisplayMesh::faceColour` alpha below 1 already draws see-through).

### 6. Materials and looks

Bodies have colours. Add a few looks (matte, glossy, metal) to the colour menu, drawn by the renderer like the sculpting looks (`Renderer::setSculptLook`). Saved with the body's info in the design. Keep it light: no textures.

### 7. Scripting or custom features

**Ask first.** This is large. A possible start is a step whose sizes come from a small expression list, building on configurations and parameters, rather than a scripting language.

## Not doing, or not without asking

- **Parasolid and DWG**: closed formats with no free reader that fits. Not doing.
- **macOS and iOS builds**: need Apple hardware, signing and accounts. **Ask first.**
- **Sharing links**: need a server and an account, against working offline. Not doing.
- Simulation, CAM, rendering, sheet metal, T-splines, real-time collaboration, AI design: not doing.
