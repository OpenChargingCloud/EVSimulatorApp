/* Arch dispatch for libgoldilocks intrinsics. Mirrors private/f_impl.h. */
#ifndef CE_ED448_ARCH_INTRINSICS_DISPATCH_H
#define CE_ED448_ARCH_INTRINSICS_DISPATCH_H 1

#include <stdint.h>

#if defined(__SIZEOF_POINTER__) && __SIZEOF_POINTER__ >= 8
#include "arch_ref64/arch_intrinsics.h"
#else
#include "arch_32/arch_intrinsics.h"
#endif

#endif
