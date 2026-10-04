#include "sketch/text.h"

#include <cmath>
#include <mutex>
#include <stdexcept>

#define STB_TRUETYPE_IMPLEMENTATION
#define STBTT_STATIC
#include "stb_truetype.h"

// The fonts, built in by CMake from third_party/fonts.
extern const unsigned char pm_font_regular[];
extern const unsigned char pm_font_bold[];

namespace pm {
namespace {

const stbtt_fontinfo& font(bool bold) {
    static stbtt_fontinfo fonts[2];
    static std::once_flag ready;
    std::call_once(ready, [] {
        if (!stbtt_InitFont(&fonts[0], pm_font_regular, 0) || !stbtt_InitFont(&fonts[1], pm_font_bold, 0))
            throw std::runtime_error("The font couldn't be read");
    });
    return fonts[bold ? 1 : 0];
}

/** The next character of UTF-8 text, moving i past it. */
int nextCodepoint(const std::string& s, size_t& i) {
    unsigned char c = static_cast<unsigned char>(s[i++]);
    int extra = c >= 0xF0 ? 3 : c >= 0xE0 ? 2 : c >= 0xC0 ? 1 : 0;
    int cp = extra == 3 ? c & 0x07 : extra == 2 ? c & 0x0F : extra == 1 ? c & 0x1F : c;
    for (int k = 0; k < extra && i < s.size(); ++k) cp = (cp << 6) | (static_cast<unsigned char>(s[i++]) & 0x3F);
    return cp;
}

}  // namespace

std::vector<SketchCurve> textOutline(const std::string& utf8, double height, bool bold, int firstId) {
    if (height <= 0) throw std::runtime_error("The text has to be taller than 0");
    const stbtt_fontinfo& f = font(bold);
    // Scaled so a capital H is the height asked for.
    int x0, y0, x1, y1;
    if (!stbtt_GetCodepointBox(&f, 'H', &x0, &y0, &x1, &y1) || y1 <= y0) throw std::runtime_error("The font couldn't be read");
    const double scale = height / (y1 - y0);
    int ascent, descent, gap;
    stbtt_GetFontVMetrics(&f, &ascent, &descent, &gap);
    const double lineStep = (ascent - descent + gap) * scale;

    std::vector<SketchCurve> out;
    int id = firstId;
    double penX = 0, penY = 0;
    int previous = 0;
    size_t i = 0;
    while (i < utf8.size()) {
        int cp = nextCodepoint(utf8, i);
        if (cp == '\n') {
            penX = 0;
            penY -= lineStep;
            previous = 0;
            continue;
        }
        if (previous) penX += stbtt_GetCodepointKernAdvance(&f, previous, cp) * scale;
        stbtt_vertex* v = nullptr;
        int n = stbtt_GetCodepointShape(&f, cp, &v);
        double lx = 0, ly = 0;
        auto at = [&](double x, double y) { return std::pair<double, double>{penX + x * scale, penY + y * scale}; };
        for (int k = 0; k < n; ++k) {
            auto [x, y] = at(v[k].x, v[k].y);
            SketchCurve c;
            if (v[k].type == STBTT_vline) {
                if (std::hypot(x - lx, y - ly) > 1e-9) {
                    c.kind = SketchCurve::Line;
                    c.x1 = lx; c.y1 = ly; c.x2 = x; c.y2 = y;
                    c.id = id++;
                    out.push_back(c);
                }
            } else if (v[k].type == STBTT_vcurve || v[k].type == STBTT_vcubic) {
                c.kind = SketchCurve::Bezier;
                c.x1 = lx; c.y1 = ly; c.x2 = x; c.y2 = y;
                auto [qx, qy] = at(v[k].cx, v[k].cy);
                if (v[k].type == STBTT_vcurve) {
                    // A quadratic as a cubic.
                    c.cx1 = lx + 2.0 / 3 * (qx - lx); c.cy1 = ly + 2.0 / 3 * (qy - ly);
                    c.cx2 = x + 2.0 / 3 * (qx - x); c.cy2 = y + 2.0 / 3 * (qy - y);
                } else {
                    auto [q2x, q2y] = at(v[k].cx1, v[k].cy1);
                    c.cx1 = qx; c.cy1 = qy; c.cx2 = q2x; c.cy2 = q2y;
                }
                if (std::hypot(x - lx, y - ly) > 1e-9 || std::hypot(c.cx1 - lx, c.cy1 - ly) > 1e-9) {
                    c.id = id++;
                    out.push_back(c);
                }
            }
            lx = x;
            ly = y;
        }
        stbtt_FreeShape(&f, v);
        int advance, bearing;
        stbtt_GetCodepointHMetrics(&f, cp, &advance, &bearing);
        penX += advance * scale;
        previous = cp;
    }
    return out;
}

}  // namespace pm
