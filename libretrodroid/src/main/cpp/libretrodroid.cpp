/*
 *     Copyright (C) 2019  Filippo Scognamiglio
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include <jni.h>

#include <EGL/egl.h>

#include <string>
#include <utility>
#include <vector>
#include <unordered_set>

#include "libretrodroid.h"
#include "utils/libretrodroidexception.h"
#include "log.h"
#include "core.h"
#include "audio.h"
#include "video.h"
#include "renderers/renderer.h"
#include "fpssync.h"
#include "input.h"
#include "rumble.h"
#include "shadermanager.h"
#include "utils/javautils.h"
#include "environment.h"
#include "renderers/es3/framebufferrenderer.h"
#include "renderers/es2/imagerendereres2.h"
#include "renderers/es3/imagerendereres3.h"
#include "utils/utils.h"
#include "utils/rect.h"
#include "errorcodes.h"
#include "vfs/vfs.h"

namespace libretrodroid {

uintptr_t LibretroDroid::callback_get_current_framebuffer() {
    return LibretroDroid::getInstance().handleGetCurrentFrameBuffer();
}

void LibretroDroid::callback_hw_video_refresh(
    const void *data,
    unsigned width,
    unsigned height,
    size_t pitch
) {
    LOGD("hw video refresh callback called %i %i", width, height);
    LibretroDroid::getInstance().handleVideoRefresh(data, width, height, pitch);
}

void LibretroDroid::callback_audio_sample(int16_t left, int16_t right) {
    LOGE("callback audio sample (left, right) has been called");
}

size_t LibretroDroid::callback_set_audio_sample_batch(const int16_t *data, size_t frames) {
    return LibretroDroid::getInstance().handleAudioCallback(data, frames);
}

void LibretroDroid::callback_retro_set_input_poll() {
    // Do nothing in here...
}

int16_t LibretroDroid::callback_set_input_state(
    unsigned int port,
    unsigned int device,
    unsigned int index,
    unsigned int id
) {
    return LibretroDroid::getInstance().handleSetInputState(port, device, index, id);
}

void LibretroDroid::updateAudioSampleRateMultiplier() {
    if (audio) {
        audio->setPlaybackSpeed(frameSpeed);
    }
}

// TODO... Do we really need this?
void LibretroDroid::resetGlobalVariables() {
    core = nullptr;
    audio = nullptr;
    video = nullptr;
    fpsSync = nullptr;
    input = nullptr;
    rumble = nullptr;
}

int LibretroDroid::availableDisks() {
    return Environment::getInstance().getRetroDiskControlCallback() != nullptr
           ? Environment::getInstance().getRetroDiskControlCallback()->get_num_images()
           : 0;
}

int LibretroDroid::currentDisk() {
    return Environment::getInstance().getRetroDiskControlCallback() != nullptr
           ? Environment::getInstance().getRetroDiskControlCallback()->get_image_index()
           : 0;
}

void LibretroDroid::changeDisk(unsigned int index) {
    if (Environment::getInstance().getRetroDiskControlCallback() == nullptr) {
        LOGE("Cannot swap disk. This platform does not support it.");
        return;
    }

    if (index < 0 || index >= Environment::getInstance().getRetroDiskControlCallback()->get_num_images()) {
        LOGE("Requested image index is not valid.");
        return;
    }

    if (Environment::getInstance().getRetroDiskControlCallback()->get_image_index() != index) {
        Environment::getInstance().getRetroDiskControlCallback()->set_eject_state(true);
        Environment::getInstance().getRetroDiskControlCallback()->set_image_index((unsigned) index);
        Environment::getInstance().getRetroDiskControlCallback()->set_eject_state(false);
    }
}

void LibretroDroid::updateVariable(const Variable& variable) {
    Environment::getInstance().updateVariable(variable.key, variable.value);
}

std::vector<Variable> LibretroDroid::getVariables() {
    return Environment::getInstance().getVariables();
}

std::vector<std::vector<struct Controller>> LibretroDroid::getControllers() {
    return Environment::getInstance().getControllers();
}

void LibretroDroid::setControllerType(unsigned int port, unsigned int type) {
    core->retro_set_controller_port_device(port, type);
}

bool LibretroDroid::unserializeState(int8_t *data, size_t size) {
    std::lock_guard<std::mutex> lock(coreLock);

    return core->retro_unserialize(data, size);
}

JNIEXPORT jboolean JNICALL LibretroDroid::unserializeSRAM(int8_t* data, size_t size) {
    std::lock_guard<std::mutex> lock(coreLock);

    size_t sramSize = core->retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    void *sramState = core->retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);

    if (sramState == nullptr) {
        LOGE("Cannot load SRAM: nullptr in retro_get_memory_data");
        return false;
    }

    if (size > sramSize) {
        LOGE("Cannot load SRAM: size mismatch");
        return false;
    }

    memcpy(sramState, data, size);

    return true;
}

std::pair<int8_t*, size_t> LibretroDroid::serializeSRAM() {
    std::lock_guard<std::mutex> lock(coreLock);

    size_t size = core->retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    auto* data = new int8_t[size];
    memcpy(data, (int8_t*) core->retro_get_memory_data(RETRO_MEMORY_SAVE_RAM), size);

    return std::pair(data, size);
}

void LibretroDroid::onSurfaceChanged(unsigned int width, unsigned int height) {
    LOGD("Performing libretrodroid onSurfaceChanged");
    video->updateScreenSize(width, height);
}

void LibretroDroid::onSurfaceCreated() {
    LOGD("Performing libretrodroid onSurfaceCreated");

    struct retro_system_av_info system_av_info {};
    core->retro_get_system_av_info(&system_av_info);

    video = nullptr;

    Video::RenderingOptions renderingOptions {
        Environment::getInstance().isUseHwAcceleration(),
        system_av_info.geometry.base_width,
        system_av_info.geometry.base_height,
        Environment::getInstance().isUseDepth(),
        Environment::getInstance().isUseStencil(),
        openglESVersion,
        Environment::getInstance().getPixelFormat()
    };

    auto newVideo = new Video(
        renderingOptions,
        fragmentShaderConfig,
        Environment::getInstance().isBottomLeftOrigin(),
        Environment::getInstance().getScreenRotation(),
        skipDuplicateFrames,
        immersiveModeEnabled,
        viewportRect,
        immersiveModeConfig,
        viewportAlignment
    );

    video = std::unique_ptr<Video>(newVideo);

    if (Environment::getInstance().getHwContextReset() != nullptr) {
        Environment::getInstance().getHwContextReset()();
    }
}

void LibretroDroid::onMotionEvent(
    unsigned int port,
    unsigned int source,
    float xAxis,
    float yAxis
) {
    LOGD("Received motion event: %d %.2f, %.2f", source, xAxis, yAxis);
    if (input) {
        input->onMotionEvent(port, source, xAxis, yAxis);
    }
}

void LibretroDroid::onTouchEvent(float xAxis, float yAxis) {
    LOGD("Received touch event: %.2f, %.2f", xAxis, yAxis);
    if (input && video) {
        auto [x, y] = video->getLayout().getRelativePosition(xAxis, yAxis);
        input->onMotionEvent(0, Input::MOTION_SOURCE_POINTER, x, y);
    }
}

void LibretroDroid::onKeyEvent(unsigned int port, int action, int keyCode) {
    LOGD("Received key event with action (%d) and keycode (%d)", action, keyCode);
    if (input) {
        input->onKeyEvent(port, action, keyCode);
    }
}

void LibretroDroid::create(
    unsigned int GLESVersion,
    const std::string& soFilePath,
    const std::string& systemDir,
    const std::string& savesDir,
    std::vector<Variable> variables,
    const ShaderManager::Config& shaderConfig,
    float refreshRate,
    bool lowLatencyAudio,
    bool enableVirtualFileSystem,
    bool enableMicrophone,
    bool duplicateFrames,
    std::optional<ImmersiveMode::Config> immersiveModeConfig,
    const std::string& language
) {
    LOGD("Performing libretrodroid create");

    resetGlobalVariables();

    Environment::getInstance().initialize(systemDir, savesDir, &callback_get_current_framebuffer);
    Environment::getInstance().setLanguage(language);
    Environment::getInstance().setEnableVirtualFileSystem(enableVirtualFileSystem);
    Environment::getInstance().setEnableMicrophone(enableMicrophone);

    openglESVersion = GLESVersion;
    screenRefreshRate = refreshRate;
    skipDuplicateFrames = duplicateFrames;
    immersiveModeEnabled = GLESVersion >= 3 && immersiveModeConfig.has_value();
    this->immersiveModeConfig = immersiveModeConfig.value_or(ImmersiveMode::Config{});
    audioEnabled = true;
    frameSpeed = 1;

    core = std::make_unique<Core>(soFilePath);

    core->retro_set_video_refresh(&callback_hw_video_refresh);
    core->retro_set_environment(&Environment::callback_environment);
    core->retro_set_audio_sample(&callback_audio_sample);
    core->retro_set_audio_sample_batch(&callback_set_audio_sample_batch);
    core->retro_set_input_poll(&callback_retro_set_input_poll);
    core->retro_set_input_state(&callback_set_input_state);

    std::for_each(variables.begin(), variables.end(), [&](const Variable& v) {
        updateVariable(v);
    });

    core->retro_init();

    preferLowLatencyAudio = lowLatencyAudio;

    // HW accelerated cores are only supported on opengles 3.
    if (Environment::getInstance().isUseHwAcceleration() && openglESVersion < 3) {
        throw LibretroDroidError("OpenGL ES 3 is required for this Core", ERROR_GL_NOT_COMPATIBLE);
    }

    fragmentShaderConfig = shaderConfig;

    rumble = std::make_unique<Rumble>();
}

void LibretroDroid::loadGameFromPath(const std::string& gamePath) {
    LOGD("Performing libretrodroid loadGameFromPath");
    struct retro_system_info system_info {};
    core->retro_get_system_info(&system_info);

    struct retro_game_info game_info {};
    game_info.path = Utils::cloneToCString(gamePath);
    game_info.meta = nullptr;

    if (system_info.need_fullpath) {
        game_info.data = nullptr;
        game_info.size = 0;
    } else {
        struct Utils::ReadResult file = Utils::readFileAsBytes(gamePath);
        game_info.data = file.data;
        game_info.size = file.size;
    }

    bool result = core->retro_load_game(&game_info);
    if (!result) {
        LOGE("Cannot load game. Leaving.");
        throw std::runtime_error("Cannot load game");
    }

    afterGameLoad();
}

void LibretroDroid::loadGameFromBytes(const int8_t *data, size_t size) {
    LOGD("Performing libretrodroid loadGameFromBytes");

    struct retro_system_info system_info {};
    core->retro_get_system_info(&system_info);

    struct retro_game_info game_info {};
    game_info.path = nullptr;
    game_info.meta = nullptr;

    if (system_info.need_fullpath) {
        game_info.data = nullptr;
        game_info.size = 0;
    } else {
        game_info.data = data;
        game_info.size = size;
    }

    bool result = core->retro_load_game(&game_info);
    if (!result) {
        LOGE("Cannot load game. Leaving.");
        throw std::runtime_error("Cannot load game");
    }

    afterGameLoad();
}

void LibretroDroid::loadGameFromVirtualFiles(std::vector<VFSFile> virtualFiles) {
    LOGD("Performing libretrodroid loadGameFromVirtualFiles");
    struct retro_system_info system_info {};
    core->retro_get_system_info(&system_info);

    if (virtualFiles.empty()) {
        LOGE("Calling loadGameFromVirtualFiles without any file.");
        throw std::runtime_error("Calling loadGameFromVirtualFiles without any file.");
    }

    std::string firstFilePath = virtualFiles[0].getFileName();
    int firstFileFD = virtualFiles[0].getFD();

    bool loadUsingVFS = system_info.need_fullpath || virtualFiles.size() > 1;

    struct retro_game_info game_info {};
    game_info.path = Utils::cloneToCString(firstFilePath);
    game_info.meta = nullptr;

    if (loadUsingVFS) {
        VFS::getInstance().initialize(std::move(virtualFiles));
    }

    if (loadUsingVFS) {
        game_info.data = nullptr;
        game_info.size = 0;
    } else {
        struct Utils::ReadResult file = Utils::readFileAsBytes(firstFileFD);
        game_info.data = file.data;
        game_info.size = file.size;
    }

    bool result = core->retro_load_game(&game_info);
    if (!result) {
        LOGE("Cannot load game. Leaving.");
        throw std::runtime_error("Cannot load game");
    }

    afterGameLoad();
}

void LibretroDroid::destroy() {
    netplay.stop();
    std::lock_guard<std::mutex> lock(coreLock);

    LOGD("Performing libretrodroid destroy");

    if (Environment::getInstance().getHwContextDestroy() != nullptr) {
        Environment::getInstance().getHwContextDestroy()();
    }

    core->retro_unload_game();
    core->retro_deinit();

    video = nullptr;
    core = nullptr;
    rumble = nullptr;
    fpsSync = nullptr;
    audio = nullptr;

    Environment::getInstance().deinitialize();
    VFS::getInstance().deinitialize();
}

void LibretroDroid::resume() {
    LOGD("Performing libretrodroid resume");

    input = std::make_unique<Input>();

    fpsSync->reset();
    audio->start();
    refreshAspectRatio();
}

void LibretroDroid::pause() {
    LOGD("Performing libretrodroid pause");
    audio->stop();

    input = nullptr;
}

void LibretroDroid::step() {
    std::lock_guard<std::mutex> lock(coreLock);

    LOGD("Stepping into retro_run()");

    if (netplay.isActive()) {
        // NeonDrive netplay: exactly one frame per tick, only when both players' buttons are known.
        if (fpsSync) fpsSync->advanceFrames();
        uint16_t ports[2] = { 0, 0 };
        if (netplay.prepareFrame(localJoypadMask(), ports, 12)) {
            netplayPorts[0] = ports[0];
            netplayPorts[1] = ports[1];
            netplayApplying = true;
            core->retro_run();
            netplayApplying = false;
            netplay.frameDone();
        } else {
            netplay.noteStall();
        }
    } else {
        unsigned frames = 1;
        if (fpsSync) {
            unsigned requestedFrames = fpsSync->advanceFrames();

            // If the application runs too slow it's better to just skip those frames.
            frames = std::min(requestedFrames, 2u);
        }

        for (size_t i = 0; i < frames * frameSpeed; i++)
            core->retro_run();
    }

    if (video && !video->rendersInVideoCallback()) {
        video->renderFrame();
    }

    if (fpsSync) {
        fpsSync->wait();
    }

    if (rumble && rumbleEnabled) {
        rumble->fetchFromEnvironment();
    }

    // Some games override the core geometry at runtime. These fields get updated in retro_run().
    if (video && Environment::getInstance().isGameGeometryUpdated()) {
        Environment::getInstance().clearGameGeometryUpdated();

        video->updateRendererSize(
            Environment::getInstance().getGameGeometryWidth(),
            Environment::getInstance().getGameGeometryHeight()
        );

        dirtyVideo = true;
    }

    if (video && Environment::getInstance().isScreenRotationUpdated()) {
        Environment::getInstance().clearScreenRotationUpdated();

        video->updateRotation(Environment::getInstance().getScreenRotation());
    }
}

float LibretroDroid::getAspectRatio() {
    float gameAspectRatio = Environment::getInstance().retrieveGameSpecificAspectRatio();
    return gameAspectRatio > 0 ? gameAspectRatio : defaultAspectRatio;
}

void LibretroDroid::refreshAspectRatio() {
    video->updateAspectRatio(getAspectRatio());
}

void LibretroDroid::setRumbleEnabled(bool enabled) {
    rumbleEnabled = enabled;
}

bool LibretroDroid::isRumbleEnabled() const {
    return rumbleEnabled;
}

void LibretroDroid::setFrameSpeed(unsigned int speed) {
    frameSpeed = speed;
    updateAudioSampleRateMultiplier();
}

void LibretroDroid::setAudioEnabled(bool enabled) {
    audioEnabled = enabled;
}

void LibretroDroid::setShaderConfig(ShaderManager::Config shaderConfig) {
    fragmentShaderConfig = std::move(shaderConfig);
    if (video) {
        video->updateShaderType(fragmentShaderConfig);
    }
}

void LibretroDroid::handleVideoRefresh(
    const void *data,
    unsigned int width,
    unsigned int height,
    size_t pitch
) {
    if (videoCaptureEnabled && data != nullptr && data != RETRO_HW_FRAME_BUFFER_VALID) {
        captureVideoFrame(data, width, height, pitch);
    }
    if (video) {
        video->onNewFrame(data, width, height, pitch);

        if (video->rendersInVideoCallback()) {
            video->renderFrame();
        }
    }
}

size_t LibretroDroid::handleAudioCallback(const int16_t *data, size_t frames) {
    if (audio && audioEnabled) {
        audio->write(data, frames);
    }
    if (captureEnabled) {
        std::lock_guard<std::mutex> lock(captureMutex);
        captureBuffer.insert(captureBuffer.end(), data, data + frames * 2);
        size_t maxSamples = (size_t) coreSampleRate * 2 * 3;   // keep at most ~3 s
        if (captureBuffer.size() > maxSamples) {
            captureBuffer.erase(captureBuffer.begin(), captureBuffer.begin() + (captureBuffer.size() - maxSamples));
        }
    }
    return frames;
}

void LibretroDroid::setAudioCapture(bool enabled) {
    std::lock_guard<std::mutex> lock(captureMutex);
    captureEnabled = enabled;
    captureBuffer.clear();
}

int LibretroDroid::readCapturedAudio(int16_t* out, int maxSamples) {
    std::lock_guard<std::mutex> lock(captureMutex);
    int n = std::min((int) captureBuffer.size(), maxSamples);
    n -= n % 2;   // whole stereo frames
    if (n <= 0) return 0;
    std::copy(captureBuffer.begin(), captureBuffer.begin() + n, out);
    captureBuffer.erase(captureBuffer.begin(), captureBuffer.begin() + n);
    return n;
}

void LibretroDroid::setVideoCapture(bool enabled) {
    std::lock_guard<std::mutex> lock(videoCaptureMutex);
    videoCaptureEnabled = enabled;
    if (!enabled) { captureFrame.clear(); captureFrame.shrink_to_fit(); captureW = captureH = 0; }
}

// Copies one emulator frame (any libretro pixel format) as 0x00RRGGBB. Runs on the emulation thread,
// only a few hundred KB per frame, so it never slows the game.
void LibretroDroid::captureVideoFrame(const void* data, unsigned width, unsigned height, size_t pitch) {
    std::lock_guard<std::mutex> lock(videoCaptureMutex);
    if (width == 0 || height == 0) return;
    captureW = (int) width; captureH = (int) height;
    captureFrame.resize((size_t) width * height);
    int format = Environment::getInstance().getPixelFormat();
    const uint8_t* src = static_cast<const uint8_t*>(data);
    for (unsigned y = 0; y < height; y++) {
        const uint8_t* row = src + y * pitch;
        uint32_t* out = &captureFrame[(size_t) y * width];
        if (format == RETRO_PIXEL_FORMAT_XRGB8888) {
            const uint32_t* p = reinterpret_cast<const uint32_t*>(row);
            for (unsigned x = 0; x < width; x++) out[x] = p[x] & 0x00FFFFFF;
        } else if (format == RETRO_PIXEL_FORMAT_RGB565) {
            const uint16_t* p = reinterpret_cast<const uint16_t*>(row);
            for (unsigned x = 0; x < width; x++) {
                uint16_t v = p[x];
                uint32_t r = (v >> 11) & 0x1F, g = (v >> 5) & 0x3F, b = v & 0x1F;
                out[x] = ((r << 3 | r >> 2) << 16) | ((g << 2 | g >> 4) << 8) | (b << 3 | b >> 2);
            }
        } else { // 0RGB1555
            const uint16_t* p = reinterpret_cast<const uint16_t*>(row);
            for (unsigned x = 0; x < width; x++) {
                uint16_t v = p[x];
                uint32_t r = (v >> 10) & 0x1F, g = (v >> 5) & 0x1F, b = v & 0x1F;
                out[x] = ((r << 3 | r >> 2) << 16) | ((g << 3 | g >> 2) << 8) | (b << 3 | b >> 2);
            }
        }
    }
    captureSeq++;
}

int64_t LibretroDroid::readCapturedFrameI420(uint8_t* dst, int dstW, int dstH) {
    std::vector<uint32_t> frame;
    int w, h;
    int64_t seq;
    {
        std::lock_guard<std::mutex> lock(videoCaptureMutex);
        if (captureFrame.empty() || captureW == 0 || captureH == 0) return -1;
        frame = captureFrame; w = captureW; h = captureH; seq = captureSeq;
    }
    uint8_t* yPlane = dst;
    uint8_t* uPlane = dst + dstW * dstH;
    uint8_t* vPlane = uPlane + (dstW / 2) * (dstH / 2);
    for (int y = 0; y < dstH; y++) {
        const uint32_t* srcRow = &frame[(size_t) ((int64_t) y * h / dstH) * w];
        uint8_t* yRow = yPlane + y * dstW;
        for (int x = 0; x < dstW; x++) {
            uint32_t c = srcRow[(int64_t) x * w / dstW];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            yRow[x] = (uint8_t) (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
            if ((y & 1) == 0 && (x & 1) == 0) {
                int i = (y / 2) * (dstW / 2) + (x / 2);
                uPlane[i] = (uint8_t) (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
                vPlane[i] = (uint8_t) (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
            }
        }
    }
    return seq;
}

std::array<float, 4> LibretroDroid::getForegroundBounds() {
    if (!video) return { 0.0F, 0.0F, 1.0F, 1.0F };
    return video->getForegroundBounds();
}

int16_t LibretroDroid::handleSetInputState(
    unsigned int port,
    unsigned int device,
    unsigned int index,
    unsigned int id
) {
    if (netplayApplying) {
        // Both players come from the netplay exchange; nothing else may influence the game.
        if (device != RETRO_DEVICE_JOYPAD || port > 1) return 0;
        if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t) netplayPorts[port];
        return id < 16 ? (int16_t) ((netplayPorts[port] >> id) & 1) : 0;
    }
    if (input) {
        return input->getInputState(port, device, index, id);
    }
    return 0;
}

// This phone's player is always on port 0 of the touch/gamepad input.
uint16_t LibretroDroid::localJoypadMask() {
    if (!input) return 0;
    uint16_t mask = 0;
    for (unsigned id = 0; id < 16; id++) {
        if (input->getInputState(0, RETRO_DEVICE_JOYPAD, 0, id)) mask |= (uint16_t) (1u << id);
    }
    return mask;
}

uintptr_t LibretroDroid::handleGetCurrentFrameBuffer() {
    if (video) {
        return video->getCurrentFramebuffer();
    }
    return 0;
}

void LibretroDroid::reset() {
    std::lock_guard<std::mutex> lock(coreLock);

    core->retro_reset();
}

std::pair<int8_t*, size_t> LibretroDroid::serializeState() {
    std::lock_guard<std::mutex> lock(coreLock);

    size_t size = core->retro_serialize_size();
    auto data = new int8_t[size];

    core->retro_serialize(data, size);

    return std::pair(data, size);
}

void LibretroDroid::resetCheat() {
    std::lock_guard<std::mutex> lock(coreLock);

    core->retro_cheat_reset();
}

void LibretroDroid::setCheat(unsigned index, bool enabled, const std::string& code) {
    std::lock_guard<std::mutex> lock(coreLock);

    core->retro_cheat_set(index, enabled, Utils::cloneToCString(code));
}

bool LibretroDroid::requiresVideoRefresh() const {
    return dirtyVideo;
}

void LibretroDroid::clearRequiresVideoRefresh() {
    dirtyVideo = false;
}

void LibretroDroid::afterGameLoad() {
    struct retro_system_av_info system_av_info {};
    core->retro_get_system_av_info(&system_av_info);

    fpsSync = std::make_unique<FPSSync>(system_av_info.timing.fps, screenRefreshRate);
    coreSampleRate = (int) std::lround(system_av_info.timing.sample_rate);

    double inputSampleRate = system_av_info.timing.sample_rate * fpsSync->getTimeStretchFactor();

    audio = std::make_unique<Audio>(
        (int32_t) std::lround(inputSampleRate),
        system_av_info.timing.fps,
        preferLowLatencyAudio
    );

    updateAudioSampleRateMultiplier();

    defaultAspectRatio = findDefaultAspectRatio(system_av_info);
}

float LibretroDroid::findDefaultAspectRatio(const retro_system_av_info& system_av_info) {
    float result = system_av_info.geometry.aspect_ratio;
    if (result < 0) {
        result =
            (float) system_av_info.geometry.base_width / (float) system_av_info.geometry.base_height;
    }
    return result;
}

void LibretroDroid::handleRumbleUpdates(const std::function<void(int, float, float)> &handler) {
    if (rumble && rumbleEnabled) {
        rumble->handleRumbleUpdates(handler);
    }
}

void LibretroDroid::setViewport(Rect viewportRect) {
    this->viewportRect = viewportRect;

    if (video != nullptr) {
        video->updateViewportSize(viewportRect);
    }
}

void LibretroDroid::setViewportAlignment(unsigned int viewportAlignment) {
    this-> viewportAlignment = viewportAlignment;

    if (video) {
        this->video->updateViewportAlignment(viewportAlignment);
    }
}

} //namespace libretrodroid
