#pragma once
#include <cstdint>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>
struct AAssetManager { std::string directory; };
struct AAsset { std::vector<char> bytes; };
constexpr int AASSET_MODE_BUFFER = 0;
inline AAsset *AAssetManager_open(AAssetManager *m, const char *p, int) {
    std::ifstream file(m->directory + "/" + p, std::ios::binary);
    if (!file) return nullptr;
    auto *a = new AAsset;
    a->bytes.assign(std::istreambuf_iterator<char>(file), {});
    return a;
}
inline void AAsset_close(AAsset *a) { delete a; }
inline int64_t AAsset_getLength64(AAsset *a) { return a->bytes.size(); }
inline const void *AAsset_getBuffer(AAsset *a) { return a->bytes.data(); }
inline int64_t AAsset_read(AAsset *a, void *b, size_t n) { n = std::min(n, a->bytes.size()); std::memcpy(b, a->bytes.data(), n); return n; }
