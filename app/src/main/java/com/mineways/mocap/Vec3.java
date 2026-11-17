package com.mineways.mocap;

/** 不可变三维向量（double 精度）。 */
public final class Vec3 {
    public final double x, y, z;

    public Vec3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }

    public static Vec3 add(Vec3 a, Vec3 b) { return new Vec3(a.x + b.x, a.y + b.y, a.z + b.z); }
    public static Vec3 sub(Vec3 a, Vec3 b) { return new Vec3(a.x - b.x, a.y - b.y, a.z - b.z); }
    public static Vec3 scale(Vec3 a, double s) { return new Vec3(a.x * s, a.y * s, a.z * s); }
    public static double dot(Vec3 a, Vec3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
    public static Vec3 cross(Vec3 a, Vec3 b) {
        return new Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x);
    }
    public double len() { return Math.sqrt(x * x + y * y + z * z); }
    public static Vec3 norm(Vec3 a) {
        double l = a.len();
        return l < 1e-12 ? new Vec3(0, 0, 0) : new Vec3(a.x / l, a.y / l, a.z / l);
    }
    public static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return new Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
    }
    public double get(int i) { return i == 0 ? x : (i == 1 ? y : z); }

    @Override public String toString() {
        return String.format(java.util.Locale.US, "(%.4f, %.4f, %.4f)", x, y, z);
    }
}
