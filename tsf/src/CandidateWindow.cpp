#include "CandidateWindow.h"
#include "DebugLog.h"
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

void CandidateWindow::Show(const std::vector<std::wstring>& candidates) {
    _candidates  = candidates;
    _selectedIdx = 0;

    if (!EnsureWindow()) return;

    int shownCount = std::min<int>(Count(), PageSize());
    if (shownCount <= 0) shownCount = 1;
    int height = kPaddingY * 2 + kLineHeight * shownCount;

    PositionAtCaret();
    SetWindowPos(_hWnd, HWND_TOPMOST, 0, 0, kMinWidth, height,
                 SWP_NOACTIVATE | SWP_NOMOVE | SWP_SHOWWINDOW);
    Repaint();
    _visible = true;
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
    _selectedIdx = (_selectedIdx + 1) % Count();
    Repaint();
}

void CandidateWindow::SelectPrev() {
    if (Count() == 0) return;
    _selectedIdx = (_selectedIdx - 1 + Count()) % Count();
    Repaint();
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

    int shown = std::min<int>(Count(), PageSize());
    int y = kPaddingY;
    for (int i = 0; i < shown; ++i) {
        const bool selected = (i == _selectedIdx);

        if (selected) {
            RECT row = { rc.left, y, rc.right, y + kLineHeight };
            HBRUSH selBrush = CreateSolidBrush(GetSysColor(COLOR_HIGHLIGHT));
            FillRect(hdc, &row, selBrush);
            DeleteObject(selBrush);
            SetTextColor(hdc, GetSysColor(COLOR_HIGHLIGHTTEXT));
        } else {
            SetTextColor(hdc, GetSysColor(COLOR_WINDOWTEXT));
        }

        wchar_t prefix[8];
        swprintf_s(prefix, L"%d. ", i + 1);
        std::wstring line = prefix;
        line += _candidates[i];

        TextOutW(hdc, kPaddingX, y + 3,
                 line.c_str(), static_cast<int>(line.size()));
        y += kLineHeight;
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
        case WM_MOUSEACTIVATE:
            return MA_NOACTIVATE;
        case WM_DESTROY:
            return 0;
    }
    return DefWindowProcW(hWnd, msg, wParam, lParam);
}
