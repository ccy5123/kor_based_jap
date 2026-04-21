#pragma once
#include <windows.h>
#include <string>
#include <vector>

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
    void Show(const std::vector<std::wstring>& candidates);
    void Hide();
    bool IsVisible() const { return _visible; }

    int  Count() const                          { return static_cast<int>(_candidates.size()); }
    int  GetSelectedIndex() const               { return _selectedIdx; }
    void SetSelectedIndex(int idx);
    void SelectNext();           // wraps
    void SelectPrev();           // wraps

    std::wstring GetSelected() const {
        return (_selectedIdx >= 0 && _selectedIdx < Count())
                ? _candidates[_selectedIdx]
                : std::wstring{};
    }

    // Pages: how many candidates fit on one page (we display 9 max).
    int PageSize() const { return 9; }

private:
    static LRESULT CALLBACK WndProc(HWND hWnd, UINT msg, WPARAM wParam, LPARAM lParam);
    bool EnsureRegistered();
    bool EnsureWindow();
    void OnPaint(HDC hdc);
    void Repaint();
    void PositionAtCaret();   // place near the system caret if available

    HWND  _hWnd        = nullptr;
    HFONT _hFont       = nullptr;
    bool  _visible     = false;
    int   _selectedIdx = 0;
    std::vector<std::wstring> _candidates;
};
