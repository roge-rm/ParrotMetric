#pragma once

#include <string>
#include <vector>

#include "sketch/regions.h"

namespace pm {

/**
 * Text as outlines in the XY plane, starting at (0, 0) on the baseline,
 * capital letters [height] mm tall, in Noto Sans or its bold. Lines are a
 * line spacing apart going down. The curves are lines and Béziers, numbered
 * from [firstId].
 */
std::vector<SketchCurve> textOutline(const std::string& utf8, double height, bool bold, int firstId);

}  // namespace pm
