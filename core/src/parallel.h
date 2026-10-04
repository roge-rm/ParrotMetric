#pragma once

namespace pm {

/**
 * Whether OCCT spreads meshing and booleans over the cores, through its own
 * thread pool. On; the benchmark turns it off to compare.
 */
inline bool& useCores() {
    static bool on = true;
    return on;
}

}  // namespace pm
