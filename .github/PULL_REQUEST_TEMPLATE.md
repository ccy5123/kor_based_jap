<!--
  Thanks for sending a PR.  A few minutes filling this in saves a
  review round-trip later.  Delete sections that don't apply.
-->

## What does this change?

<!-- One-paragraph summary.  Link the issue this fixes / addresses. -->

Fixes #

## Why is this needed?

<!-- The user-visible motivation.  Skip if it's an obvious bug fix. -->

## Approach

<!-- Brief design notes.  How did you implement it; what alternatives
     did you consider; what's the blast radius. -->

## Testing

<!-- How did you verify this works?  -->

- [ ] `tools\build_tests.bat` passes (56/56 unit cases)
- [ ] Manual smoke test: built DLL with `tools\make_release.bat`,
      installed, logged out + back in, typed Japanese
- [ ] Added regression test case for the bug being fixed (if applicable)

## Compatibility

- [ ] Behaviour unchanged when the optional viterbi binaries
      (`kj_dict.bin`, `kj_conn.bin`) are missing -- the IME still
      works on the legacy longest-prefix path
- [ ] No new third-party dependencies (or, if there are, the LICENSE
      is compatible with MIT and they're documented in `LICENSE`)
- [ ] No new ABI surface in the DLL exports

## Docs

- [ ] README.md / README.ko.md / README.ja.md updated together (or
      not touched)
- [ ] CHANGELOG.md updated under `## [Unreleased]`
