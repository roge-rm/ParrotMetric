#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "sketch/regions.h"

namespace pm {

/** The fonts built in, in the order CMake builds them in: each has a regular and a bold. */
enum class TextFont { Sans, Serif, Mono, Rounded };

/**
 * Text as outlines in the XY plane, starting at (0, 0) on the baseline,
 * capital letters [height] mm tall, in a built-in [font] or its bold, or in
 * the TrueType or OpenType font file [data] if it's given (then [bold] does
 * nothing). Lines are a line spacing apart going down. The curves are lines
 * and Béziers, numbered from [firstId]. Throws if the file isn't a font.
 */
std::vector<SketchCurve> textOutline(const std::string& utf8, double height, bool bold, int firstId, TextFont font = TextFont::Sans,
                                     const std::vector<uint8_t>* data = nullptr);

}  // namespace pm
