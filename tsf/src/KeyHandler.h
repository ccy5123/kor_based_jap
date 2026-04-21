#pragma once
#include "Globals.h"
#include <string>

// ----- Korean 2-beolsik composer -------------------------------------------
// State machine that converts a sequence of jamo inputs into complete
// Hangul syllables. Syllables are output to a callback when complete.

class HangulComposer {
public:
    HangulComposer();

    // Sentinel character (Unicode private-use area) returned from input()
    // when a Korean keystroke pattern with no native meaning is repurposed
    // to emit a Japanese-only kana.  The call site translates the marker to
    // the real kana before appending to the pending buffer.
    //
    //   kWoMarker = ㅇ-ㅗ-ㅗ -> を   (Japanese object particle "wo")
    //               ㅇ-ㅗ-ㅇ-ㅗ stays as 오오 -> おお (long vowel intact)
    //
    // The marker is never displayed to the user -- it lives only in the
    // brief gap between HangulComposer::input() returning and the call site
    // mapping it to actual kana.
    static constexpr wchar_t kWoMarker = L'\uE000';

    // Feed one jamo (or Latin key). Returns:
    //   - completed syllable if one was just finished (NFC unicode char as wstring)
    //   - kWoMarker if a special pattern fired (caller must translate)
    //   - empty string if still composing
    // Call flush() at word boundary to retrieve any in-progress syllable.
    std::wstring input(wchar_t jamo);
    std::wstring flush();   // complete current syllable and reset
    std::wstring preedit(); // current in-progress syllable (for display)
    bool empty() const;
    void reset();

    // Per-jamo backspace: drops the most recently added piece of state — a
    // compound jong (ㄳ → ㄱ), a compound vowel (ㅘ → ㅗ), the jong, the jung,
    // or finally the cho.  Returns true if anything was actually undone.
    bool undoLastJamo();

private:
    // Indexes into the Hangul Unicode tables
    static constexpr int CHO_COUNT  = 19;
    static constexpr int JUNG_COUNT = 21;
    static constexpr int JONG_COUNT = 28;

    // jamo classification
    static int  choIndex(wchar_t jamo);   // -1 if not a valid initial
    static int  jungIndex(wchar_t jamo);  // -1 if not a vowel
    static int  jongIndex(wchar_t jamo);  // -1 if not a valid final
    static int  choFromJong(int jongIdx); // remap jong->cho for syllable split
    static wchar_t compoundVowel(wchar_t v1, wchar_t v2); // ㅗ+ㅏ=ㅘ, 0 if none
    static int     compoundJong(wchar_t j1, wchar_t j2);  // returns jong index (1-27), 0 if none

    wchar_t compose() const; // current (cho, jung, jong) -> syllable codepoint

    int _cho  = -1;  // 초성 index, -1 = none
    int _jung = -1;  // 중성 index
    int _jong = -1;  // 종성 index (single consonant only for skeleton)
    // Raw jamo kept for compound vowel/consonant tracking
    wchar_t _rawJung = 0;
    wchar_t _rawJong = 0;
};

// ----- VK → jamo mapping (Korean 2-beolsik) --------------------------------
// Returns 0 if the VK is not part of the Korean keyboard.
wchar_t VkToJamo(UINT vk, bool shifted);

// ----- ITfKeyEventSink implementation --------------------------------------
class KeyHandler : public ITfKeyEventSink {
public:
    explicit KeyHandler(class KorJpnIme *pIme);
    ~KeyHandler();

    // Attach/detach from context
    HRESULT Advise(ITfThreadMgr *pThreadMgr, TfClientId tid);
    HRESULT Unadvise(ITfThreadMgr *pThreadMgr);

    // IUnknown
    STDMETHODIMP QueryInterface(REFIID, void**) override;
    STDMETHODIMP_(ULONG) AddRef()  override;
    STDMETHODIMP_(ULONG) Release() override;

    // ITfKeyEventSink
    STDMETHODIMP OnSetFocus(BOOL fForeground) override;
    STDMETHODIMP OnTestKeyDown(ITfContext*, WPARAM, LPARAM, BOOL *pfEaten) override;
    STDMETHODIMP OnTestKeyUp  (ITfContext*, WPARAM, LPARAM, BOOL *pfEaten) override;
    STDMETHODIMP OnKeyDown    (ITfContext*, WPARAM, LPARAM, BOOL *pfEaten) override;
    STDMETHODIMP OnKeyUp      (ITfContext*, WPARAM, LPARAM, BOOL *pfEaten) override;
    STDMETHODIMP OnPreservedKey(ITfContext*, REFGUID, BOOL *pfEaten) override;

private:
    HRESULT _handleKey(ITfContext *pCtx, WPARAM vk, LPARAM lParam, BOOL *pfEaten);

    LONG          _cRef;
    KorJpnIme    *_pIme;    // back-pointer (not AddRef'd to avoid cycle)
    HangulComposer _composer;
};
