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

#include <iostream>
#include <fstream>
#include <cerrno>
#include <unistd.h>

#include "utils.h"
#include "../log.h"

namespace libretrodroid {

Utils::ReadResult Utils::readFileAsBytes(const std::string &filePath) {
    std::ifstream fileStream(filePath);
    fileStream.seekg(0, std::ios::end);
    size_t size = fileStream.tellg();
    char* bytes = new char[size];
    fileStream.seekg(0, std::ios::beg);
    fileStream.read(bytes, size);
    fileStream.close();

    return ReadResult { size, bytes };
}

Utils::ReadResult Utils::readFileAsBytes(const int fileDescriptor) {
    // dup() the fd so that fdopen/fclose operate on our own copy and do NOT take ownership
    // of the caller's fd.  Without this, the FDWrapper that holds fileDescriptor would
    // call close() on an fd already closed by fclose() → fdsan SIGABRT on Android 11+.
    int workFd = ::dup(fileDescriptor);
    if (workFd < 0) {
        LOGE("readFileAsBytes: dup() failed for fd=%d (errno=%d)", fileDescriptor, errno);
        return ReadResult { 0, nullptr };
    }

    FILE* file = fdopen(workFd, "r");
    if (!file) {
        LOGE("readFileAsBytes: fdopen() failed for fd=%d (errno=%d)", workFd, errno);
        ::close(workFd);
        return ReadResult { 0, nullptr };
    }

    size_t size = getFileSize(file);
    char* bytes = new char[size];
    fread(bytes, sizeof(char), size, file);
    fclose(file);  // closes workFd — the original fileDescriptor is untouched
    return ReadResult { size, bytes };
}

size_t Utils::getFileSize(FILE* file) {
    fseek(file, 0, SEEK_SET);
    fseek(file, 0, SEEK_END);
    size_t size = ftell(file);
    fseek(file, 0, SEEK_SET);
    return size;
}

const char* Utils::cloneToCString(const std::string &input) {
    char* result = new char[input.length() + 1];
    std::strcpy(result, input.c_str());
    return result;
}

} //namespace libretrodroid