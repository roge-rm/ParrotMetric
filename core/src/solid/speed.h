#pragma once

namespace pm {

/**
 * Times a small, fixed piece of modelling work (a plate with sixteen holes,
 * cut and meshed for display) and returns the time in ms, to judge how
 * much detail this device can show comfortably. About 0.3 s on a slow
 * tablet, far less on a desktop.
 */
double speedTest();

}  // namespace pm
