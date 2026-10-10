#include "SPUMotion.h"

#include <CoreFoundation/CoreFoundation.h>
#include <mach/mach_time.h>
#include <stdlib.h>

// Private IOKit HID event system API (exported by IOKit.framework, not in the public headers).
typedef struct __IOHIDEventSystemClient *IOHIDEventSystemClientRef;
typedef struct __IOHIDServiceClient *IOHIDServiceClientRef;
typedef struct __IOHIDEvent *IOHIDEventRef;
typedef void (*IOHIDEventSystemClientEventCallback)(void *target, void *refcon, IOHIDServiceClientRef sender, IOHIDEventRef event);

extern IOHIDEventSystemClientRef IOHIDEventSystemClientCreate(CFAllocatorRef allocator);
extern void IOHIDEventSystemClientSetMatching(IOHIDEventSystemClientRef client, CFDictionaryRef match);
extern CFArrayRef IOHIDEventSystemClientCopyServices(IOHIDEventSystemClientRef client);
extern Boolean IOHIDServiceClientSetProperty(IOHIDServiceClientRef service, CFStringRef key, CFTypeRef value);
extern void IOHIDEventSystemClientRegisterEventCallback(IOHIDEventSystemClientRef client, IOHIDEventSystemClientEventCallback callback, void *target, void *refcon);
extern void IOHIDEventSystemClientUnregisterEventCallback(IOHIDEventSystemClientRef client, IOHIDEventSystemClientEventCallback callback, void *target, void *refcon);
extern void IOHIDEventSystemClientScheduleWithRunLoop(IOHIDEventSystemClientRef client, CFRunLoopRef runLoop, CFStringRef mode);
extern void IOHIDEventSystemClientUnscheduleWithRunLoop(IOHIDEventSystemClientRef client, CFRunLoopRef runLoop, CFStringRef mode);
extern uint32_t IOHIDEventGetType(IOHIDEventRef event);
extern double IOHIDEventGetFloatValue(IOHIDEventRef event, uint32_t field);

// Event types and field layout from IOHIDFamily's IOHIDEventTypes.h (field = type << 16 | index).
enum { kAccelerometerEvent = 13, kGyroEvent = 20 };
// Vendor usages of the SPU sensors, as listed in the IORegistry (page 0xFF00).
enum { kSPUUsagePage = 0xFF00, kAccelerometerUsage = 3, kGyroUsage = 9 };

struct SPUMotion {
    IOHIDEventSystemClientRef clients[2];
    CFArrayRef services[2];
    CFRunLoopRef runLoop;
    SPUMotionCallback callback;
    void *context;
};

static double uptime(void) {
    static mach_timebase_info_data_t timebase;
    if (timebase.denom == 0) mach_timebase_info(&timebase);
    return (double)mach_absolute_time() * timebase.numer / timebase.denom / 1e9;
}

static void on_event(void *target, void *refcon, IOHIDServiceClientRef sender, IOHIDEventRef event) {
    SPUMotion *motion = target;
    uint32_t type = IOHIDEventGetType(event);
    SPUMotionKind kind;
    if (type == kGyroEvent) kind = SPUMotionKindRotation;
    else if (type == kAccelerometerEvent) kind = SPUMotionKindAcceleration;
    else return;
    motion->callback(motion->context, kind,
                     IOHIDEventGetFloatValue(event, type << 16),
                     IOHIDEventGetFloatValue(event, (type << 16) | 1),
                     IOHIDEventGetFloatValue(event, (type << 16) | 2),
                     uptime());
}

static void set_interval(CFArrayRef services, int interval_us) {
    CFNumberRef value = CFNumberCreate(NULL, kCFNumberIntType, &interval_us);
    for (CFIndex i = 0; services && i < CFArrayGetCount(services); i++) {
        IOHIDServiceClientSetProperty((IOHIDServiceClientRef)CFArrayGetValueAtIndex(services, i), CFSTR("ReportInterval"), value);
    }
    CFRelease(value);
}

static IOHIDEventSystemClientRef open_sensor(SPUMotion *motion, int usage, CFArrayRef *services, int interval_us) {
    IOHIDEventSystemClientRef client = IOHIDEventSystemClientCreate(kCFAllocatorDefault);
    if (!client) return NULL;
    int page = kSPUUsagePage;
    CFNumberRef pageNumber = CFNumberCreate(NULL, kCFNumberIntType, &page);
    CFNumberRef usageNumber = CFNumberCreate(NULL, kCFNumberIntType, &usage);
    const void *keys[] = { CFSTR("PrimaryUsagePage"), CFSTR("PrimaryUsage") };
    const void *values[] = { pageNumber, usageNumber };
    CFDictionaryRef match = CFDictionaryCreate(NULL, keys, values, 2, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    IOHIDEventSystemClientSetMatching(client, match);
    CFRelease(match);
    CFRelease(pageNumber);
    CFRelease(usageNumber);

    *services = IOHIDEventSystemClientCopyServices(client);
    if (!*services || CFArrayGetCount(*services) == 0) {
        if (*services) CFRelease(*services);
        *services = NULL;
        CFRelease(client);
        return NULL;
    }
    // The sensors only stream while someone asks for a report interval.
    set_interval(*services, interval_us);
    IOHIDEventSystemClientRegisterEventCallback(client, on_event, motion, NULL);
    // Common modes: keep receiving while the person clicks or scrolls (event-tracking mode).
    IOHIDEventSystemClientScheduleWithRunLoop(client, motion->runLoop, kCFRunLoopCommonModes);
    return client;
}

SPUMotion *spu_motion_start(unsigned interval_us, SPUMotionCallback callback, void *context) {
    SPUMotion *motion = calloc(1, sizeof(SPUMotion));
    motion->runLoop = CFRunLoopGetCurrent();
    motion->callback = callback;
    motion->context = context;
    motion->clients[0] = open_sensor(motion, kGyroUsage, &motion->services[0], (int)interval_us);
    motion->clients[1] = open_sensor(motion, kAccelerometerUsage, &motion->services[1], (int)interval_us);
    if (!motion->clients[0] || !motion->clients[1]) {
        spu_motion_stop(motion);
        return NULL;
    }
    return motion;
}

void spu_motion_stop(SPUMotion *motion) {
    for (int i = 0; i < 2; i++) {
        if (motion->services[i]) {
            set_interval(motion->services[i], 0);  // let the sensor idle again
            CFRelease(motion->services[i]);
        }
        if (motion->clients[i]) {
            IOHIDEventSystemClientUnregisterEventCallback(motion->clients[i], on_event, motion, NULL);
            IOHIDEventSystemClientUnscheduleWithRunLoop(motion->clients[i], motion->runLoop, kCFRunLoopCommonModes);
            CFRelease(motion->clients[i]);
        }
    }
    free(motion);
}
