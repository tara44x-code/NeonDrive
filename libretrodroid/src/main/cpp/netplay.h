/*
 * NeonDrive: lockstep two-player netplay coordinator.
 *
 * Both phones run the same game from the same state. Every frame each side's buttons are
 * captured, tagged for frame (current + delay) and sent to the other side. A frame only runs
 * when both players' buttons for it are known, so both emulators see identical input and stay
 * in sync. The delay (a few frames) hides the Wi-Fi latency.
 *
 * Player 1 (port 0) = host, player 2 (port 1) = guest.
 */

#ifndef LIBRETRODROID_NETPLAY_H
#define LIBRETRODROID_NETPLAY_H

#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>
#include <unordered_map>

namespace libretrodroid {

class Netplay {
public:
    void start(bool host, int inputDelay) {
        std::lock_guard<std::mutex> lock(mutex);
        active = true;
        isHost = host;
        delay = inputDelay < 1 ? 1 : (inputDelay > 12 ? 12 : inputDelay);
        frame = 0;
        localQueued = false;
        stalls = 0;
        local.clear(); remote.clear(); outbox.clear();
        // The first `delay` frames run with no buttons on both sides.
        for (int64_t f = 0; f < delay; f++) { local[f] = 0; remote[f] = 0; }
        cv.notify_all();
    }

    void stop() {
        std::lock_guard<std::mutex> lock(mutex);
        active = false;
        local.clear(); remote.clear(); outbox.clear();
        cv.notify_all();
    }

    bool isActive() {
        std::lock_guard<std::mutex> lock(mutex);
        return active;
    }

    /**
     * Emulation thread, before running a frame. Queues this side's buttons (once per frame) and
     * waits up to waitMs for the other side. On success fills ports[0..1] and returns true;
     * otherwise the frame must not run yet (the caller simply tries again next tick).
     */
    bool prepareFrame(uint16_t localMask, uint16_t ports[2], int waitMs) {
        std::unique_lock<std::mutex> lock(mutex);
        if (!active) return false;
        if (!localQueued) {
            int64_t target = frame + delay;
            local[target] = localMask;
            outbox.emplace_back(target, localMask);
            localQueued = true;
        }
        bool ready = cv.wait_for(lock, std::chrono::milliseconds(waitMs), [this] {
            return !active || remote.count(frame) > 0;
        });
        if (!active || !ready) { stalls++; return false; }
        uint16_t mine = local.count(frame) ? local[frame] : 0;
        uint16_t theirs = remote[frame];
        ports[isHost ? 0 : 1] = mine;
        ports[isHost ? 1 : 0] = theirs;
        return true;
    }

    /** Emulation thread, after the frame ran. */
    void frameDone() {
        std::lock_guard<std::mutex> lock(mutex);
        local.erase(frame);
        remote.erase(frame);
        frame++;
        localQueued = false;
        consecutiveStalls = 0;
    }

    void noteStall() {
        std::lock_guard<std::mutex> lock(mutex);
        consecutiveStalls++;
    }

    /** Network thread: buttons received from the other player. */
    void pushRemote(int64_t targetFrame, uint16_t mask) {
        std::lock_guard<std::mutex> lock(mutex);
        if (!active || targetFrame < frame) return;
        remote[targetFrame] = mask;
        cv.notify_all();
    }

    /** Network thread: buttons to send. Returns how many were written. */
    int pollOutbox(int64_t* frames, uint16_t* masks, int max) {
        std::lock_guard<std::mutex> lock(mutex);
        int n = 0;
        while (n < max && !outbox.empty()) {
            frames[n] = outbox.front().first;
            masks[n] = outbox.front().second;
            outbox.pop_front();
            n++;
        }
        return n;
    }

    /** [current frame, total stalls, stalls in a row] */
    void status(int64_t out[3]) {
        std::lock_guard<std::mutex> lock(mutex);
        out[0] = frame; out[1] = stalls; out[2] = consecutiveStalls;
    }

private:
    std::mutex mutex;
    std::condition_variable cv;
    bool active = false;
    bool isHost = true;
    int delay = 3;
    int64_t frame = 0;
    bool localQueued = false;
    int64_t stalls = 0;
    int64_t consecutiveStalls = 0;
    std::unordered_map<int64_t, uint16_t> local;
    std::unordered_map<int64_t, uint16_t> remote;
    std::deque<std::pair<int64_t, uint16_t>> outbox;
};

} // namespace libretrodroid

#endif // LIBRETRODROID_NETPLAY_H
