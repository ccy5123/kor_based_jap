#pragma once
#include <windows.h>
#include <cstdint>
#include <string>

// ----------------------------------------------------------------------------
// Connector — read-only access to the bigram connection-cost matrix shipped
// in kj_conn.bin (built by dict/build_viterbi_data.py from Mozc OSS).
//
// The matrix is indexed by (left_token.rid, right_token.lid) and gives the
// transition cost (lower = more grammatical) between two adjacent tokens
// in a viterbi lattice.  Together with the per-entry word cost from
// RichDictionary, this is what lets us pick "私 + の" over "渡志野" when
// segmenting わたしの.
//
// File layout (little-endian, see dict/build_viterbi_data.py for the spec):
//
//     char[4]  magic    = b"KJCN"
//     uint32   version  = 1
//     uint16   dim
//     uint16   _pad
//     uint32   _reserved
//     int16    matrix[dim * dim]      // row-major, cost[lid * dim + rid]
//
// Memory model: the file is mmap-ed read-only into the process and lives
// for the lifetime of the Connector instance.  Cost lookup is a single
// array read so it's safe to call from inside the inner loop of the
// viterbi expansion.
// ----------------------------------------------------------------------------
class Connector {
public:
    Connector() = default;
    ~Connector();

    Connector(const Connector&)            = delete;
    Connector& operator=(const Connector&) = delete;

    // Open the binary at `utf16Path` and validate its header.  Returns true
    // on success; on failure the connector stays empty (Cost() will return
    // a defensive max value so callers see "very bad transition" without
    // crashing).
    bool Load(const wchar_t *utf16Path);

    bool IsLoaded() const { return _data != nullptr; }
    uint16_t Dim()  const { return _dim; }

    // Bigram transition cost from left token (rid = leftRid) to right
    // token (lid = rightLid).  Returns INT16_MAX when out-of-range or
    // the connector failed to load -- safe to add into a viterbi score
    // path because nothing legitimate ever beats max + max.
    int16_t Cost(uint16_t leftRid, uint16_t rightLid) const {
        if (!_data || leftRid >= _dim || rightLid >= _dim) {
            return INT16_MAX;
        }
        return _data[static_cast<size_t>(leftRid) * _dim + rightLid];
    }

private:
    void _Close();

    HANDLE   _file   = INVALID_HANDLE_VALUE;
    HANDLE   _map    = nullptr;
    void    *_view   = nullptr;
    size_t   _size   = 0;
    const int16_t *_data = nullptr;   // points inside _view, after the header
    uint16_t _dim    = 0;
};
