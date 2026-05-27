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

#include "imagerendereres2.h"
#include "../../libretro-common/include/libretro.h"

namespace libretrodroid {

ImageRendererES2::ImageRendererES2() {
    glGenTextures(1, &currentTexture);
    glBindTexture(GL_TEXTURE_2D, currentTexture);
}

void ImageRendererES2::onNewFrame(const void *data, unsigned width, unsigned height, size_t pitch) {
    glBindTexture(GL_TEXTURE_2D, currentTexture);

    // Use the tightest valid alignment for this row stride (mirrors RetroArch gl2_get_alignment).
    auto rowBytes = static_cast<size_t>(pitch);
    unsigned int alignment = (rowBytes & 1u) ? 1 : (rowBytes & 2u) ? 2 : (rowBytes & 4u) ? 4 : 8;
    glPixelStorei(GL_UNPACK_ALIGNMENT, alignment);

    if (lastFrameSize.first != width || lastFrameSize.second != height) {
        glTexImage2D(GL_TEXTURE_2D, 0, glInternalFormat, width, height, 0, glFormat, glType, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, linear ? GL_LINEAR : GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, linear ? GL_LINEAR : GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    if (pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888) {
        // Cannot modify the core's const buffer in-place — use a dedicated conversion buffer.
        convertDataFromRGB8888ToTemp(data, width, height, pitch);
        if (bytesPerPixel * width == pitch) {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, glFormat, glType, conversionBuffer.data());
        } else {
            for (unsigned int i = 0; i < height; i++) {
                auto row = conversionBuffer.data() + width * bytesPerPixel * i;
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, i, width, 1, glFormat, glType, row);
            }
        }
    } else if (pixelFormat == RETRO_PIXEL_FORMAT_0RGB1555) {
        convertDataFrom0RGB1555ToTemp(data, width, height, pitch);
        if (bytesPerPixel * width == pitch) {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, glFormat, glType, conversionBuffer.data());
        } else {
            for (unsigned int i = 0; i < height; i++) {
                auto row = conversionBuffer.data() + width * bytesPerPixel * i;
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, i, width, 1, glFormat, glType, row);
            }
        }
    } else {
        if (bytesPerPixel * width == pitch) {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, glFormat, glType, data);
        } else {
            for (unsigned int i = 0; i < height; i++) {
                auto row = static_cast<const char*>(data) + pitch * i;
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, i, width, 1, glFormat, glType, row);
            }
        }
    }

    glBindTexture(GL_TEXTURE_2D, 0);

    Renderer::onNewFrame(data, width, height, pitch);
}

// Converts XRGB8888 (little-endian BGRX) → RGBA into conversionBuffer.
// Never touches the core's const data pointer.
void ImageRendererES2::convertDataFromRGB8888ToTemp(
    const void *data, unsigned int width, unsigned int height, size_t pitch
) {
    conversionBuffer.resize(width * height * 4);
    const auto* src = static_cast<const uint8_t*>(data);
    uint8_t* dst = conversionBuffer.data();
    for (unsigned int y = 0; y < height; ++y) {
        const uint8_t* row = src + pitch * y;
        for (unsigned int x = 0; x < width; ++x, row += 4, dst += 4) {
            dst[0] = row[2]; // R ← B channel in XRGB8888 LE
            dst[1] = row[1]; // G
            dst[2] = row[0]; // B ← R channel in XRGB8888 LE
            dst[3] = 0xFF;
        }
    }
}

// Converts 0RGB1555 → RGB565 into conversionBuffer.
// Never touches the core's const data pointer.
// Fills the extra G LSB from the original G MSB (RetroArch conv_0rgb1555_rgb565 trick).
void ImageRendererES2::convertDataFrom0RGB1555ToTemp(
    const void *data, unsigned int width, unsigned int height, size_t pitch
) {
    conversionBuffer.resize(width * height * 2);
    auto* dst = reinterpret_cast<uint16_t*>(conversionBuffer.data());
    for (unsigned int y = 0; y < height; ++y) {
        const auto* row = reinterpret_cast<const uint16_t*>(
            static_cast<const uint8_t*>(data) + pitch * y);
        uint16_t* dstRow = dst + y * width;
        for (unsigned int x = 0; x < width; ++x) {
            uint16_t col = row[x];
            uint16_t rg   = (col << 1u) & static_cast<uint16_t>((0x1Fu << 11u) | (0x1Fu << 6u));
            uint16_t b    = col & 0x1Fu;
            uint16_t glow = (col >> 4u) & (1u << 5u); // fill LSB of 6-bit G
            dstRow[x] = rg | b | glow;
        }
    }
}

uintptr_t ImageRendererES2::getTexture() {
    return currentTexture;
}

uintptr_t ImageRendererES2::getFramebuffer() {
    return 0; // ImageRender does not really expose a framebuffer.
}

void ImageRendererES2::setPixelFormat(int pixelFormat) {
    this->pixelFormat = pixelFormat;

    switch (pixelFormat) {
        case RETRO_PIXEL_FORMAT_XRGB8888:
            this->glInternalFormat = GL_RGBA;
            this->glFormat = GL_RGBA;
            this->glType = GL_UNSIGNED_BYTE;
            this->bytesPerPixel = 4;
            break;

        default:
        case RETRO_PIXEL_FORMAT_0RGB1555:
        case RETRO_PIXEL_FORMAT_RGB565:
            this->glInternalFormat = GL_RGB;
            this->glFormat = GL_RGB;
            this->glType = GL_UNSIGNED_SHORT_5_6_5;
            this->bytesPerPixel = 2;
            break;
    }
}



void ImageRendererES2::updateRenderedResolution(unsigned int width, unsigned int height) {}

bool ImageRendererES2::rendersInVideoCallback() {
    return false;
}

void ImageRendererES2::setShaders(ShaderManager::Chain shaders) {
    this->linear = shaders.linearTexture;
}

// ES2 Renderer doesn't currently support multiple passes.
Renderer::PassData ImageRendererES2::getPassData(unsigned int layer) {
    return { };
}

} //namespace libretrodroid
