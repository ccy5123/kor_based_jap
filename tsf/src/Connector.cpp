#include "Connector.h"
#include "DebugLog.h"
#include <cstring>

namespace {

// On-disk header layout (must match dict/build_viterbi_data.py).
//
// Compiler must lay these out tightly with no padding -- single-byte and
// 2-/4-byte primitives in this exact order.  We rely on default x86-64
// alignment which puts uint32 on 4-byte boundaries; the layout in the
// builder was chosen so that holds without #pragma pack tricks.
struct Header {
    char     magic[4];   // 'KJCN'
    uint32_t version;    // 1
    uint16_t dim;
    uint16_t pad;
    uint32_t reserved;
};
static_assert(sizeof(Header) == 16, "Connector header layout drift");

constexpr char     kMagic[4]    = { 'K', 'J', 'C', 'N' };
constexpr uint32_t kVersion     = 1;

} // namespace

Connector::~Connector() { _Close(); }

void Connector::_Close() {
    if (_view) UnmapViewOfFile(_view);
    if (_map)  CloseHandle(_map);
    if (_file != INVALID_HANDLE_VALUE) CloseHandle(_file);
    _view = nullptr;
    _map  = nullptr;
    _file = INVALID_HANDLE_VALUE;
    _data = nullptr;
    _dim  = 0;
    _size = 0;
}

bool Connector::Load(const wchar_t *utf16Path) {
    _Close();

    _file = CreateFileW(utf16Path, GENERIC_READ, FILE_SHARE_READ, nullptr,
                        OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (_file == INVALID_HANDLE_VALUE) {
        DBGF("Connector::Load CreateFileW failed err=%lu (%ls)",
             (unsigned long)GetLastError(), utf16Path);
        return false;
    }

    LARGE_INTEGER li;
    if (!GetFileSizeEx(_file, &li) || li.QuadPart < (LONGLONG)sizeof(Header)) {
        DBGF("Connector::Load file too small or stat failed (%ls)", utf16Path);
        _Close();
        return false;
    }
    _size = (size_t)li.QuadPart;

    _map = CreateFileMappingW(_file, nullptr, PAGE_READONLY, 0, 0, nullptr);
    if (!_map) {
        DBGF("Connector::Load CreateFileMappingW failed err=%lu",
             (unsigned long)GetLastError());
        _Close();
        return false;
    }

    _view = MapViewOfFile(_map, FILE_MAP_READ, 0, 0, 0);
    if (!_view) {
        DBGF("Connector::Load MapViewOfFile failed err=%lu",
             (unsigned long)GetLastError());
        _Close();
        return false;
    }

    const Header *hdr = reinterpret_cast<const Header *>(_view);
    if (std::memcmp(hdr->magic, kMagic, 4) != 0) {
        DBG("Connector::Load magic mismatch -- not a kj_conn.bin file");
        _Close();
        return false;
    }
    if (hdr->version != kVersion) {
        DBGF("Connector::Load version=%u (expected %u)",
             (unsigned)hdr->version, (unsigned)kVersion);
        _Close();
        return false;
    }
    if (hdr->dim == 0) {
        DBG("Connector::Load dim=0");
        _Close();
        return false;
    }

    const size_t expected =
        sizeof(Header) + (size_t)hdr->dim * hdr->dim * sizeof(int16_t);
    if (_size < expected) {
        DBGF("Connector::Load file too small: have %zu, need %zu", _size, expected);
        _Close();
        return false;
    }

    _dim  = hdr->dim;
    _data = reinterpret_cast<const int16_t *>(
        static_cast<const char *>(_view) + sizeof(Header));

    DBGF("Connector::Load OK dim=%u file_size=%zu", (unsigned)_dim, _size);
    return true;
}
