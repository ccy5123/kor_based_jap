#include "Settings.h"
#include "DebugLog.h"
#include <shlobj.h>
#include <strsafe.h>
#include <fstream>
#include <sstream>
#include <vector>
#include <algorithm>

namespace {

// Resolve %APPDATA%\KorJpnIme\ — create the directory if missing.
std::wstring AppDataDir() {
    wchar_t buf[MAX_PATH] = {};
    if (FAILED(SHGetFolderPathW(nullptr, CSIDL_APPDATA, nullptr, 0, buf))) {
        return L"";
    }
    std::wstring p = buf;
    p += L"\\KorJpnIme";
    CreateDirectoryW(p.c_str(), nullptr);  // ok if it already exists
    return p;
}

// Trim ASCII whitespace from both ends.
std::wstring Trim(std::wstring s) {
    auto isSpace = [](wchar_t c) { return c == L' ' || c == L'\t' || c == L'\r' || c == L'\n'; };
    while (!s.empty() && isSpace(s.front())) s.erase(0, 1);
    while (!s.empty() && isSpace(s.back()))  s.pop_back();
    return s;
}

bool ParseBool(const std::wstring& v, bool fallback) {
    std::wstring s = v;
    std::transform(s.begin(), s.end(), s.begin(), ::towlower);
    if (s == L"true" || s == L"1" || s == L"yes" || s == L"on")  return true;
    if (s == L"false" || s == L"0" || s == L"no"  || s == L"off") return false;
    return fallback;
}

// Convert a UTF-8 byte buffer to UTF-16.
std::wstring Utf8ToUtf16(const std::string& s) {
    if (s.empty()) return {};
    int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), nullptr, 0);
    if (n <= 0) return {};
    std::wstring out((size_t)n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), out.data(), n);
    return out;
}

} // namespace

Settings::~Settings() {
    if (_changeHandle != INVALID_HANDLE_VALUE) {
        FindCloseChangeNotification(_changeHandle);
        _changeHandle = INVALID_HANDLE_VALUE;
    }
}

bool Settings::MaybeReload() {
    // Lazy open of the directory watch -- deferred until first MaybeReload()
    // so that Load() itself doesn't pay for it (Load() may be called on a
    // path we never end up watching, e.g. when AppData resolution failed).
    if (_changeHandle == INVALID_HANDLE_VALUE) {
        std::wstring dir = AppDataDir();
        if (dir.empty()) return false;
        // FILE_NOTIFY_CHANGE_LAST_WRITE only fires on actual writes -- reads
        // and metadata-only updates (timestamps from antivirus etc.) are
        // ignored.  We watch the parent dir because notifications on a single
        // file are only available via the heavier ReadDirectoryChangesW path.
        _changeHandle = FindFirstChangeNotificationW(
            dir.c_str(), FALSE, FILE_NOTIFY_CHANGE_LAST_WRITE);
        if (_changeHandle == INVALID_HANDLE_VALUE) {
            DBGF("Settings::MaybeReload FindFirstChangeNotificationW failed err=%lu",
                 (unsigned long)GetLastError());
            return false;
        }
        return false;   // first call just installs the watch
    }

    // Non-blocking poll: returns immediately whether or not the dir changed.
    DWORD wait = WaitForSingleObject(_changeHandle, 0);
    if (wait != WAIT_OBJECT_0) return false;
    // Re-arm the notification before reloading so we don't miss a write that
    // arrives during Load().
    FindNextChangeNotification(_changeHandle);
    DBG("Settings::MaybeReload INI changed -- reloading");
    Load();
    return true;
}

void Settings::_ApplyDefaults() {
    // Default katakana toggle: Right Alt + K.
    // RAlt was chosen because it doesn't conflict with common app shortcuts
    // (Notion's Ctrl+Shift+K, Claude's Ctrl+;, MS-IME's F6-F10 conventions).
    // On a Korean 2-beolsik keyboard, RAlt is the 한/영 key — repurposing it
    // while the IME is active is natural for Korean users.
    _katakanaToggle = Hotkey{};
    _katakanaToggle.ralt = true;
    _katakanaToggle.vk   = L'K';
    _fullWidthAscii = true;
    _userDictLearn  = true;
}

void Settings::_WriteDefaultFile() {
    std::ofstream f(_path, std::ios::binary);
    if (!f) return;
    static const char kDefault[] =
        "# Korean-Japanese IME settings\n"
        "# Edit and save; changes take effect on next IME activation\n"
        "# (typically next login).\n"
        "\n"
        "[Hotkeys]\n"
        "# The key combo that toggles persistent katakana mode.\n"
        "# Format: zero or more modifiers joined with '+', then a key.\n"
        "# Modifiers:\n"
        "#   Ctrl, Shift, Win        — standard\n"
        "#   Alt                     — either Alt key\n"
        "#   LAlt / LeftAlt          — LEFT Alt only\n"
        "#   RAlt / RightAlt         — RIGHT Alt only (Korean 2-beolsik han/yeong key)\n"
        "# Key can be a letter (A-Z), digit (0-9), function key (F1-F12),\n"
        "# or punctuation (; / [ ] etc.).  Examples:\n"
        "#   RAlt+K     (default — Korean han/yeong key + K, no app conflicts)\n"
        "#   F8         (free F-key on most keyboards)\n"
        "#   Ctrl+Shift+K\n"
        "KatakanaToggle = RAlt+K\n"
        "\n"
        "[Behavior]\n"
        "# Convert ASCII digits and punctuation to full-width forms while the IME\n"
        "# is on (1 -> '\xef\xbc\x91', ! -> '\xef\xbc\x81', etc.).\n"
        "FullWidthAscii = true\n"
        "\n"
        "# Learn user-selected kanji conversions into user_dict.txt so frequent\n"
        "# picks bubble to the top of the candidate window.\n"
        "UserDictLearn = true\n"
        "\n"
        "# Maximum (kana, kanji) pairs to keep in the user dictionary.  When\n"
        "# the count exceeds this number after a learning session, entries\n"
        "# with the lowest pick-count are dropped (LFU eviction) before the\n"
        "# next save.  0 = unlimited.  Default 5000 keeps the file under\n"
        "# ~200 KB.\n"
        "UserDictMaxEntries = 5000\n";
    f.write(kDefault, sizeof(kDefault) - 1);
}

bool Settings::Load() {
    _ApplyDefaults();

    std::wstring dir = AppDataDir();
    if (dir.empty()) {
        DBG("Settings::Load AppData dir resolution failed — using built-in defaults");
        return true;   // never fatal
    }
    _path = dir + L"\\settings.ini";

    if (GetFileAttributesW(_path.c_str()) == INVALID_FILE_ATTRIBUTES) {
        DBGF("Settings::Load creating default %ls", _path.c_str());
        _WriteDefaultFile();
        return true;   // defaults already applied
    }

    std::ifstream f(_path, std::ios::binary);
    if (!f) {
        DBGF("Settings::Load open failed for %ls — defaults stand", _path.c_str());
        return true;
    }
    std::stringstream ss;
    ss << f.rdbuf();
    std::wstring text = Utf8ToUtf16(ss.str());

    std::wstring section, line;
    size_t pos = 0;
    while (pos <= text.size()) {
        size_t nl = text.find(L'\n', pos);
        if (nl == std::wstring::npos) nl = text.size();
        line = text.substr(pos, nl - pos);
        pos  = nl + 1;
        line = Trim(line);
        if (line.empty()) continue;
        if (line[0] == L'#' || line[0] == L';') continue;
        if (line.front() == L'[' && line.back() == L']') {
            section = Trim(line.substr(1, line.size() - 2));
            continue;
        }
        size_t eq = line.find(L'=');
        if (eq == std::wstring::npos) continue;
        std::wstring key = Trim(line.substr(0, eq));
        std::wstring val = Trim(line.substr(eq + 1));
        _ParseLine(section, key, val);
    }

    DBGF("Settings::Load done katakana={ctrl=%d shift=%d alt=%d lalt=%d ralt=%d win=%d vk=0x%X} fullw=%d learn=%d",
         (int)_katakanaToggle.ctrl, (int)_katakanaToggle.shift,
         (int)_katakanaToggle.alt,  (int)_katakanaToggle.lalt,
         (int)_katakanaToggle.ralt, (int)_katakanaToggle.win,
         (unsigned)_katakanaToggle.vk,
         (int)_fullWidthAscii, (int)_userDictLearn);
    return true;
}

void Settings::_ParseLine(const std::wstring& section, const std::wstring& key,
                           const std::wstring& value) {
    if (section == L"Hotkeys") {
        if (key == L"KatakanaToggle") {
            Hotkey hk = _ParseHotkey(value);
            if (hk.IsValid()) _katakanaToggle = hk;
        }
    } else if (section == L"Behavior") {
        if      (key == L"FullWidthAscii") _fullWidthAscii = ParseBool(value, _fullWidthAscii);
        else if (key == L"UserDictLearn")  _userDictLearn  = ParseBool(value, _userDictLearn);
        else if (key == L"UserDictMaxEntries") {
            try {
                int n = std::stoi(value);
                if (n >= 0) _userDictMaxEntries = n;   // 0 = unlimited
            } catch (...) {
                // malformed -> keep default
            }
        }
    }
}

Settings::Hotkey Settings::_ParseHotkey(const std::wstring& s) {
    Hotkey hk;
    if (s.empty()) return hk;

    // Split on '+'
    std::vector<std::wstring> parts;
    size_t start = 0, plus;
    while ((plus = s.find(L'+', start)) != std::wstring::npos) {
        parts.push_back(Trim(s.substr(start, plus - start)));
        start = plus + 1;
    }
    parts.push_back(Trim(s.substr(start)));

    auto eqIgnoreCase = [](const std::wstring& a, const wchar_t* b) {
        std::wstring la = a;
        std::transform(la.begin(), la.end(), la.begin(), ::towlower);
        std::wstring lb = b;
        std::transform(lb.begin(), lb.end(), lb.begin(), ::towlower);
        return la == lb;
    };

    for (size_t i = 0; i + 1 < parts.size(); ++i) {
        const auto& m = parts[i];
        if      (eqIgnoreCase(m, L"Ctrl"))    hk.ctrl  = true;
        else if (eqIgnoreCase(m, L"Shift"))   hk.shift = true;
        // Side-specific Alt tokens take precedence over generic "Alt".
        // This lets Korean 2-beolsik users bind RAlt (한/영 key) without
        // catching keystrokes that happen to use the LEFT Alt key.
        else if (eqIgnoreCase(m, L"RAlt") ||
                 eqIgnoreCase(m, L"RightAlt"))  hk.ralt = true;
        else if (eqIgnoreCase(m, L"LAlt") ||
                 eqIgnoreCase(m, L"LeftAlt"))   hk.lalt = true;
        else if (eqIgnoreCase(m, L"Alt"))     hk.alt   = true;
        else if (eqIgnoreCase(m, L"Win"))     hk.win   = true;
    }

    const std::wstring& key = parts.back();
    if (key.empty()) return hk;

    // Single character: A-Z, 0-9, or one of the OEM punctuation symbols.
    if (key.size() == 1) {
        wchar_t c = key[0];
        if (c >= L'a' && c <= L'z') c = static_cast<wchar_t>(c - L'a' + L'A');
        if ((c >= L'A' && c <= L'Z') || (c >= L'0' && c <= L'9')) {
            hk.vk = static_cast<UINT>(c);
        } else {
            switch (c) {
                case L';': case L':':  hk.vk = VK_OEM_1;     break;
                case L'/': case L'?':  hk.vk = VK_OEM_2;     break;
                case L'`': case L'~':  hk.vk = VK_OEM_3;     break;
                case L'[': case L'{':  hk.vk = VK_OEM_4;     break;
                case L'\\':case L'|':  hk.vk = VK_OEM_5;     break;
                case L']': case L'}':  hk.vk = VK_OEM_6;     break;
                case L'\'':case L'"':  hk.vk = VK_OEM_7;     break;
                case L'-': case L'_':  hk.vk = VK_OEM_MINUS; break;
                case L'=': case L'+':  hk.vk = VK_OEM_PLUS;  break;
                case L',': case L'<':  hk.vk = VK_OEM_COMMA; break;
                case L'.': case L'>':  hk.vk = VK_OEM_PERIOD;break;
                default: break;
            }
        }
        return hk;
    }

    // F1..F24
    if (key.size() <= 3 && (key[0] == L'F' || key[0] == L'f')) {
        try {
            int n = std::stoi(key.substr(1));
            if (n >= 1 && n <= 24) hk.vk = VK_F1 + (n - 1);
        } catch (...) {}
        return hk;
    }

    // Named keys
    if      (eqIgnoreCase(key, L"Space"))     hk.vk = VK_SPACE;
    else if (eqIgnoreCase(key, L"Tab"))       hk.vk = VK_TAB;
    else if (eqIgnoreCase(key, L"Enter"))     hk.vk = VK_RETURN;
    else if (eqIgnoreCase(key, L"Escape"))    hk.vk = VK_ESCAPE;
    else if (eqIgnoreCase(key, L"CapsLock"))  hk.vk = VK_CAPITAL;
    return hk;
}
