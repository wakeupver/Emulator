/*
 *     Copyright (C) 2021  Filippo Scognamiglio
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

#include "fdwrapper.h"
#include "../log.h"

// FDWrapper: always dup() the incoming fd so we own an untagged copy.
// This prevents Android fdsan from aborting when we close() a fd that was
// originally owned by a ParcelFileDescriptor / unique_fd on the Java side.
// The caller retains (or releases) the original fd independently.
libretrodroid::FDWrapper::FDWrapper(int rawFd) : fd(::dup(rawFd)) {
    if (fd < 0) {
        LOGE("FDWrapper: dup() failed for fd=%d (errno=%d)", rawFd, errno);
    }
}

libretrodroid::FDWrapper::~FDWrapper() {
    if (fd > 0) {
        close(fd);
        fd = -1;
    }
}

int libretrodroid::FDWrapper::getFD() {
    return fd;
}
