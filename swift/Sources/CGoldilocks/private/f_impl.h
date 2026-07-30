/* Arch dispatch for libgoldilocks field implementation.
 *
 * Picks the 32-bit or 64-bit f_impl.h based on pointer width. This file
 * replaces the per-arch include the upstream Makefile selects via -I flags;
 * SPM has no equivalent arch-conditional include dirs.
 */
#ifndef CE_ED448_F_IMPL_DISPATCH_H
#define CE_ED448_F_IMPL_DISPATCH_H 1

#if defined(__SIZEOF_POINTER__) && __SIZEOF_POINTER__ >= 8
#include "arch_ref64/f_impl.h"
#else
#include "arch_32/f_impl.h"
#endif

#endif
