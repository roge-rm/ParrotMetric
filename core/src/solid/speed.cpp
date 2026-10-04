#include "solid/speed.h"

#include <BRepAlgoAPI_Cut.hxx>
#include <BRepPrimAPI_MakeBox.hxx>
#include <BRepPrimAPI_MakeCylinder.hxx>
#include <TopTools_ListOfShape.hxx>
#include <gp_Ax2.hxx>

#include <chrono>

#include "parallel.h"
#include "solid/solid.h"

namespace pm {

double speedTest() {
    auto start = std::chrono::steady_clock::now();
    const int n = 4;
    TopTools_ListOfShape args, tools;
    args.Append(BRepPrimAPI_MakeBox(10.0 * n + 10, 10.0 * n + 10, 6).Shape());
    for (int i = 0; i < n; ++i)
        for (int j = 0; j < n; ++j)
            tools.Append(BRepPrimAPI_MakeCylinder(gp_Ax2(gp_Pnt(10.0 + 10 * i, 10.0 + 10 * j, -1), gp::DZ()), 3, 8).Shape());
    BRepAlgoAPI_Cut cut;
    cut.SetArguments(args);
    cut.SetTools(tools);
    cut.SetRunParallel(useCores());
    cut.Build();
    if (cut.IsDone()) Solid::fromShape(cut.Shape()).display({0.01, 0.25});
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
}

}  // namespace pm
