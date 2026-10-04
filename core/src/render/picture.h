#pragma once

#include <cstdint>
#include <memory>
#include <vector>

namespace pm {

/** A picture's pixels, RGBA from the top row down. */
struct Picture {
    std::shared_ptr<const std::vector<uint8_t>> rgba;
    int width = 0, height = 0;
};

/** A PNG or JPEG file's pixels, halved until neither side is over 2048. Empty if it can't be read. */
Picture decodePicture(const std::vector<uint8_t>& file);

}  // namespace pm
