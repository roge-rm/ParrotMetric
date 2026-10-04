#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "solid/solid.h"

namespace pm {

/** Solid file formats OCCT reads and writes for us. */
enum class SolidFormat { Step, Iges };

/**
 * A folder for files OCCT can only read or write on disk (IGES). The system's
 * temporary folder until set; Android passes the app's cache folder.
 */
void setScratchDirectory(const std::string& path);

/**
 * Reads every solid in a STEP or IGES file as one Solid, in millimetres
 * whatever units the file uses. Throws std::runtime_error if it can't.
 */
Solid readSolid(const std::vector<uint8_t>& data, SolidFormat format);

/** Writes a solid as STEP (AP214) or IGES, in millimetres. */
std::vector<uint8_t> writeSolid(const Solid& solid, SolidFormat format);

}  // namespace pm
