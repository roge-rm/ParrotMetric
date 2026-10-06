// Times the core's heavy work on the device it runs on, one core and then
// all of them, and OCCT's meshing options. Build with -DPM_BENCH=ON, then on
// a phone or tablet:
//   adb push pmcore_bench /data/local/tmp && adb shell /data/local/tmp/pmcore_bench
// "pmcore_bench files" writes the test parts (plate.step, sphere.stl) instead.
#include <BRepAlgoAPI_Cut.hxx>
#include <BRepAlgoAPI_Fuse.hxx>
#include <BRepFilletAPI_MakeFillet.hxx>
#include <BRepPrimAPI_MakeBox.hxx>
#include <BRepPrimAPI_MakeCylinder.hxx>
#include <BRepPrimAPI_MakeSphere.hxx>
#include <BRepMesh_IncrementalMesh.hxx>
#include <BRepTools.hxx>
#include <BRep_Tool.hxx>
#include <Poly_Triangulation.hxx>
#include <TopExp_Explorer.hxx>
#include <TopTools_ListOfShape.hxx>
#include <TopoDS.hxx>
#include <gp_Ax2.hxx>

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <functional>
#include <thread>

#include "display/display_mesh.h"
#include "model/operations.h"
#include "io/exchange.h"
#include "mesh/mesh_body.h"
#include "mesh/repair.h"
#include "mesh/stl.h"
#include "parallel.h"
#include "solid/solid.h"
#include "solid/speed.h"

using Clock = std::chrono::steady_clock;

// Runs work a few times and gives the fastest, in ms.
static double best(int runs, const std::function<void()>& work) {
    double fastest = 1e30;
    for (int i = 0; i < runs; ++i) {
        auto t0 = Clock::now();
        work();
        fastest = std::min(fastest, std::chrono::duration<double, std::milli>(Clock::now() - t0).count());
    }
    return fastest;
}

// A plate with a grid of n by n holes: lots of faces, the kind of part people print.
static TopoDS_Shape plate(int n) {
    TopoDS_Shape out = BRepPrimAPI_MakeBox(10.0 * n + 10, 10.0 * n + 10, 6).Shape();
    TopTools_ListOfShape args, tools;
    args.Append(out);
    for (int i = 0; i < n; ++i)
        for (int j = 0; j < n; ++j)
            tools.Append(BRepPrimAPI_MakeCylinder(gp_Ax2(gp_Pnt(10.0 + 10 * i, 10.0 + 10 * j, -1), gp::DZ()), 3, 8).Shape());
    BRepAlgoAPI_Cut cut;
    cut.SetArguments(args);
    cut.SetTools(tools);
    cut.SetRunParallel(pm::useCores());
    cut.Build();
    return cut.Shape();
}

static void row(const char* what, double one, double all) {
    std::printf("%-34s %9.1f %9.1f  %4.2fx\n", what, one, all, one / all);
}

static void save(const char* path, const std::vector<uint8_t>& bytes) {
    std::ofstream(path, std::ios::binary).write(reinterpret_cast<const char*>(bytes.data()), std::streamsize(bytes.size()));
}

int main(int argc, char** argv) {
    // "files": only write the test parts, for opening in the app.
    if (argc > 1 && std::strcmp(argv[1], "files") == 0) {
        pm::setScratchDirectory(".");
        save("plate.step", pm::writeSolid(pm::Solid::fromShape(plate(12)), pm::SolidFormat::Step));
        TopoDS_Shape ball = BRepPrimAPI_MakeSphere(40).Shape();
        save("sphere.stl", pm::writeStl(pm::Solid::fromShape(ball).tessellate({0.002, 0.05})));
        return 0;
    }
    std::printf("cores: %u\n", std::thread::hardware_concurrency());

    // The steps of a project box and a threaded rod, as the app builds them; "parts" alone runs only this.
    if (argc > 1 && std::strcmp(argv[1], "parts") == 0) {
        const gp_Ax3 top(gp_Pnt(0, 0, 0), gp::DZ(), gp::DX());
        const gp_Ax3 front(gp_Pnt(0, -20, 0), -gp::DY(), gp::DX());
        auto named = [](const std::vector<std::string>& all, const char* part) {
            std::vector<std::string> out;
            for (const auto& n : all) if (n.find(part) != std::string::npos) out.push_back(n);
            return out;
        };
        pm::NamedShape box, rounded, hollow, vented, rod, threaded;
        double tBox = best(2, [&] { box = pm::primitive(1, top, pm::Primitive::Box, 0, 0, 80, 40, 25); });
        std::vector<std::string> upright;
        for (const auto& e : box.edgeNames())
            if ((e.find(".x0") != std::string::npos || e.find(".x1") != std::string::npos) &&
                (e.find(".y0") != std::string::npos || e.find(".y1") != std::string::npos)) upright.push_back(e);
        double tFillet = best(2, [&] { rounded = pm::fillet(2, box, upright, 4); });
        double tShell = best(2, [&] { hollow = pm::shell(3, rounded, named(rounded.faceNames(), ".end"), 2); });
        double tVents = best(1, [&] {
            vented = hollow;
            for (int i = 0; i < 5; ++i) {
                pm::NamedShape vent = pm::primitive(10 + i, front, pm::Primitive::Cylinder, -24.0 + 12 * i, 12, 5, 10, 0);
                vented = pm::combine(20 + i, vented, vent, pm::Combine::Cut);
            }
        });
        double tRod = best(2, [&] { rod = pm::primitive(30, top, pm::Primitive::Cylinder, 0, 0, 10, 20, 0); });
        double tThread = best(1, [&] { threaded = pm::thread(31, rod, named(rod.faceNames(), ".side").at(0), 1.5); });
        double tDisplay = best(2, [&] { BRepTools::Clean(vented.shape); pm::Solid::fromShape(vented.shape).display({0.05, 0.3}); });
        double tThreadDisplay = best(2, [&] { BRepTools::Clean(threaded.shape); pm::Solid::fromShape(threaded.shape).display({0.05, 0.3}); });
        std::printf("%-34s %9.1f\n", "box", tBox);
        std::printf("%-34s %9.1f\n", "box: fillet 4 corners", tFillet);
        std::printf("%-34s %9.1f\n", "box: shell open top", tShell);
        std::printf("%-34s %9.1f\n", "box: cut 5 vents", tVents);
        std::printf("%-34s %9.1f\n", "box: display at medium", tDisplay);
        std::printf("%-34s %9.1f\n", "rod: cylinder", tRod);
        std::printf("%-34s %9.1f\n", "rod: M10 thread", tThread);
        std::printf("%-34s %9.1f\n", "rod: display at medium", tThreadDisplay);
        // A diamond knurl as the app builds it: one groove each way, then each one's 11 copies cut together.
        pm::NamedShape knob = pm::primitive(40, top, pm::Primitive::Cylinder, 0, 0, 30, 15, 0);
        pm::NamedShape groove = pm::coil(41, top, 0, 0, 30, 60, 0.3, 1.5, false);
        const double flip[12] = {-1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
        pm::NamedShape other = pm::transformed(42, groove, flip, "m");
        pm::NamedShape knurled;
        double tKnurl = best(1, [&] {
            knurled = pm::combine(43, pm::combine(43, knob, groove, pm::Combine::Cut), other, pm::Combine::Cut);
            for (const auto* src : {&groove, &other}) {
                std::vector<pm::NamedShape> copies;
                for (int i = 1; i < 12; ++i) {
                    double a = 2 * M_PI * i / 12, c = std::cos(a), s = std::sin(a);
                    const double turn[12] = {c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0};
                    copies.push_back(pm::transformed(44, *src, turn, "p" + std::to_string(i)));
                }
                knurled = pm::combine(45, knurled, pm::gather(copies), pm::Combine::Cut);
            }
        });
        double tKnurlDisplay = best(1, [&] { BRepTools::Clean(knurled.shape); pm::Solid::fromShape(knurled.shape).display({0.05, 0.3}); });
        std::printf("%-34s %9.1f\n", "knob: 24 crossed grooves", tKnurl);
        std::printf("%-34s %9.1f\n", "knob: display at medium", tKnurlDisplay);
        return 0;
    }

    // A vase: a smooth loft through four rings, hollowed out, then meshed for display at
    // each detail level. Each step timed on its own; "vase" alone runs only this.
    {
        auto ring = [](double z, double r) {
            pm::SketchCurve c;
            c.kind = pm::SketchCurve::Circle;
            c.id = 2;
            c.r = r;
            pm::RegionPick pick;
            pick.curveIds = {2};
            return pm::LoftProfile{gp_Ax3(gp_Pnt(0, 0, z), gp::DZ(), gp::DX()), {c}, pick};
        };
        const std::vector<pm::LoftProfile> rings = {ring(0, 22), ring(45, 38), ring(95, 16), ring(110, 20)};
        pm::NamedShape vase;
        double loft = best(2, [&] { vase = pm::loft(9, rings, false); });
        pm::NamedShape hollow;
        double shell = best(2, [&] { hollow = pm::shell(10, vase, {"F9.end"}, 2); });
        std::printf("%-34s %9.1f\n", "vase: loft", loft);
        std::printf("%-34s %9.1f\n", "vase: shell", shell);
        const double levels[][2] = {{0.1, 0.4}, {0.05, 0.3}, {0.01, 0.25}};
        const char* names[] = {"low", "medium", "high"};
        for (int i = 0; i < 3; ++i) {
            size_t triangles = 0;
            double ms = best(2, [&] {
                BRepTools::Clean(hollow.shape);
                triangles = pm::Solid::fromShape(hollow.shape).display({levels[i][0], levels[i][1]}).indices.size() / 3;
            });
            std::printf("vase: display at %-17s %9.1f ms  %zu triangles\n", names[i], ms, triangles);
        }
        if (argc > 1 && std::strcmp(argv[1], "vase") == 0) return 0;
    }
    pm::speedTest();
    std::printf("speed test: %.1f ms\n", best(3, [] { pm::speedTest(); }));
    std::printf("%-34s %9s %9s\n", "", "1 core ms", "all ms");

    double t[2];
    TopoDS_Shape holes = plate(12);

    for (int p = 0; p < 2; ++p) {
        pm::useCores() = p == 1;
        t[p] = best(3, [] { plate(12); });
    }
    row("cut 144 holes in a plate", t[0], t[1]);

    for (int p = 0; p < 2; ++p) {
        pm::useCores() = p == 1;
        t[p] = best(3, [&] {
            BRepTools::Clean(holes);
            pm::Solid::fromShape(holes).display();
        });
    }
    row("mesh it for display", t[0], t[1]);


    // Display meshing at other settings, all cores: time and triangles.
    pm::useCores() = true;
    const double settings[][2] = {{0.01, 0.25}, {0.05, 0.25}, {0.1, 0.35}};
    for (auto& st : settings) {
        size_t triangles = 0;
        double ms = best(2, [&] {
            BRepTools::Clean(holes);
            triangles = pm::Solid::fromShape(holes).display({st[0], st[1]}).indices.size() / 3;
        });
        std::printf("display at chord %.2f angle %.2f   %9.1f ms  %zu triangles\n", st[0], st[1], ms, triangles);
    }

    // OCCT's meshing options, all cores.
    struct Way { const char* name; bool control; IMeshTools_MeshAlgoType algo; };
    const Way ways[] = {
        {"as now", true, IMeshTools_MeshAlgoType_Watson},
        {"no surface check", false, IMeshTools_MeshAlgoType_Watson},
        {"Delabella", true, IMeshTools_MeshAlgoType_Delabella},
        {"Delabella, no surface check", false, IMeshTools_MeshAlgoType_Delabella},
    };
    for (auto& st : settings) for (auto& w : ways) {
        size_t triangles = 0;
        double ms = best(2, [&] {
            BRepTools::Clean(holes);
            BRepMesh_IncrementalMesh m;
            m.SetShape(holes);
            IMeshTools_Parameters& prm = m.ChangeParameters();
            prm.Deflection = st[0];
            prm.Angle = st[1];
            prm.InParallel = true;
            prm.ControlSurfaceDeflection = w.control;
            prm.MeshAlgo = w.algo;
            m.Perform();
            triangles = 0;
            for (TopExp_Explorer e(holes, TopAbs_FACE); e.More(); e.Next()) {
                TopLoc_Location loc;
                auto tri = BRep_Tool::Triangulation(TopoDS::Face(e.Current()), loc);
                if (!tri.IsNull()) triangles += size_t(tri->NbTriangles());
            }
        });
        std::printf("%.2f/%.2f %-28s %9.1f ms  %zu triangles\n", st[0], st[1], w.name, ms, triangles);
    }

    // Fillets don't use threads; timed once for scale.
    pm::useCores() = false;
    double fillet = best(2, [&] {
        BRepFilletAPI_MakeFillet f(holes);
        for (TopExp_Explorer e(holes, TopAbs_EDGE); e.More(); e.Next()) {
            // The holes' top and bottom rims.
            f.Add(0.5, TopoDS::Edge(e.Current()));
        }
        f.Build();
    });
    std::printf("%-34s %9.1f\n", "fillet every edge", fillet);

    // Mesh bodies: a fine sphere as a big STL.
    TopoDS_Shape ball = BRepPrimAPI_MakeSphere(40).Shape();
    pm::Mesh sphere = pm::Solid::fromShape(ball).tessellate({0.002, 0.05});
    std::vector<uint8_t> stl = pm::writeStl(sphere);
    std::printf("%-34s %zu triangles, %zu kB\n", "test STL", sphere.triangleCount(), stl.size() / 1024);
    double read = best(3, [&] { pm::readStl(stl); });
    std::printf("%-34s %9.1f\n", "read the STL", read);
    pm::Mesh readBack = pm::readStl(stl);
    double fix = best(3, [&] { pm::RepairReport r; pm::repair(readBack, r); });
    std::printf("%-34s %9.1f\n", "repair check", fix);
    double show = best(3, [&] { pm::displayMesh(readBack); });
    std::printf("%-34s %9.1f\n", "display mesh for it", show);
    pm::MeshBody body = pm::MeshBody::fromMesh(readBack);
    pm::MeshBody other = body.translated(30, 0, 0);
    std::printf("%-34s %zu triangles\n", "as a mesh body", body.triangleCount());
    // Manifold works booleans out when the result is read.
    double boolean = best(3, [&] { body.boolean(other, pm::BooleanOp::Cut).triangleCount(); });
    std::printf("%-34s %9.1f\n", "mesh cut, sphere from sphere", boolean);
    return 0;
}
