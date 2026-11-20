// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
// CoreMIDI converts this MIDI 1.0 universal packet for the chosen destination.
int32_t FortemSendMIDI(uint32_t port, uint32_t destination, uint8_t status, uint8_t note, uint8_t value);
#ifdef __cplusplus
}
#endif
