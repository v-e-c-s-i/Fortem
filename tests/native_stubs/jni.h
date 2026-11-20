#pragma once
#include <cstdint>
#define JNIEXPORT
#define JNICALL
#define JNI_TRUE 1
#define JNI_FALSE 0
using jlong = int64_t; using jint=int32_t; using jfloat=float; using jdouble=double; using jboolean=bool; using jobject=void*; using jfloatArray=float*;
struct JNIEnv {
int GetArrayLength(jfloatArray) { return 2048; }
float *GetFloatArrayElements(jfloatArray p,void*) { return p; }
void ReleaseFloatArrayElements(jfloatArray,float*,int) {}
};
