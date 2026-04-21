#pragma once
#include <windows.h>
#include <string>
#include <vector>

// Forward declaration — owner that handles mouse-triggered commits.
class KorJpnIme;

// ----------------------------------------------------------------------------
// CandidateWindow — small popup that shows kanji conversion candidates.
//
// Owned by KorJpnIme.  KeyHandler asks it to Show(candidates), drives the
// selection via SetSelectedIndex(), and reads GetSelected() before commit.
// The window has WS_EX_NOACTIVATE so focus stays in the host application.
// ----------------------------------------------------------------------------
class CandidateWindow {
public:
    CandidateWindow() = default;
    ~CandidateWindow();

    CandidateWindow(const CandidateWindow&)            = delete;
    CandidateWindow& operator=(const CandidateWindow&) = delete;

    // Show the popup with the given candidate list, selection reset to 0.
    // If `caretRect` is non-null, place the window just below caretRect->bottom
    // (screen coords); otherwise fall back to GetGUIThreadInfo / foreground
    // window heuristics.
    void Show(const std::vector<std::wstring>& candidates,
              const RECT *caretRect = nullptr);
    void Hide();
    bool IsVisible() const { return _visible; }

    // Owner is notified on mouse click (the IME does the commit since the
    // window itself doesn't have access to ITfContext directly).
    void SetOwner(KorJpnIme *p) { _pIme = p; }

    // "Expanded" mode shows many more candidates at once (default 30) so the
    // user can browse a long list without having to PgDn through pages.  Tab
    // toggles into this state from compact mode.
    bool IsExpanded() const { return _expanded; }
    void SetExpanded(bool on);
    int  RowsPerView() const { return _expanded ? kExpandedRows : PageSize(); }

    int  Count() const                          { return static_cast<int>(_candidates.size()); }
    int  GetSelectedIndex() const               { return _selectedIdx; }
    void SetSelectedIndex(int idx);
    void SelectNext();           // wraps; advances to next page when crossing boundary
    void SelectPrev();           // wraps; retreats to previous page

    // Page navigation (PgUp / PgDn): jump to the corresponding slot on the
    // next/previous page (wrapping at the ends).  No-op when there's only one page.
    void NextPage();
    void PrevPage();

    // Direct selection by visible index (1-9 → 0-8 within the current page).
    // Returns true if the index is valid on the current page.
    bool SelectOnPage(int slot);

    std::wstring GetSelected() const {
        return (_selectedIdx >= 0 && _selectedIdx < Count())
                ? _candidates[_selectedIdx]
                : std::wstring{};
    }

    // Pages: how many candidates fit on one page (we display 9 max).
    int PageSize() const { return 9; }
    int PageOf(int idx) const { return PageSize() > 0 ? idx / PageSize() : 0; }
    int CurrentPage() const   { return PageOf(_selectedIdx); }
    int PageCount()  const    {
        if (Count() == 0) return 0;
        return (Count() + PageSize() - 1) / PageSize();
    }

private:
    static LRESULT CALLBACK WndProc(HWND hWnd, UINT msg, WPARAM wParam, LPARAM lParam);
    bool EnsureRegistered();
    bool EnsureWindow();
    void OnPaint(HDC hdc);
    void Repaint();
    void PositionAtCaret();   // place near the system caret if available

    void Reposition(int width, int height);   // applies caret rect + clipping

    HWND  _hWnd        = nullptr;
    HFONT _hFont       = nullptr;
    bool  _visible     = false;
    bool  _expanded    = false;
    int   _selectedIdx = 0;
    KorJpnIme *_pIme   = nullptr;        // for mouse-click commit callback
    std::vector<std::wstring> _candidates;
    RECT  _lastCaretRect = {};
    bool  _haveLastCaretRect = false;

    static constexpr int kExpandedRows = 30;  // rows visible in expanded mode
};
