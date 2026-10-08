#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

#define TSF_IMPLEMENTATION
#include "third_party/tsf.h"

namespace {
constexpr long kMaxFontBytes = 256L * 1024 * 1024;
constexpr int kMaxVoices = 128;
struct FontDeleter { void operator()(tsf* font) const { tsf_close(font); } };
using Font = std::unique_ptr<tsf, FontDeleter>;
std::mutex registryMutex;
std::unordered_map<jlong, Font> registry;
std::atomic<jlong> nextHandle{1};

void throwJava(JNIEnv* env, const char* type, const std::string& message) {
    if (!env->ExceptionCheck()) {
        jclass cls = env->FindClass(type);
        if (cls) env->ThrowNew(cls, message.c_str());
    }
}

struct Cancellation {
    JNIEnv* env;
    jobject object;
    jmethodID method;
    bool failed = false;
    bool cancelled() {
        if (failed || env->ExceptionCheck()) return failed = true;
        if (object && env->CallBooleanMethod(object, method) == JNI_TRUE) failed = true;
        if (env->ExceptionCheck()) failed = true;
        return failed;
    }
};

uint16_t u16(const uint8_t* p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }
uint32_t u32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8)
            | (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}
bool four(const uint8_t* p, const char* text) { return std::memcmp(p, text, 4) == 0; }
struct Chunk { long offset = 0; uint32_t size = 0; };

void readAt(FILE* file, long offset, void* data, size_t size) {
    if (fseek(file, offset, SEEK_SET) != 0 || fread(data, 1, size, file) != size)
        throw std::runtime_error("SoundFont is truncated or unreadable.");
}

// TSF's loader assumes valid SF2 table links. Validate bounds, monotonic terminal
// indices, sample references, and a region budget before passing user input to it.
uint32_t validateFont(FILE* file, Cancellation& cancel, std::unordered_map<long, uint32_t>& paddedSizes) {
    if (fseek(file, 0, SEEK_END) != 0) throw std::runtime_error("Could not read the SoundFont.");
    long length = ftell(file);
    if (length < 12 || length > kMaxFontBytes) throw std::runtime_error("SoundFont must be a valid .sf2 file up to 256 MB.");
    uint8_t header[12];
    readAt(file, 0, header, sizeof(header));
    if (!four(header, "RIFF") || !four(header + 8, "sfbk") || u32(header + 4) != static_cast<uint32_t>(length - 8))
        throw std::runtime_error("This file is not a complete SoundFont2 (.sf2) bank.");
    std::unordered_map<std::string, Chunk> chunks;
    bool pdta = false, sdta = false;
    for (long pos = 12; pos < length;) {
        if (cancel.cancelled()) return 0;
        if (length - pos < 8) throw std::runtime_error("Invalid SoundFont chunk header.");
        uint8_t top[12];
        readAt(file, pos, top, 8);
        uint32_t size = u32(top + 4);
        if (static_cast<uint64_t>(size) + (size & 1) > static_cast<uint64_t>(length - pos - 8)) throw std::runtime_error("Invalid SoundFont chunk length.");
        if (size & 1) paddedSizes[pos + 4] = size + 1;
        long end = pos + 8 + size;
        if (four(top, "LIST")) {
            if (size < 4) throw std::runtime_error("Invalid SoundFont LIST chunk.");
            readAt(file, pos + 8, top + 8, 4);
            bool table = four(top + 8, "pdta"), samples = four(top + 8, "sdta");
            if ((table && pdta) || (samples && sdta)) throw std::runtime_error("Duplicate SoundFont tables.");
            pdta |= table; sdta |= samples;
            // INFO is unused by TSF; its subchunks need not enter the audio tables.
            if (table || samples) {
                for (long child = pos + 12; child < end;) {
                    if (cancel.cancelled()) return 0;
                    if (end - child < 8) throw std::runtime_error("Invalid SoundFont table chunk.");
                    uint8_t item[8]; readAt(file, child, item, 8);
                    uint32_t bytes = u32(item + 4);
                    if (static_cast<uint64_t>(bytes) + (bytes & 1) > static_cast<uint64_t>(end - child - 8)) throw std::runtime_error("SoundFont table runs past the file.");
                    if (bytes & 1) paddedSizes[child + 4] = bytes + 1;
                    std::string name(reinterpret_cast<char*>(item), 4);
                    if (chunks.count(name)) throw std::runtime_error("Duplicate SoundFont table: " + name);
                    chunks[name] = {child + 8, bytes};
                    child += 8 + bytes + (bytes & 1);
                }
            }
        }
        pos = end + (size & 1);
    }
    if (!pdta || !sdta || !chunks.count("smpl") || chunks["smpl"].size < 4 || (chunks["smpl"].size & 1))
        throw std::runtime_error("SoundFont has no PCM samples or instrument tables.");
    const char* names[] = {"phdr", "pbag", "pmod", "pgen", "inst", "ibag", "imod", "igen", "shdr"};
    const uint32_t strides[] = {38, 4, 10, 4, 22, 4, 10, 4, 46};
    std::unordered_map<std::string, std::vector<uint8_t>> data;
    for (int i = 0; i < 9; ++i) {
        auto found = chunks.find(names[i]);
        if (found == chunks.end() || found->second.size == 0 || found->second.size % strides[i] != 0
                || found->second.size / strides[i] > 65536)
            throw std::runtime_error("Invalid or missing SoundFont table: " + std::string(names[i]));
        auto& bytes = data[names[i]];
        bytes.resize(found->second.size);
        readAt(file, found->second.offset, bytes.data(), bytes.size());
        if (cancel.cancelled()) return 0;
    }
    auto count = [&](const char* name, size_t stride) { return data[name].size() / stride; };
    size_t presets = count("phdr", 38), instruments = count("inst", 22), samples = count("shdr", 46);
    if (presets < 2 || presets > 4097 || instruments < 2 || samples < 2) throw std::runtime_error("SoundFont has no usable presets or samples.");
    auto validateIndex = [&](const char* table, size_t stride, size_t field, size_t limit) {
        const auto& bytes = data[table];
        uint16_t previous = 0;
        for (size_t off = 0; off < bytes.size(); off += stride) {
            uint16_t index = u16(bytes.data() + off + field);
            if (index < previous || index >= limit) throw std::runtime_error("SoundFont contains invalid table links.");
            previous = index;
        }
    };
    validateIndex("phdr", 38, 24, count("pbag", 4));
    validateIndex("inst", 22, 20, count("ibag", 4));
    validateIndex("pbag", 4, 0, count("pgen", 4));
    validateIndex("pbag", 4, 2, count("pmod", 10));
    validateIndex("ibag", 4, 0, count("igen", 4));
    validateIndex("ibag", 4, 2, count("imod", 10));
    for (size_t off = 0; off < data["pgen"].size(); off += 4)
        if (u16(data["pgen"].data() + off) == 41 && u16(data["pgen"].data() + off + 2) >= instruments - 1)
            throw std::runtime_error("SoundFont refers to an invalid instrument.");
    for (size_t off = 0; off < data["igen"].size(); off += 4)
        if (u16(data["igen"].data() + off) == 53 && u16(data["igen"].data() + off + 2) >= samples - 1)
            throw std::runtime_error("SoundFont refers to an invalid sample.");
    const uint32_t sampleCount = chunks["smpl"].size / 2;
    for (size_t i = 0; i < samples - 1; ++i) {
        const auto* sample = data["shdr"].data() + i * 46;
        uint32_t start = u32(sample + 20), end = u32(sample + 24), rate = u32(sample + 36);
        if (start >= end || end >= sampleCount || u32(sample + 28) > sampleCount || u32(sample + 32) > sampleCount
                || rate < 400 || rate > 384000 || (u16(sample + 44) & 0x30))
            throw std::runtime_error("SoundFont contains invalid or compressed sample data. Use an uncompressed .sf2 bank.");
    }
    // Count an upper bound on regions before TSF allocates them. Shared instrument
    // references can otherwise expand a small malformed bank to unbounded memory.
    std::vector<uint64_t> instrumentRegions(instruments - 1);
    for (size_t i = 0; i < instruments - 1; ++i) {
        if (cancel.cancelled()) return 0;
        const auto* inst = data["inst"].data() + i * 22;
        for (size_t bag = u16(inst + 20); bag < u16(inst + 42); ++bag) {
            const auto* zone = data["ibag"].data() + bag * 4;
            for (size_t gen = u16(zone); gen < u16(zone + 4); ++gen)
                if (u16(data["igen"].data() + gen * 4) == 53) ++instrumentRegions[i];
        }
    }
    uint64_t regions = 0;
    for (size_t i = 0; i < presets - 1; ++i) {
        if (cancel.cancelled()) return 0;
        const auto* preset = data["phdr"].data() + i * 38;
        for (size_t bag = u16(preset + 24); bag < u16(preset + 62); ++bag) {
            const auto* zone = data["pbag"].data() + bag * 4;
            for (size_t gen = u16(zone); gen < u16(zone + 4); ++gen) {
                const auto* generator = data["pgen"].data() + gen * 4;
                if (u16(generator) == 41) regions += instrumentRegions[u16(generator + 2)];
                if (regions > 131072) throw std::runtime_error("SoundFont exceeds this beta's instrument-region limit.");
            }
        }
    }
    rewind(file);
    return sampleCount;
}

struct Stream { FILE* file; Cancellation* cancellation; long remaining; const std::unordered_map<long, uint32_t>* paddedSizes; };
int streamRead(void* opaque, void* output, unsigned int size) {
    auto* stream = static_cast<Stream*>(opaque);
    auto* bytes = static_cast<uint8_t*>(output);
    long start = ftell(stream->file);
    if (size > static_cast<unsigned long>(stream->remaining)) {
        std::memset(output, 0, size); stream->cancellation->failed = true; return 0;
    }
    for (unsigned int done = 0; done < size;) {
        if (stream->cancellation->cancelled()) { std::memset(output, 0, size); return 0; }
        unsigned int part = std::min(65536U, size - done);
        if (fread(bytes + done, 1, part, stream->file) != part) {
            std::memset(output, 0, size); stream->cancellation->failed = true; return 0;
        }
        done += part; stream->remaining -= part;
    }
    // TSF doesn't consume RIFF's odd-chunk pad itself. Advertise that pad as part
    // of an unknown chunk's payload so its unchanged loader advances correctly.
    if (size == 4) {
        auto padded = stream->paddedSizes->find(start);
        if (padded != stream->paddedSizes->end()) {
            uint32_t value = padded->second;
            for (int i = 0; i < 4; ++i) bytes[i] = static_cast<uint8_t>(value >> (i * 8));
        }
    }
    return static_cast<int>(size);
}
int streamSkip(void* opaque, unsigned int size) {
    auto* stream = static_cast<Stream*>(opaque);
    if (stream->cancellation->cancelled() || size > static_cast<unsigned long>(stream->remaining)) return 0;
    if (fseek(stream->file, size, SEEK_CUR) != 0) { stream->cancellation->failed = true; return 0; }
    stream->remaining -= size;
    return 1;
}

tsf* lookup(JNIEnv* env, jlong handle) {
    auto found = registry.find(handle);
    if (found == registry.end()) { throwJava(env, "java/lang/IllegalStateException", "SoundFont synthesizer has been closed."); return nullptr; }
    return found->second.get();
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_karaokeflow_app_NativeSynth_nativeLoad(JNIEnv* env, jclass, jstring path, jobject cancellation) {
    if (!path || !cancellation) { throwJava(env, "java/io/IOException", "Missing SoundFont path."); return 0; }
    jclass cls = env->GetObjectClass(cancellation);
    jmethodID method = env->GetMethodID(cls, "isCancelled", "()Z");
    env->DeleteLocalRef(cls);
    if (!method) return 0;
    Cancellation cancel{env, cancellation, method};
    const char* name = env->GetStringUTFChars(path, nullptr);
    if (!name) return 0;
    std::unique_ptr<FILE, int(*)(FILE*)> file(fopen(name, "rb"), fclose);
    env->ReleaseStringUTFChars(path, name);
    if (!file) { throwJava(env, "java/io/IOException", "Could not open your selected SoundFont."); return 0; }
    try {
        std::unordered_map<long, uint32_t> paddedSizes;
        uint32_t sampleCount = validateFont(file.get(), cancel, paddedSizes);
        if (!sampleCount || cancel.cancelled()) return 0;
        fseek(file.get(), 0, SEEK_END);
        long length = ftell(file.get()); rewind(file.get());
        Stream data{file.get(), &cancel, length, &paddedSizes};
        tsf_stream stream{&data, streamRead, streamSkip};
        Font font(tsf_load(&stream));
        if (cancel.cancelled()) return 0;
        if (!font || tsf_get_presetcount(font.get()) <= 0) throw std::runtime_error("Could not decode this .sf2 SoundFont.");
        // Generator offsets are applied by TSF; validate resulting regions too,
        // including the interpolation look-ahead and loop positions.
        for (int i = 0; i < font->presetNum; ++i) {
            if (cancel.cancelled()) return 0;
            const auto& preset = font->presets[i];
            for (int r = 0; r < preset.regionNum; ++r) {
                const auto& region = preset.regions[r];
                if (region.offset >= region.end || region.end >= sampleCount || region.sample_rate == 0
                        || (region.loop_mode != TSF_LOOPMODE_NONE && (region.loop_start < region.offset
                            || region.loop_start >= region.loop_end || region.loop_end >= region.end)))
                    throw std::runtime_error("SoundFont contains unsafe sample or loop offsets.");
            }
        }
        tsf_set_output(font.get(), TSF_STEREO_INTERLEAVED, 44100, -6.0f);
        if (!tsf_set_max_voices(font.get(), kMaxVoices)) throw std::runtime_error("Not enough memory for SoundFont voices.");
        for (int channel = 0; channel < 16; ++channel) {
            if (!tsf_channel_set_presetnumber(font.get(), channel, 0, channel == 9))
                tsf_channel_set_presetindex(font.get(), channel, 0);
        }
        jlong handle = nextHandle.fetch_add(1);
        std::lock_guard<std::mutex> lock(registryMutex);
        registry.emplace(handle, std::move(font));
        return handle;
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/io/IOException", "Not enough memory for this SoundFont. Choose a smaller .sf2 bank.");
    } catch (const std::exception& error) {
        if (!cancel.cancelled()) throwJava(env, "java/io/IOException", error.what());
    }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_karaokeflow_app_NativeSynth_nativeEvent(JNIEnv* env, jclass, jlong handle, jint status, jint data1, jint data2) {
    std::lock_guard<std::mutex> lock(registryMutex);
    tsf* font = lookup(env, handle);
    if (!font) return;
    if (status < 0x80 || status >= 0xf0 || data1 < 0 || data1 > 127 || data2 < 0 || data2 > 127) {
        throwJava(env, "java/lang/IllegalArgumentException", "Invalid MIDI channel event."); return;
    }
    int channel = status & 15;
    switch (status & 0xf0) {
        case 0x80: tsf_channel_note_off(font, channel, data1); break;
        case 0x90: tsf_channel_note_on(font, channel, data1, data2 / 127.0f); break;
        case 0xb0: tsf_channel_midi_control(font, channel, data1, data2); break;
        case 0xc0: tsf_channel_set_presetnumber(font, channel, data1, channel == 9); break;
        case 0xe0: tsf_channel_set_pitchwheel(font, channel, data1 | (data2 << 7)); break;
        default: break; // Channel/poly aftertouch are not implemented by TinySoundFont.
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_karaokeflow_app_NativeSynth_nativeRender(JNIEnv* env, jclass, jlong handle, jshortArray output, jint offset, jint frames) {
    std::lock_guard<std::mutex> lock(registryMutex);
    tsf* font = lookup(env, handle);
    if (!font) return;
    if (!output || offset < 0 || frames < 0 || frames > 16384
            || (static_cast<int64_t>(offset) + frames) * 2 > env->GetArrayLength(output)) {
        throwJava(env, "java/lang/IllegalArgumentException", "Invalid synthesizer output buffer."); return;
    }
    if (frames == 0) return;
    jshort* buffer = env->GetShortArrayElements(output, nullptr);
    if (!buffer) return;
    tsf_render_short(font, buffer + offset * 2, frames, 0);
    env->ReleaseShortArrayElements(output, buffer, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_karaokeflow_app_NativeSynth_nativeReset(JNIEnv* env, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    tsf* font = lookup(env, handle);
    if (!font) return;
    // Start each song from a clean channel/voice state while retaining the decoded SF2.
    tsf_reset(font);
    if (font->voices) {
        for (int i = 0; i < font->voiceNum; ++i) font->voices[i].playingPreset = -1;
    }
    for (int channel = 0; channel < 16; ++channel) {
        if (!tsf_channel_set_presetnumber(font, channel, 0, channel == 9)) {
            tsf_channel_set_presetindex(font, channel, 0);
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_karaokeflow_app_NativeSynth_nativeReleaseNotes(JNIEnv* env, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    tsf* font = lookup(env, handle);
    if (!font) return;
    for (int channel = 0; channel < 16; ++channel) {
        tsf_channel_set_sustain(font, channel, 0);
        tsf_channel_note_off_all(font, channel);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_karaokeflow_app_NativeSynth_nativeActiveVoices(JNIEnv* env, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    tsf* font = lookup(env, handle);
    return font ? tsf_active_voice_count(font) : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_karaokeflow_app_NativeSynth_nativeClose(JNIEnv*, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    registry.erase(handle); // Opaque IDs make repeated close and stale calls safe.
}
