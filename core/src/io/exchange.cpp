#include "io/exchange.h"

#include <IGESControl_Controller.hxx>
#include <IGESControl_Reader.hxx>
#include <IGESControl_Writer.hxx>
#include <Interface_Static.hxx>
#include <STEPControl_Reader.hxx>
#include <STEPControl_Writer.hxx>
#include <Standard_Failure.hxx>
#include <TopoDS_Shape.hxx>

#include <filesystem>
#include <fstream>
#include <iterator>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>

namespace pm {
namespace {

// OCCT's exchange settings are global, so only one file is read or written at a time.
std::mutex exchangeLock;

std::filesystem::path scratch;

std::string text(const std::vector<uint8_t>& data) { return std::string(data.begin(), data.end()); }

std::vector<uint8_t> bytes(const std::string& s) { return std::vector<uint8_t>(s.begin(), s.end()); }

std::filesystem::path scratchFile(const char* name) {
    return (scratch.empty() ? std::filesystem::temp_directory_path() : scratch) / name;
}

/** Deletes the scratch file when it goes out of scope. */
struct Remove {
    std::filesystem::path path;
    ~Remove() {
        std::error_code ignored;
        std::filesystem::remove(path, ignored);
    }
};

}  // namespace

void setScratchDirectory(const std::string& path) {
    std::lock_guard<std::mutex> g(exchangeLock);
    scratch = path;
}

Solid readSolid(const std::vector<uint8_t>& data, SolidFormat format) {
    std::lock_guard<std::mutex> g(exchangeLock);
    try {
        std::istringstream in(text(data));
        TopoDS_Shape shape;
        if (format == SolidFormat::Step) {
            STEPControl_Reader reader;
            if (reader.ReadStream("model.step", in) != IFSelect_RetDone) throw std::runtime_error("Not a STEP file OCCT can read");
            reader.TransferRoots();
            shape = reader.OneShape();
        } else {
            // IGES is read from a file only.
            Remove file{scratchFile("import.igs")};
            std::ofstream(file.path, std::ios::binary).write(reinterpret_cast<const char*>(data.data()), std::streamsize(data.size()));
            IGESControl_Controller::Init();
            IGESControl_Reader reader;
            if (reader.ReadFile(file.path.c_str()) != IFSelect_RetDone) throw std::runtime_error("Not an IGES file OCCT can read");
            reader.TransferRoots();
            shape = reader.OneShape();
        }
        if (shape.IsNull()) throw std::runtime_error("The file has no shapes in it");
        return Solid::fromShape(shape);
    } catch (const Standard_Failure& e) {
        throw std::runtime_error(std::string("Couldn't read the file: ") + e.what());
    }
}

std::vector<uint8_t> writeSolid(const Solid& solid, SolidFormat format) {
    std::lock_guard<std::mutex> g(exchangeLock);
    try {
        std::ostringstream out;
        if (format == SolidFormat::Step) {
            STEPControl_Writer writer;
            Interface_Static::SetCVal("write.step.unit", "MM");
            if (writer.Transfer(solid.shape(), STEPControl_AsIs) != IFSelect_RetDone || writer.WriteStream(out) != IFSelect_RetDone)
                throw std::runtime_error("Couldn't write STEP");
        } else {
            IGESControl_Controller::Init();
            IGESControl_Writer writer("MM", 1);  // 1: write faces as BRep entities.
            writer.AddShape(solid.shape());
            writer.ComputeModel();
            if (!writer.Write(out)) throw std::runtime_error("Couldn't write IGES");
        }
        return bytes(out.str());
    } catch (const Standard_Failure& e) {
        throw std::runtime_error(std::string("Couldn't write the file: ") + e.what());
    }
}

}  // namespace pm
