// SPDX-License-Identifier: GPL-3.0-only
#include "FortemMIDI.h"
#include <CoreMIDI/CoreMIDI.h>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <thread>

// A macOS virtual receiver exercises the same sender used by iPhone/iPad.
// iOS restricts receiver creation unless an app declares background audio.
int main() {
    MIDIClientRef client = 0;
    MIDIPortRef port = 0;
    MIDIEndpointRef destination = 0;
    if (MIDIClientCreateWithBlock(CFSTR("Fortem test"), &client, nullptr) != noErr) return 1;
    if (MIDIOutputPortCreate(client, CFSTR("Fortem output test"), &port) != noErr) return 2;
    std::atomic<unsigned> received{0};
    auto *seen = &received;
    const auto status = MIDIDestinationCreateWithProtocol(client, CFSTR("Fortem receiver test"), kMIDIProtocol_1_0, &destination,
        ^(const MIDIEventList *events, void *) {
            auto *packet = &events->packet[0];
            for (uint32_t i = 0; i < events->numPackets; ++i) {
                for (uint32_t j = 0; j < packet->wordCount; ++j) {
                    const auto word = packet->words[j];
                    if (word == 0x20903C64U) seen->fetch_or(1);
                    if (word == 0x20803C00U) seen->fetch_or(2);
                }
                packet = MIDIEventPacketNext(packet);
            }
        });
    if (status != noErr) { std::fprintf(stderr, "MIDI receiver: %d\n", int(status)); return 3; }
    const auto on = FortemSendMIDI(port, destination, 0x90, 60, 100);
    const auto off = FortemSendMIDI(port, destination, 0x80, 60, 0);
    for (int i = 0; i < 300 && received.load() != 3; ++i) std::this_thread::sleep_for(std::chrono::milliseconds(10));
    MIDIEndpointDispose(destination); MIDIPortDispose(port); MIDIClientDispose(client);
    if (on != noErr || off != noErr || received.load() != 3) return 4;
    std::puts("MIDI Note On and Note Off delivered");
    return 0;
}
