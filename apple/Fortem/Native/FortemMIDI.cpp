// SPDX-License-Identifier: GPL-3.0-only
#include "FortemMIDI.h"
#include <CoreMIDI/CoreMIDI.h>

int32_t FortemSendMIDI(uint32_t port, uint32_t destination, uint8_t status, uint8_t note, uint8_t value) {
    MIDIEventList events {};
    auto *packet = MIDIEventListInit(&events, kMIDIProtocol_1_0);
    // Group 0, channel 1; note and velocity are seven-bit MIDI values.
    const uint32_t word = 0x20000000U | (static_cast<uint32_t>(status) << 16U) |
                          (static_cast<uint32_t>(note & 0x7FU) << 8U) | (value & 0x7FU);
    if (!MIDIEventListAdd(&events, sizeof(events), packet, 0, 1, &word)) return -50; // Invalid parameter.
    return MIDISendEventList(port, destination, &events);
}
