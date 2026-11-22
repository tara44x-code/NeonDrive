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

#ifndef LIBRETRODROID_VIDEO_H
#define LIBRETRODROID_VIDEO_H

#include <GLES2/gl2.h>
#include <functional>
#include <mutex>
#include <vector>
#include <array>
#include <optional>
#include <array>

#include "renderers/renderer.h"
#include "shadermanager.h"
#include "utils/rect.h"
#include "immersivemode.h"
#include "videolayout.h"

namespace libretrodroid {

class Video {
public:
    /** NeonDrive: game area on screen, 0..1, bottom-left origin: xMin, yMin, xMax, yMax. */
    std::array<float, 4> getForegroundBounds() { return videoLayout.getRelativeForegroundBounds(); }

    /** NeonDrive: size in pixels of the game picture as shown on screen. */
    std::pair<int, int> getDisplayedSize() {
        auto b = videoLayout.getRelativeForegroundBounds();
        return { (int) ((b[2] - b[0]) * videoLayout.getScreenWidth()), (int) ((b[3] - b[1]) * videoLayout.getScreenHeight()) };
    }

    /** NeonDrive: rows are bottom-up RGBA, exactly as the shaders drew them. */
    using CaptureSink = std::function<void(const uint8_t* rgba, int w, int h)>;
    /** Draws the final (filtered) pass again at w x h for the video recorder; w = 0 stops it. */
    void setFilteredCapture(int w, int h, CaptureSink sink);

    struct RenderingOptions {
        bool hardwareAccelerated = false;
        unsigned int width;
        unsigned int height;
        bool useDepth;
        bool useStencil;
        int openglESVersion;
        int pixelFormat;
    };

    struct ShaderChainEntry {
        GLint gProgram = 0;
        GLint gvPositionHandle = 0;
        GLint gvCoordinateHandle = 0;
        GLint gTextureHandle = 0;
        GLint gPreviousPassTextureHandle = 0;
        GLint gScreenDensityHandle = 0;
        GLint gTextureSizeHandle = 0;
    };

    Video(
        RenderingOptions renderingOptions,
        ShaderManager::Config shaderConfig,
        bool bottomLeftOrigin,
        float rotation,
        bool skipDuplicateFrames,
        bool immersiveMode,
        Rect viewportRect,
        ImmersiveMode::Config immersiveModeConfig,
        unsigned int viewportAlignment
    );

    VideoLayout& getLayout() { return videoLayout; }

    void updateAspectRatio(float aspectRatio);
    void updateScreenSize(unsigned screenWidth, unsigned screenHeight);
    void updateViewportSize(Rect viewportRect);
    void updateViewportAlignment(unsigned int viewportAlignment);
    void updateRendererSize(unsigned width, unsigned height);
    void updateRotation(float rotation);
    void updateShaderType(ShaderManager::Config shaderConfig);

    void renderFrame();

    void onNewFrame(const void *data, unsigned width, unsigned height, size_t pitch);

    uintptr_t getCurrentFramebuffer() {
        return renderer->getFramebuffer();
    };

    bool rendersInVideoCallback() {
        return renderer->rendersInVideoCallback();
    }

private:
    void updateProgram();
    void drawPass(int i, unsigned int framebuffer, int viewW, int viewH, const float* vertices, float density);
    void captureFiltered();
    void releaseCapture();

    std::mutex capLock;
    int capReqW = 0, capReqH = 0;
    CaptureSink capSink;
    int capW = 0, capH = 0;
    GLuint capFbo = 0, capTex = 0;
    GLuint capPbo[2] = { 0, 0 };
    int capPboIdx = 0;
    bool capPboReady = false;
    bool capFailed = false;
    std::vector<uint8_t> capTmp;
    std::array<float, 12> capVertices {};
    int glesVersion = 2;

    float getScreenDensity();
    float getTextureWidth();
    float getTextureHeight();

    void initializeRenderer(RenderingOptions renderingOptions);

private:
    ShaderManager::Config requestedShaderConfig = ShaderManager::Config {
        ShaderManager::Type::SHADER_DEFAULT
    };
    std::optional<ShaderManager::Config> loadedShaderType = std::nullopt;

    bool isDirty = false;
    bool skipDuplicateFrames = false;

    std::vector<ShaderChainEntry> shadersChain;

    bool immersiveModeEnabled = false;
    ImmersiveMode immersiveMode;
    VideoLayout videoLayout;

    Renderer* renderer;
};

}

#endif //LIBRETRODROID_VIDEO_H
