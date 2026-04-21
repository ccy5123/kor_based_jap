// pthread_stub.cpp
// Minimal POSIX-thread stubs matching Strawberry MinGW winpthreads ABI exactly.
//
// Critical: winpthreads uses lazy initialisation sentinels for statically
// declared mutexes/cond-vars.  libstdc++ (statically linked) initialises its
// internal synchronisation objects with these sentinel values and expects the
// first lock/wait call to do the real init.  Without handling them we call
// EnterCriticalSection on an invalid pointer and crash immediately on DLL load.
//
// Sentinel values (from winpthreads' pthread.h):
//   GENERIC_INITIALIZER            = -1  (normal mutex / cond)
//   GENERIC_ERRORCHECK_INITIALIZER = -2
//   GENERIC_RECURSIVE_INITIALIZER  = -3
//
// Types (verbatim from pthread.h):
//   pthread_key_t   = unsigned
//   pthread_once_t  = long
//   pthread_mutex_t = intptr_t   (pointer to heap CS, or sentinel)
//   pthread_cond_t  = intptr_t   (pointer to heap CV, or sentinel)

#include <windows.h>
#include <errno.h>
#include <stdint.h>

typedef unsigned      pthread_key_t;
typedef long          pthread_once_t;
typedef intptr_t      pthread_mutex_t;
typedef intptr_t      pthread_cond_t;
typedef void         *pthread_mutexattr_t;
typedef void         *pthread_condattr_t;

static constexpr intptr_t kSentinel0 = 0;
static constexpr intptr_t kSentinel1 = -1;  // GENERIC_INITIALIZER
static constexpr intptr_t kSentinel2 = -2;  // GENERIC_ERRORCHECK_INITIALIZER
static constexpr intptr_t kSentinel3 = -3;  // GENERIC_RECURSIVE_INITIALIZER

static inline bool mutex_is_uninit(intptr_t v) noexcept {
    return v == kSentinel0 || v == kSentinel1 ||
           v == kSentinel2 || v == kSentinel3;
}
static inline bool cond_is_uninit(intptr_t v) noexcept {
    return v == kSentinel0 || v == kSentinel1;
}

extern "C" {

// ---- TLS --------------------------------------------------------------------

int pthread_key_create(pthread_key_t *key, void (*dtor)(void *)) noexcept {
    DWORD idx = FlsAlloc(dtor);
    if (idx == FLS_OUT_OF_INDEXES) return ENOMEM;
    *key = static_cast<unsigned>(idx);
    return 0;
}
int pthread_key_delete(pthread_key_t key) noexcept {
    return FlsFree(static_cast<DWORD>(key)) ? 0 : EINVAL;
}
void *pthread_getspecific(pthread_key_t key) noexcept {
    return FlsGetValue(static_cast<DWORD>(key));
}
int pthread_setspecific(pthread_key_t key, const void *value) noexcept {
    return FlsSetValue(static_cast<DWORD>(key), const_cast<void *>(value)) ? 0 : EINVAL;
}

// ---- Mutex ------------------------------------------------------------------

static CRITICAL_SECTION *cs_alloc() noexcept {
    auto *cs = static_cast<CRITICAL_SECTION *>(
        HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(CRITICAL_SECTION)));
    if (cs) InitializeCriticalSection(cs);
    return cs;
}

int pthread_mutex_init(pthread_mutex_t *m, const pthread_mutexattr_t *) noexcept {
    auto *cs = cs_alloc();
    if (!cs) return ENOMEM;
    *m = reinterpret_cast<intptr_t>(cs);
    return 0;
}

// Lazy-init helper: atomically replace sentinel with a real CRITICAL_SECTION.
static void mutex_ensure_init(pthread_mutex_t *m) noexcept {
    if (!mutex_is_uninit(*m)) return;
    auto *cs = cs_alloc();
    if (!cs) return;
    // CAS: only store if value is still a sentinel
    PVOID old = InterlockedCompareExchangePointer(
        reinterpret_cast<volatile PVOID *>(m),
        cs, reinterpret_cast<PVOID>(*m));
    if (old != static_cast<PVOID>(cs)) {
        // Another thread won (or value changed) — free ours
        DeleteCriticalSection(cs);
        HeapFree(GetProcessHeap(), 0, cs);
    }
}

int pthread_mutex_lock(pthread_mutex_t *m) noexcept {
    mutex_ensure_init(m);
    if (mutex_is_uninit(*m)) return EINVAL;
    EnterCriticalSection(reinterpret_cast<CRITICAL_SECTION *>(*m));
    return 0;
}
int pthread_mutex_unlock(pthread_mutex_t *m) noexcept {
    if (mutex_is_uninit(*m)) return 0;
    LeaveCriticalSection(reinterpret_cast<CRITICAL_SECTION *>(*m));
    return 0;
}
int pthread_mutex_destroy(pthread_mutex_t *m) noexcept {
    if (mutex_is_uninit(*m)) { *m = 0; return 0; }
    auto *cs = reinterpret_cast<CRITICAL_SECTION *>(*m);
    DeleteCriticalSection(cs);
    HeapFree(GetProcessHeap(), 0, cs);
    *m = 0;
    return 0;
}

// ---- Condition variable -----------------------------------------------------

static CONDITION_VARIABLE *cv_alloc() noexcept {
    auto *cv = static_cast<CONDITION_VARIABLE *>(
        HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(CONDITION_VARIABLE)));
    if (cv) InitializeConditionVariable(cv);
    return cv;
}

int pthread_cond_init(pthread_cond_t *c, const pthread_condattr_t *) noexcept {
    auto *cv = cv_alloc();
    if (!cv) return ENOMEM;
    *c = reinterpret_cast<intptr_t>(cv);
    return 0;
}
int pthread_cond_destroy(pthread_cond_t *c) noexcept {
    if (cond_is_uninit(*c)) { *c = 0; return 0; }
    HeapFree(GetProcessHeap(), 0, reinterpret_cast<void *>(*c));
    *c = 0;
    return 0;
}
int pthread_cond_wait(pthread_cond_t *c, pthread_mutex_t *m) noexcept {
    if (cond_is_uninit(*c)) pthread_cond_init(c, nullptr);
    if (mutex_is_uninit(*m)) return EINVAL;
    return SleepConditionVariableCS(
        reinterpret_cast<CONDITION_VARIABLE *>(*c),
        reinterpret_cast<CRITICAL_SECTION *>(*m),
        INFINITE) ? 0 : EINVAL;
}
int pthread_cond_broadcast(pthread_cond_t *c) noexcept {
    if (cond_is_uninit(*c)) return 0;
    WakeAllConditionVariable(reinterpret_cast<CONDITION_VARIABLE *>(*c));
    return 0;
}
int pthread_cond_signal(pthread_cond_t *c) noexcept {
    if (cond_is_uninit(*c)) return 0;
    WakeConditionVariable(reinterpret_cast<CONDITION_VARIABLE *>(*c));
    return 0;
}

// ---- pthread_once -----------------------------------------------------------

int pthread_once(pthread_once_t *once, void (*fn)(void)) noexcept {
    if (InterlockedCompareExchange(once, 1L, 0L) == 0L) {
        fn();
        InterlockedExchange(once, 2L);
    } else {
        while (InterlockedCompareExchange(once, 2L, 2L) != 2L)
            Sleep(0);
    }
    return 0;
}

} // extern "C"
