package com.koper.koper_lib.kodel;

import java.util.ArrayList;
import java.util.List;

// bedrock authoring space -> minecraft render space.
//
// a .kodel holds whatever the source format held, verbatim, because the container
// is supposed to be engine agnostic. bedrock geometry is authored in a space
// mirrored across x relative to the one mc renders entities in, which is why
// geckolib negates x on every pivot and origin and negates the x and y euler
// components of every rotation. kodel skipped that entirely, so bones with a
// rotation on x or y turned the wrong way and the whole model came out mirrored.
//
// this is the one place that flip lives. KodelBook applies it on load, so nothing
// downstream has to remember. it does NOT touch the stored bytes.
public final class KodelBedrock {
    private KodelBedrock() {}

    /// mirrored copy, safe to cache. the input is left alone
    public static KodelModel toRenderSpace(KodelModel src) {
        if (src == null) return null;
        KodelModel out = new KodelModel();
        out.texWidth = src.texWidth;
        out.texHeight = src.texHeight;

        for (KodelModel.KodelBone b : src.bones) {
            KodelModel.KodelBone bone = new KodelModel.KodelBone();
            bone.name = b.name;
            bone.parent = b.parent;
            bone.visible = b.visible;
            bone.pivot[0] = -b.pivot[0];
            bone.pivot[1] = b.pivot[1];
            bone.pivot[2] = b.pivot[2];
            bone.position[0] = -b.position[0];
            bone.position[1] = b.position[1];
            bone.position[2] = b.position[2];
            mirrorQuat(b.rotation, bone.rotation);
            System.arraycopy(b.scale, 0, bone.scale, 0, 3);

            for (KodelModel.KodelCube c : b.cubes) {
                KodelModel.KodelCube cube = new KodelModel.KodelCube();
                // the box keeps its width, it just sits on the other side of x=0,
                // so the min corner becomes the mirror of the old max corner
                cube.origin[0] = -(c.origin[0] + c.size[0]);
                cube.origin[1] = c.origin[1];
                cube.origin[2] = c.origin[2];
                // size stays exactly as authored. a zero here is the only marker that
                // a cube is flat, and KodelModelRender needs to know: it gives those
                // a sliver of thickness so they stop z-fighting, and turns the back
                // face's normal around so it is not lit from inside
                System.arraycopy(c.size, 0, cube.size, 0, 3);
                cube.inflate = c.inflate;
                cube.pivot[0] = -c.pivot[0];
                cube.pivot[1] = c.pivot[1];
                cube.pivot[2] = c.pivot[2];
                mirrorQuat(c.rotation, cube.rotation);
                cube.mirror = c.mirror;
                // faces keep their atlas rects. the east slot now draws what used to
                // be the west side of the model, which is exactly what mc expects
                // and what geckolib ends up doing
                for (int f = 0; f < 6; f++) {
                    KodelModel.KodelFace face = new KodelModel.KodelFace();
                    KodelModel.KodelFace from = c.faces[f] != null ? c.faces[f] : new KodelModel.KodelFace();
                    face.u = from.u;
                    face.v = from.v;
                    face.uw = from.uw;
                    face.vh = from.vh;
                    face.rot = from.rot;
                    face.mirror = from.mirror;
                    cube.faces[f] = face;
                }
                bone.cubes.add(cube);
            }
            out.bones.add(bone);
        }

        for (KodelModel.KodelMesh m : src.meshes) {
            KodelModel.KodelMesh mesh = new KodelModel.KodelMesh();
            mesh.bone = m.bone;
            mesh.positions = m.positions.clone();
            mesh.normals = m.normals.clone();
            mesh.uvs = m.uvs.clone();
            mesh.indices = m.indices.clone();
            for (int i = 0; i < mesh.positions.length; i += 3) mesh.positions[i] = -mesh.positions[i];
            for (int i = 0; i < mesh.normals.length; i += 3) mesh.normals[i] = -mesh.normals[i];
            out.meshes.add(mesh);
        }
        return out;
    }

    /// same flip for the clips, or the rest pose would be mirrored and the animation
    /// would not
    public static List<KodelAnimation> toRenderSpace(List<KodelAnimation> src) {
        if (src == null) return null;
        List<KodelAnimation> out = new ArrayList<>(src.size());
        for (KodelAnimation a : src) out.add(toRenderSpace(a));
        return out;
    }

    public static KodelAnimation toRenderSpace(KodelAnimation src) {
        if (src == null) return null;
        KodelAnimation out = new KodelAnimation();
        out.name = src.name;
        out.length = src.length;
        out.loop = src.loop;
        for (KodelAnimation.KodelTrack t : src.tracks) {
            KodelAnimation.KodelTrack track = new KodelAnimation.KodelTrack();
            track.bone = t.bone;
            // rotations are euler radians here, so the flip is the same negate x,y
            track.rotation = channel(t.rotation, true, true, false);
            // a position channel moves the bone along mirrored axes
            track.position = channel(t.position, true, false, false);
            track.scale = channel(t.scale, false, false, false);
            out.tracks.add(track);
        }
        return out;
    }

    private static KodelAnimation.KodelChannel channel(KodelAnimation.KodelChannel src,
                                                       boolean negX, boolean negY, boolean negZ) {
        if (src == null) return null;
        float[] values = src.values.clone();
        float[] ctrlA = src.ctrlA.clone();
        float[] ctrlB = src.ctrlB.clone();
        for (int i = 0; i + 2 < values.length; i += 3) {
            if (negX) {
                values[i] = -values[i];
                ctrlA[i] = -ctrlA[i];
                ctrlB[i] = -ctrlB[i];
            }
            if (negY) {
                values[i + 1] = -values[i + 1];
                ctrlA[i + 1] = -ctrlA[i + 1];
                ctrlB[i + 1] = -ctrlB[i + 1];
            }
            if (negZ) {
                values[i + 2] = -values[i + 2];
                ctrlA[i + 2] = -ctrlA[i + 2];
                ctrlB[i + 2] = -ctrlB[i + 2];
            }
        }
        return new KodelAnimation.KodelChannel()
            .set(src.times.clone(), values, src.easing.clone(), ctrlA, ctrlB);
    }

    // negating the x and y euler components in ZYX order is the same as conjugating
    // by a 180 degree turn about z, and on a quaternion that is just two sign flips.
    // no euler round trip, no gimbal surprises
    static void mirrorQuat(float[] src, float[] out) {
        out[0] = -src[0];
        out[1] = -src[1];
        out[2] = src[2];
        out[3] = src[3];
    }
}
