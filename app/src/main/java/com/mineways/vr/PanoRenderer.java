package com.mineways.vr;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 把一张等距柱状（equirectangular，2:1）全景图渲染成一段缓慢运动的 360 视频帧。
 *
 * <p>关键点：输出也是等距柱状。所以每一帧不是"透视相机看球"，而是
 * <b>对每个输出像素求出方向 → 旋转一下 → 再查源图</b>。这样：
 * <ul>
 *   <li>画面本身始终是完整的 360°，播放器转头时四周都有内容；</li>
 *   <li>运动 = 整个全景在缓慢转动（yaw）+ 轻微俯仰摆动，观感就是"镜头在转"；</li>
 *   <li>不需要建球体网格，全屏一个四边形 + 一次纹理采样，4K 也跑得动。</li>
 * </ul>
 */
public final class PanoRenderer implements GlEncoder.FrameDrawer {

    private static final String VS =
            "attribute vec2 aPos;\n" +
            "varying vec2 vUV;\n" +
            "void main() {\n" +
            "  vUV = aPos * 0.5 + 0.5;\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "}\n";

    private static final String FS =
            "precision highp float;\n" +
            "varying vec2 vUV;\n" +
            "uniform sampler2D uTex;\n" +
            "uniform mat3 uRot;\n" +
            "uniform int uMode;\n" +      // 0 = 等距柱状输出(360)，1 = 透视输出(平面)
            "uniform float uFov;\n" +      // 透视模式的垂直视场角（弧度）
            "uniform float uAspect;\n" +
            "const float PI = 3.14159265359;\n" +
            "void main() {\n" +
            "  vec3 d;\n" +
            "  if (uMode == 0) {\n" +
            "    float lon = (vUV.x - 0.5) * 2.0 * PI;\n" +
            "    float lat = (0.5 - vUV.y) * PI;\n" +
            "    d = vec3(cos(lat) * sin(lon), sin(lat), cos(lat) * cos(lon));\n" +
            "  } else {\n" +
            "    vec2 p = vUV * 2.0 - 1.0;\n" +
            "    float t = tan(uFov * 0.5);\n" +
            "    d = normalize(vec3(p.x * t * uAspect, p.y * t, 1.0));\n" +
            "  }\n" +
            "  vec3 s = uRot * d;\n" +
            "  float slon = atan(s.x, s.z);\n" +
            "  float slat = asin(clamp(s.y, -1.0, 1.0));\n" +
            "  vec2 uv = vec2(slon / (2.0 * PI) + 0.5, 0.5 - slat / PI);\n" +
            "  gl_FragColor = texture2D(uTex, uv);\n" +
            "}\n";

    private int program = -1;
    private int texId = -1;
    private int aPos = -1;
    private int uTex = -1;
    private int uRot = -1;
    private int uMode = -1;
    private int uFov = -1;
    private int uAspect = -1;

    /** 0 = 全景（等距柱状输出），1 = 平面（透视输出）。 */
    private int mode = 0;
    private float fovDeg = 90f;
    private FloatBuffer quad;

    /** 每秒转多少度（yaw）。正数向右转。 */
    private float yawPerSec = 6f;
    /** 每秒俯仰摆动幅度（度），做成轻微上下起伏，避免死板。 */
    private float pitchAmp = 1.5f;
    private float pitchPeriod = 12f;

    public PanoRenderer() {
    }

    public PanoRenderer yawSpeed(float degPerSec) {
        this.yawPerSec = degPerSec;
        return this;
    }

    /** 输出模式：0 = 全景（等距柱状），1 = 平面（透视，16:9 那种）。 */
    public PanoRenderer mode(int m) {
        this.mode = m;
        return this;
    }

    /** 平面模式下的垂直视场角（度）。越大越"广角"。 */
    public PanoRenderer fov(float deg) {
        this.fovDeg = deg;
        return this;
    }

    public PanoRenderer pitch(float ampDeg, float periodSec) {
        this.pitchAmp = ampDeg;
        this.pitchPeriod = Math.max(1f, periodSec);
        return this;
    }

    /** 必须在 GL 线程调用。 */
    public void setup(Bitmap pano) {
        // 全屏四边形（两个三角形）
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);

        program = buildProgram();
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        uTex = GLES20.glGetUniformLocation(program, "uTex");
        uRot = GLES20.glGetUniformLocation(program, "uRot");
        uMode = GLES20.glGetUniformLocation(program, "uMode");
        uFov = GLES20.glGetUniformLocation(program, "uFov");
        uAspect = GLES20.glGetUniformLocation(program, "uAspect");

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        texId = tex[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        // 水平方向要能环绕，否则接缝会露一条线
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, pano, 0);
    }

    @Override
    public void drawFrame(int index, float timeSec) {
        GLES20.glViewport(0, 0, viewW, viewH);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(program);

        float yaw = yawPerSec * timeSec;
        float pitch = pitchAmp * (float) Math.sin(2 * Math.PI * timeSec / pitchPeriod);

        float[] m = new float[9];
        buildRotation(yaw, pitch, 0f, m);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glUniformMatrix3fv(uRot, 1, false, m, 0);
        GLES20.glUniform1i(uMode, mode);
        GLES20.glUniform1f(uFov, (float) Math.toRadians(fovDeg));
        GLES20.glUniform1f(uAspect, viewW / (float) viewH);

        quad.position(0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
    }

    private int viewW = 1;
    private int viewH = 1;

    /** 输出尺寸，供 glViewport 用。 */
    public PanoRenderer size(int w, int h) {
        this.viewW = Math.max(1, w);
        this.viewH = Math.max(1, h);
        return this;
    }

    public void release() {
        if (texId > 0) GLES20.glDeleteTextures(1, new int[]{texId}, 0);
        if (program > 0) GLES20.glDeleteProgram(program);
        texId = -1;
        program = -1;
    }

    // ------------------------------------------------------------ 内部

    private static int buildProgram() {
        int vs = compile(GLES20.GL_VERTEX_SHADER, VS);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, FS);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new IllegalStateException("着色器链接失败：" + log);
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new IllegalStateException("着色器编译失败：" + log);
        }
        return s;
    }

    /**
     * 生成 3x3 旋转矩阵，按 GL 的列主序写进 out[9]。
     * 顺序：先滚转（绕视线），再俯仰（绕 X），最后偏航（绕 Y）。
     */
    static void buildRotation(float yawDeg, float pitchDeg, float rollDeg, float[] out) {
        double cy = Math.cos(Math.toRadians(yawDeg)), sy = Math.sin(Math.toRadians(yawDeg));
        double cp = Math.cos(Math.toRadians(pitchDeg)), sp = Math.sin(Math.toRadians(pitchDeg));
        double cr = Math.cos(Math.toRadians(rollDeg)), sr = Math.sin(Math.toRadians(rollDeg));

        // Ry * Rx * Rz（行主序）
        double[][] r = new double[3][3];
        double[][] ry = {{cy, 0, sy}, {0, 1, 0}, {-sy, 0, cy}};
        double[][] rx = {{1, 0, 0}, {0, cp, -sp}, {0, sp, cp}};
        double[][] rz = {{cr, -sr, 0}, {sr, cr, 0}, {0, 0, 1}};
        r = mul(ry, mul(rx, rz));

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                out[col * 3 + row] = (float) r[row][col];
            }
        }
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] o = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double s = 0;
                for (int k = 0; k < 3; k++) s += a[i][k] * b[k][j];
                o[i][j] = s;
            }
        }
        return o;
    }
}
