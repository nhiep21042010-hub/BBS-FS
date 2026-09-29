# BBS VFX attribution

This build integrates the source code of BBS VFX by Xavin (build-1.2), a VFX toolkit for BBS:
physics destruction (PhysX), smear frames and motion lines, impact frames, blend modes,
label/text overhaul, 3D curves with a follow-curve camera, and After Effects / Blender camera export.

Copyright (c) 2026 Xavin. Licensed under the MIT License; the complete notice is included in every
jar at `META-INF/licenses/bbs-vfx-LICENSE.txt`.

The code keeps its own package (`com.bbsvfx.bbsvfx`), its resource namespace (`bbsvfx`) and its mixins,
so saved films that use `bbsvfx:` forms and clips keep working. It is loaded through the same
entrypoints it had as a separate mod; only the packaging changed. Do not run the standalone
BBS VFX jar together with this build.
