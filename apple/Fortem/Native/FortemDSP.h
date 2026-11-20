// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include <stdbool.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct FortemDSP FortemDSP;
FortemDSP *FortemCreate(void);
void FortemDestroy(FortemDSP *dsp);
bool FortemLoadPiano(FortemDSP *dsp, const char *directory);
// Configure/reset/load/share only while this instance is not rendering.
void FortemConfigure(FortemDSP *dsp, double sampleRate);
void FortemReset(FortemDSP *dsp);
void FortemSetTimbre(FortemDSP *dsp, int timbre);
void FortemSetOutputEnabled(FortemDSP *dsp, bool enabled);
void FortemNoteOn(FortemDSP *dsp, int note, float frequency, float velocity);
void FortemRetune(FortemDSP *dsp, int note, float frequency);
void FortemNoteOff(FortemDSP *dsp, int note);
void FortemRender(FortemDSP *dsp, float *interleavedStereo, int frames);
void FortemRenderPlanar(FortemDSP *dsp, float *left, float *right, int frames);
void FortemSharePiano(FortemDSP *destination, FortemDSP *source);
#ifdef __cplusplus
}
#endif
