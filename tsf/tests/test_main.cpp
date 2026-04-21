// Single runner -- each test_*.cpp self-registers its MT_TEST() cases into
// minitest::Registry() via static initialisers, so this file just invokes
// RunAll() and propagates the exit code.
#include "minitest.h"

MT_MAIN()
