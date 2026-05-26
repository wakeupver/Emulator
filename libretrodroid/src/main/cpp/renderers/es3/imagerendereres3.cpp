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

#include "imagerendereres3.h"
#include "../../libretro-common/include/libretro.h"
#include "es3utils.h"

namespace libretrodroid {

ImageRendererES3::ImageRendererES3() {
    glGenTextures(1, &currentTexture);
    glBindTexture(GL_TEXTURE_2D, currentTexture);
}

// Compute the tightest valid GL_UNPACK_ALIGNMENT for a given row byte-width.
// Mirrors RetroArch's gl2_get_alignment(). GLES requires power-of-two (1/2/4/8).
static unsigned int glUnpackAlignment(size_t pitchBytes) {
    if (pitchBytes & 1u) return 1;
    if (pitchBytes & 2u) return 2;
    if (pitchBytes & 4u) return 4;
    return 8;
}

void ImageRendererES3::onNewFrame(const void *data, unsigned width, unsigned height, size_t pitch) {
    const void* uploadData = data;

    if (pixelFormat == RETRO_PIXEL_FORMAT_0RGB1555) {
        // Convert into a temporary buffer — do NOT touch the core's const buffer.
        convertDataFrom0RGB1555ToTemp(data, width, height, pitch);
        uploadData = conversionBuffer.data();
        // After conversion the row stride is tightly packed (width * 2 bytes).
        pitch = width * bytesPerPixel;
    }

    if (lastFrameSize.first != width || lastFrameSize.second != height || isDirty) {
        initializeTextures(width, height);
    }

    glBindTexture(GL_TEXTURE_2D, currentTexture);

    glPixelStorei(GL_UNPACK_ALIGNMENT, glUnpackAlignment(pitch));
    glPixelStorei(GL_UNPACK_ROW_LENGTH, pitch / bytesPerPixel);

    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, glFormat, glType, uploadData);

    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);

    glBindTexture(GL_TEXTURE_2D, 0);

    Renderer::onNewFrame(data, width, height, pitch);
}

void ImageRendererES3::initializeTextures(unsigned int width, unsigned int height) {
    for (auto& i : *framebuffers) {
        ES3Utils::deleteFramebuffer(std::move(i));
    }
    framebuffers = libretrodroid::ES3Utils::buildShaderPasses(width, height, shaders);

    glBindTexture(GL_TEXTURE_2D, currentTexture);
    glTexImage2D(GL_TEXTURE_2D, 0, glInternalFormat, width, height, 0, glFormat, glType, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, shaders.linearTexture ? GL_LINEAR : GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, shaders.linearTexture ? GL_LINEAR : GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    if (swapRedAndBlueChannels) {
        applyGLSwizzle(GL_BLUE, GL_GREEN, GL_RED, GL_ALPHA);
    } else {
        applyGLSwizzle(GL_RED, GL_GREEN, GL_BLUE, GL_ALPHA);
    }

    glBindTexture(GL_TEXTURE_2D, 0);

    isDirty = false;
}

void ImageRendererES3::applyGLSwizzle(int r, int g, int b, int a) {
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_R, r);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_G, g);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_B, b);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_A, a);
}

uintptr_t ImageRendererES3::getTexture() {
    return currentTexture;
}

uintptr_t ImageRendererES3::getFramebuffer() {
    return 0; // ImageRender does not really expose a framebuffer.
}

void ImageRendererES3::setPixelFormat(int pixelFormat) {
    this->pixelFormat = pixelFormat;

    switch (pixelFormat) {

        case RETRO_PIXEL_FORMAT_XRGB8888:
            this->glInternalFormat = GL_RGBA;
            this->glFormat = GL_RGBA;
            this->glType = GL_UNSIGNED_BYTE;
            this->bytesPerPixel = 4;
            this->swapRedAndBlueChannels = true;
            break;

        default:
        case RETRO_PIXEL_FORMAT_0RGB1555:
        case RETRO_PIXEL_FORMAT_RGB565:
            this->glInternalFormat = GL_RGB565;
            this->glFormat = GL_RGB;
            this->glType = GL_UNSIGNED_SHORT_5_6_5;
            this->bytesPerPixel = 2;
            this->swapRedAndBlueChannels = false;
            break;
    }
}

void ImageRendererES3::convertDataFrom0RGB1555ToTemp(
    const void *data, unsigned int width, unsigned int height, size_t pitch
) {
    conversionBuffer.resize(width * height * 2);
    auto* dst = reinterpret_cast<uint16_t*>(conversionBuffer.data());

    for (unsigned int y = 0; y < height; ++y) {
        const auto* row = reinterpret_cast<const uint16_t*>(
            static_cast<const uint8_t*>(data) + pitch * y
        );
        uint16_t* dstRow = dst + y * width;
        for (unsigned int x = 0; x < width; ++x) {
            uint16_t col = row[x];
            // Shift R and G up by 1 bit to expand into RGB565.
            uint16_t rg   = (col << 1u) & static_cast<uint16_t>((0x1Fu << 11u) | (0x1Fu << 6u));
            uint16_t b    = col & 0x1Fu;
            // Fill the new LSB of G from the MSB of the original 5-bit G
            // (RetroArch conv_0rgb1555_rgb565 trick) to prevent green banding.
            uint16_t glow = (col >> 4u) & (1u << 5u);
            dstRow[x] = rg | b | glow;
        }
    }
}

void ImageRendererES3::updateRenderedResolution(unsigned int width, unsigned int height) {}

bool ImageRendererES3::rendersInVideoCallback() {
    return false;
}

void ImageRendererES3::setShaders(ShaderManager::Chain newShaders) {
    this->shaders = newShaders;
    this->isDirty = true;
}

Renderer::PassData ImageRendererES3::getPassData(unsigned int layer) {
    PassData result;

    if (layer >= 0 && layer < framebuffers->size()) {
        result.framebuffer = framebuffers->at(layer)->framebuffer;
        result.width = framebuffers->at(layer)->width;
        result.height = framebuffers->at(layer)->height;
    }

    if (layer > 0 && layer < framebuffers->size() + 1) {
        result.texture = framebuffers->at(layer - 1)->texture;
    }

    return result;
}

} //namespace libretrodroid
