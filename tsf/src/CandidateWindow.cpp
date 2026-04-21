#include "CandidateWindow.h"
#include "KorJpnIme.h"
#include "DebugLog.h"
#include <windowsx.h>     // GET_X_LPARAM / GET_Y_LPARAM
#include <algorithm>
#include <cwchar>

extern HINSTANCE g_hModule;

namespace {

constexpr wchar_t kWndClass[]   = L"KorJpnIme.CandidateWindow.{CADA0001}";
constexpr int    kPaddingX      = 10;
constexpr int    kPaddingY      = 6;
constexpr int    kLineHeight    = 22;
constexpr int    kFontHeight    = 16;
constexpr int    kFallbackX     = 100;
constexpr int    kFallbackY     = 100;
constexpr int    kMinWidth      = 220;

bool g_classRegistered = false;

} // namespace

CandidateWindow::~CandidateWindow() {
    if (_hWnd)  DestroyWindow(_hWnd);
    if (_hFont) DeleteObject(_hFont);
}

bool CandidateWindow::EnsureRegistered() {
    if (g_classRegistered) return true;
    WNDCLASSEXW wc = {};
    wc.cbSize        = sizeof(wc);
    wc.style         = CS_HREDRAW | CS_VREDRAW | CS_SAVEBITS;
    wc.lpfnWndProc   = WndProc;
    wc.hInstance     = g_hModule;
    wc.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_WINDOW + 1);
    wc.hCursor       = LoadCursorW(nullptr, IDC_ARROW);
    wc.lpszClassName = kWndClass;
    if (!RegisterClassExW(&wc) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
        DBGF("CandidateWindow::RegisterClassExW failed err=%lu", (unsigned long)GetLastError());
        return false;
    }
    g_classRegistered = true;
    return true;
}

bool CandidateWindow::EnsureWindow() {
    if (_hWnd) return true;
    if (!EnsureRegistered()) return false;

    _hWnd = CreateWindowExW(
        WS_EX_NOACTIVATE | WS_EX_TOPMOST | WS_EX_TOOLWINDOW,
        kWndClass, L"",
        WS_POPUP | WS_BORDER,
        kFallbackX, kFallbackY, kMinWidth, kLineHeight,
        nullptr, nullptr, g_hModule,
        this);
    if (!_hWnd) {
        DBGF("CandidateWindow::CreateWindowExW failed err=%lu", (unsigned long)GetLastError());
        return false;
    }

    // Japanese-friendly font (falls back through the registry chain if missing)
    _hFont = CreateFontW(
        kFontHeight, 0, 0, 0,
        FW_NORMAL, FALSE, FALSE, FALSE,
        DEFAULT_CHARSET,
        OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
        CLEARTYPE_QUALITY, DEFAULT_PITCH | FF_DONTCARE,
        L"Yu Gothic UI");

    return true;
}

void CandidateWindow::Show(const std::vector<std::wstring>& candidates,
                            const RECT *caretRect) {
    _candidates  = candidates;
    _selectedIdx = 0;
    _expanded    = false;        // every fresh Show() starts in compact mode

    if (!EnsureWindow()) return;

    // Window height = padding + visible rows + (optional) page-footer line + padding
    int rows = std::min<int>(Count(), RowsPerView());
    if (rows <= 0) rows = 1;
    int extraFooter = (!_expanded && PageCount() > 1) ? kLineHeight : 0;
    int height = kPaddingY * 2 + kLineHeight * rows + extraFooter;

    if (caretRect) {
        _lastCaretRect      = *caretRect;
        _haveLastCaretRect  = true;
        Reposition(kMinWidth, height);
        ShowWindow(_hWnd, SW_SHOWNOACTIVATE);
    } else {
        _haveLastCaretRect = false;
        PositionAtCaret();   // fallback (GetGUIThreadInfo / fg-window)
        SetWindowPos(_hWnd, HWND_TOPMOST, 0, 0, kMinWidth, height,
                     SWP_NOACTIVATE | SWP_NOMOVE | SWP_SHOWWINDOW);
    }
    Repaint();
    _visible = true;
}

void CandidateWindow::SetExpanded(bool on) {
    if (_expanded == on || !_hWnd) { _expanded = on; return; }
    _expanded = on;

    int rows = std::min<int>(Count(), RowsPerView());
    if (rows <= 0) rows = 1;
    int extraFooter = (!_expanded && PageCount() > 1) ? kLineHeight : 0;
    int height = kPaddingY * 2 + kLineHeight * rows + extraFooter;

    // If we know the caret rect, recompute position so the (now taller) window
    // stays inside the work area — flips to above-caret when there's no room
    // below.  Without a known caret rect, just resize in place.
    if (_haveLastCaretRect) {
        Reposition(kMinWidth, height);
    } else {
        SetWindowPos(_hWnd, HWND_TOPMOST, 0, 0, kMinWidth, height,
                     SWP_NOACTIVATE | SWP_NOMOVE | SWP_SHOWWINDOW);
    }
    Repaint();
}

void CandidateWindow::Reposition(int width, int height) {
    if (!_hWnd) return;

    HMONITOR hMon = _haveLastCaretRect
        ? MonitorFromRect(&_lastCaretRect, MONITOR_DEFAULTTONEAREST)
        : MonitorFromWindow(_hWnd, MONITOR_DEFAULTTOPRIMARY);
    MONITORINFO mi = { sizeof(mi) };
    RECT work;
    if (hMon && GetMonitorInfo(hMon, &mi)) {
        work = mi.rcWork;
    } else if (!SystemParametersInfo(SPI_GETWORKAREA, 0, &work, 0)) {
        work = { 0, 0, GetSystemMetrics(SM_CXSCREEN),
                        GetSystemMetrics(SM_CYSCREEN) };
    }

    int x, y;
    if (_haveLastCaretRect) {
        x = _lastCaretRect.left;
        y = _lastCaretRect.bottom + 2;          // try below
        if (y + height > work.bottom) {
            int above = _lastCaretRect.top - 2 - height;
            y = (above < work.top) ? work.top : above;
        }
    } else {
        // No caret info — place at top-left of work area
        x = work.left + 100;
        y = work.top + 100;
    }
    if (x + width > work.right)  x = work.right - width;
    if (x < work.left)           x = work.left;

    SetWindowPos(_hWnd, HWND_TOPMOST, x, y, width, height,
                 SWP_NOACTIVATE);
}

void CandidateWindow::Hide() {
    if (_hWnd) ShowWindow(_hWnd, SW_HIDE);
    _visible = false;
}

void CandidateWindow::SetSelectedIndex(int idx) {
    if (idx < 0 || idx >= Count()) return;
    _selectedIdx = idx;
    Repaint();
}

void CandidateWindow::SelectNext() {
    if (Count() == 0) return;
    int prevPage = CurrentPage();
    _selectedIdx = (_selectedIdx + 1) % Count();
    Repaint();
    (void)prevPage;  // page change is implicit via repaint of new range
}

void CandidateWindow::SelectPrev() {
    if (Count() == 0) return;
    _selectedIdx = (_selectedIdx - 1 + Count()) % Count();
    Repaint();
}

void CandidateWindow::NextPage() {
    if (PageCount() <= 1) return;
    int slot = _selectedIdx % PageSize();
    int nextPage = (CurrentPage() + 1) % PageCount();
    int target   = nextPage * PageSize() + slot;
    if (target >= Count()) target = Count() - 1;
    _selectedIdx = target;
    Repaint();
}

void CandidateWindow::PrevPage() {
    if (PageCount() <= 1) return;
    int slot = _selectedIdx % PageSize();
    int prevPage = (CurrentPage() - 1 + PageCount()) % PageCount();
    int target   = prevPage * PageSize() + slot;
    if (target >= Count()) target = Count() - 1;
    _selectedIdx = target;
    Repaint();
}

bool CandidateWindow::SelectOnPage(int slot) {
    if (slot < 0 || slot >= PageSize()) return false;
    int target = CurrentPage() * PageSize() + slot;
    if (target >= Count()) return false;
    _selectedIdx = target;
    Repaint();
    return true;
}

void CandidateWindow::Repaint() {
    if (_hWnd) InvalidateRect(_hWnd, nullptr, TRUE);
}

void CandidateWindow::PositionAtCaret() {
    // Try to put the popup just below the caret in the focused window.
    GUITHREADINFO gti = {};
    gti.cbSize = sizeof(gti);
    if (GetGUIThreadInfo(0, &gti) && gti.hwndCaret) {
        POINT pt = { gti.rcCaret.left, gti.rcCaret.bottom + 2 };
        ClientToScreen(gti.hwndCaret, &pt);
        SetWindowPos(_hWnd, HWND_TOPMOST, pt.x, pt.y, 0, 0,
                     SWP_NOSIZE | SWP_NOACTIVATE);
        return;
    }
    // Fallback: under the foreground window's title bar
    if (HWND fg = GetForegroundWindow()) {
        RECT rc;
        if (GetWindowRect(fg, &rc)) {
            SetWindowPos(_hWnd, HWND_TOPMOST, rc.left + 80, rc.top + 80, 0, 0,
                         SWP_NOSIZE | SWP_NOACTIVATE);
            return;
        }
    }
    SetWindowPos(_hWnd, HWND_TOPMOST, kFallbackX, kFallbackY, 0, 0,
                 SWP_NOSIZE | SWP_NOACTIVATE);
}

void CandidateWindow::OnPaint(HDC hdc) {
    RECT rc;
    GetClientRect(_hWnd, &rc);

    HBRUSH bg = reinterpret_cast<HBRUSH>(COLOR_WINDOW + 1);
    FillRect(hdc, &rc, bg);

    HFONT oldFont = nullptr;
    if (_hFont) oldFont = static_cast<HFONT>(SelectObject(hdc, _hFont));
    SetBkMode(hdc, TRANSPARENT);

    // Determine the visible window into _candidates.
    // - Compact mode: show one PageSize() block; numeric prefix is the page-local slot (1-9).
    // - Expanded mode: scroll so the selected row stays visible; show kExpandedRows;
    //   numeric prefix is omitted (digits 1-9 still pick the first nine of the
    //   visible block, preserving compatibility).
    int viewStart;
    int viewRows = RowsPerView();
    if (_expanded) {
        // Keep selection visible
        if (_selectedIdx < viewRows / 2) {
            viewStart = 0;
        } else {
            viewStart = std::min<int>(_selectedIdx - viewRows / 2,
                                      std::max<int>(0, Count() - viewRows));
        }
    } else {
        viewStart = CurrentPage() * PageSize();
    }
    int viewEnd = std::min<int>(viewStart + viewRows, Count());

    int y = kPaddingY;
    for (int i = viewStart; i < viewEnd; ++i) {
        const bool selected = (i == _selectedIdx);
        const int  slot     = i - viewStart;       // 0-based within visible block

        if (selected) {
            RECT row = { rc.left, y, rc.right, y + kLineHeight };
            HBRUSH selBrush = CreateSolidBrush(GetSysColor(COLOR_HIGHLIGHT));
            FillRect(hdc, &row, selBrush);
            DeleteObject(selBrush);
            SetTextColor(hdc, GetSysColor(COLOR_HIGHLIGHTTEXT));
        } else {
            SetTextColor(hdc, GetSysColor(COLOR_WINDOWTEXT));
        }

        std::wstring line;
        if (slot < 9) {
            wchar_t prefix[8];
            swprintf_s(prefix, L"%d. ", slot + 1);
            line = prefix;
        } else {
            line = L"   ";   // align with prefixed rows
        }
        line += _candidates[i];

        TextOutW(hdc, kPaddingX, y + 3,
                 line.c_str(), static_cast<int>(line.size()));
        y += kLineHeight;
    }

    // Page footer (compact mode only)
    if (!_expanded && PageCount() > 1) {
        SetTextColor(hdc, GetSysColor(COLOR_GRAYTEXT));
        wchar_t foot[16];
        swprintf_s(foot, L"%d/%d", CurrentPage() + 1, PageCount());
        TextOutW(hdc, kPaddingX, y, foot, static_cast<int>(wcslen(foot)));
    }

    if (oldFont) SelectObject(hdc, oldFont);
}

LRESULT CALLBACK CandidateWindow::WndProc(HWND hWnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    if (msg == WM_NCCREATE) {
        auto *cs = reinterpret_cast<CREATESTRUCTW*>(lParam);
        SetWindowLongPtrW(hWnd, GWLP_USERDATA,
                          reinterpret_cast<LONG_PTR>(cs->lpCreateParams));
    }
    auto *self = reinterpret_cast<CandidateWindow*>(
        GetWindowLongPtrW(hWnd, GWLP_USERDATA));

    switch (msg) {
        case WM_PAINT: {
            PAINTSTRUCT ps;
            HDC hdc = BeginPaint(hWnd, &ps);
            if (self) self->OnPaint(hdc);
            EndPaint(hWnd, &ps);
            return 0;
        }
        case WM_MOUSEMOVE:
        case WM_LBUTTONDOWN: {
            if (!self || self->Count() == 0) return 0;
            int yClient = GET_Y_LPARAM(lParam);
            int row = (yClient - kPaddingY) / kLineHeight;
            // Same view-window calculation as OnPaint
            int viewStart;
            int viewRows = self->RowsPerView();
            if (self->_expanded) {
                if (self->_selectedIdx < viewRows / 2) {
                    viewStart = 0;
                } else {
                    viewStart = std::min<int>(self->_selectedIdx - viewRows / 2,
                                std::max<int>(0, self->Count() - viewRows));
                }
            } else {
                viewStart = self->CurrentPage() * self->PageSize();
            }
            int idx = viewStart + row;
            if (idx < 0 || idx >= self->Count()) return 0;

            if (msg == WM_MOUSEMOVE) {
                if (idx != self->_selectedIdx) {
                    self->_selectedIdx = idx;
                    self->Repaint();
                }
            } else /* WM_LBUTTONDOWN */ {
                self->_selectedIdx = idx;
                self->Repaint();
                if (self->_pIme) self->_pIme->OnCandidateClicked(idx);
            }
            return 0;
        }
        case WM_MOUSEACTIVATE:
            return MA_NOACTIVATE;
        case WM_DESTROY:
            return 0;
    }
    return DefWindowProcW(hWnd, msg, wParam, lParam);
}
