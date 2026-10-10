#ifndef SPUMOTION_H
#define SPUMOTION_H

#include <stdbool.h>

/// Reads the MacBook's built-in gyroscope and accelerometer (Bosch IMU behind Apple's sensor
/// coprocessor, "SPU"). CoreMotion's CMMotionManager is unavailable on macOS, so this uses the
/// undocumented IOHIDEventSystemClient API. It may stop working in a future macOS release; callers
/// must handle `spu_motion_start` returning NULL.

typedef enum {
    SPUMotionKindRotation = 0,      ///< Gyroscope, degrees per second.
    SPUMotionKindAcceleration = 1,  ///< Accelerometer, g.
} SPUMotionKind;

/// Called on the run loop that `spu_motion_start` was called on. `time` is seconds of system uptime.
typedef void (*SPUMotionCallback)(void *_Nullable context, SPUMotionKind kind, double x, double y, double z, double time);

typedef struct SPUMotion SPUMotion;

/// Starts streaming both sensors every `interval_us` microseconds. NULL if no sensor was found.
SPUMotion *_Nullable spu_motion_start(unsigned interval_us, SPUMotionCallback _Nonnull callback, void *_Nullable context);

/// Stops streaming and frees the handle.
void spu_motion_stop(SPUMotion *_Nonnull motion);

#endif
