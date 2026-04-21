#pragma once
#include <windows.h>
#include <string>

// ----------------------------------------------------------------------------
// Settings — user-editable INI file at %APPDATA%\KorJpnIme\settings.ini.
//
// Sample (default) file written on first run:
//
//     # Korean-Japanese IME settings
//     [Hotkeys]
//     # The key combo that toggles persistent katakana mode.
//     # Format: zero or more modifiers (Ctrl, Shift, Alt, Win) joined with '+',
//     # then the key.  Examples: "Ctrl+;", "F8", "Ctrl+Shift+K".
//     KatakanaToggle = Ctrl+;
//
//     [Behavior]
//     # Convert ASCII digits and punctuation to full-width while IME is on.
//     FullWidthAscii = true
//
//     # Learn user-selected kanji to user_dict.txt so frequent picks bubble up.
//     UserDictLearn = true
//
// Missing sections/keys fall back to the compiled-in defaults.  Malformed
// values are silently ignored; nothing throws or aborts the IME.
// ----------------------------------------------------------------------------
class Settings {
public:
    // Parsed hotkey: modifier mask + VK code.
    // Alt is split into three flags:
    //   - alt   = either Alt key (left or right)
    //   - lalt  = LEFT Alt only  (more specific than `alt`)
    //   - ralt  = RIGHT Alt only (more specific than `alt`)
    // Exactly one of {alt, lalt, ralt} should be true if Alt is required.
    struct Hotkey {
        bool  ctrl   = false;
        bool  shift  = false;
        bool  alt    = false;
        bool  lalt   = false;
        bool  ralt   = false;
        bool  win    = false;
        UINT  vk     = 0;          // 0 = no key configured

        bool IsValid() const { return vk != 0; }

        // Returns true if the keystroke (with the supplied modifier state)
        // matches this hotkey.  The caller passes the LEFT and RIGHT Alt
        // state separately so we can honour RAlt-only / LAlt-only configs.
        bool Matches(UINT vkPressed, bool c, bool s,
                     bool altLeft, bool altRight, bool w) const {
            if (vk != vkPressed) return false;
            if (ctrl != c)       return false;
            if (shift != s)      return false;
            if (win != w)        return false;
            if (ralt) return  altRight && !altLeft;
            if (lalt) return  altLeft  && !altRight;
            if (alt)  return  altLeft  ||  altRight;
            return    !altLeft && !altRight;
        }
    };

    Settings() = default;

    // Load %APPDATA%\KorJpnIme\settings.ini.  If missing, write the default
    // file so the user can find it and edit it.  Always returns true (errors
    // fall back to defaults; never fatal).
    bool Load();

    // Path of the on-disk settings file (after Load() has resolved %APPDATA%).
    const std::wstring& Path() const { return _path; }

    // ---- Accessors --------------------------------------------------------
    const Hotkey& KatakanaToggle()  const { return _katakanaToggle; }
    bool          FullWidthAscii()  const { return _fullWidthAscii; }
    bool          UserDictLearn()   const { return _userDictLearn; }

private:
    void   _ApplyDefaults();
    void   _WriteDefaultFile();
    void   _ParseLine(const std::wstring& section, const std::wstring& key,
                      const std::wstring& value);
    static Hotkey _ParseHotkey(const std::wstring& s);

    std::wstring _path;
    Hotkey       _katakanaToggle;
    bool         _fullWidthAscii = true;
    bool         _userDictLearn  = true;
};
