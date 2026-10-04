#include "render/picture.h"

#include <algorithm>

#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#define STBI_ONLY_JPEG
#define STB_IMAGE_STATIC
#include "stb_image.h"

namespace pm {

Picture decodePicture(const std::vector<uint8_t>& file) {
    int w = 0, h = 0, channels = 0;
    unsigned char* data = stbi_load_from_memory(file.data(), int(file.size()), &w, &h, &channels, 4);
    if (!data) return {};
    std::vector<uint8_t> px(data, data + size_t(w) * size_t(h) * 4);
    stbi_image_free(data);
    // Halved, averaging each two by two, until it fits a texture every GPU takes.
    while (w > 2048 || h > 2048) {
        int nw = std::max(1, w / 2), nh = std::max(1, h / 2);
        std::vector<uint8_t> half(size_t(nw) * size_t(nh) * 4);
        for (int y = 0; y < nh; ++y)
            for (int x = 0; x < nw; ++x)
                for (int c = 0; c < 4; ++c) {
                    auto at = [&](int xx, int yy) { return px[(size_t(std::min(yy, h - 1)) * size_t(w) + size_t(std::min(xx, w - 1))) * 4 + size_t(c)]; };
                    half[(size_t(y) * size_t(nw) + size_t(x)) * 4 + size_t(c)] =
                        uint8_t((at(2 * x, 2 * y) + at(2 * x + 1, 2 * y) + at(2 * x, 2 * y + 1) + at(2 * x + 1, 2 * y + 1)) / 4);
                }
        px = std::move(half);
        w = nw;
        h = nh;
    }
    return {std::make_shared<const std::vector<uint8_t>>(std::move(px)), w, h};
}

}  // namespace pm
