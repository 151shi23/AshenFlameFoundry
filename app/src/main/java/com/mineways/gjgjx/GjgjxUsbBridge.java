package com.mineways.gjgjx;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把 WebUSB 的最小实现转接到 Android 的 UsbManager。
 *
 * <p>页面的 {@code js/android-usb.js} 把 {@code navigator.usb} 的每个方法打包成
 * {@code call(id, method, argsJson)} 交给这里；本类在后台线程执行真正的 USB 读写，
 * 完成后回调页面里的 {@code window.__gjUsbResult(id, ok, payloadJson)}。
 *
 * <p>只覆盖 adb-daemon-webusb 用到的接口：枚举、申请权限、打开、选配置、占用接口、
 * 选择备用设置、批量收发、关闭，以及设备拔出事件。二进制数据走 Base64，
 * 这样不依赖 Android WebView 对 Java 数组的桥接细节。
 */
public final class GjgjxUsbBridge {

    private static final String TAG = "GjgjxUsb";
    private static final String ACTION_PERMISSION = "com.mineways.gjgjx.USB_PERMISSION";
    private static final int ADB_CLASS = UsbConstants.USB_CLASS_VENDOR_SPEC;
    private static final int ADB_SUBCLASS = 66;
    private static final int ADB_PROTOCOL = 1;

    /** 页面脚本的出口：把一段 JS 交给 WebView 执行。 */
    public interface Sink {
        void runJs(String js);
    }

    private static final class Session {
        final UsbDevice device;
        final UsbDeviceConnection connection;
        final Map<Integer, UsbInterface> claimed = new ConcurrentHashMap<>();
        volatile boolean closed;

        Session(UsbDevice device, UsbDeviceConnection connection) {
            this.device = device;
            this.connection = connection;
        }
    }

    private final Activity activity;
    private final UsbManager usb;
    private final Sink sink;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "gjgjx-usb");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, UsbDevice> known = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    /** 等待系统权限弹窗结果的设备名 → 页面回调 id。 */
    private final Map<String, String> awaiting = new ConcurrentHashMap<>();
    private final AtomicInteger permissionSeq = new AtomicInteger(1);
    private volatile boolean disposed;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    onDetached(device);
                }
                return;
            }
            if (!ACTION_PERMISSION.equals(action)) {
                return;
            }
            UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (device == null) {
                return;
            }
            String id = awaiting.remove(device.getDeviceName());
            if (id == null) {
                return;
            }
            if (granted) {
                finishDevice(id, device);
            } else {
                error(id, "SecurityError", "USB 权限被拒绝，请在系统弹窗里允许");
            }
        }
    };

    public GjgjxUsbBridge(Activity activity, Sink sink) {
        this.activity = activity;
        this.sink = sink;
        this.usb = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
    }

    public void dispose() {
        disposed = true;
        try {
            activity.unregisterReceiver(receiver);
        } catch (Throwable ignored) {
            // 未注册或已注销
        }
        for (Session s : sessions.values()) {
            closeQuietly(s);
        }
        sessions.clear();
        awaiting.clear();
        io.shutdownNow();
    }

    // ---------------------------------------------------------------- 页面入口

    /** 页面在加载最早阶段同步读取的云端配置。 */
    @JavascriptInterface
    public String config() {
        JSONObject out = new JSONObject();
        try {
            out.put("usb", GjgjxFlags.usb(activity));
            out.put("notice", GjgjxFlags.notice(activity));
        } catch (JSONException ignored) {
            // 不会发生
        }
        return out.toString();
    }

    @JavascriptInterface
    public void call(String id, String method, String argsJson) {
        if (disposed) {
            return;
        }
        if (!usbAllowed()) {
            String notice = GjgjxFlags.notice(activity);
            error(id, "SecurityError", notice.length() == 0 ? "云端已关闭 USB 连机功能" : notice);
            return;
        }
        JSONObject args;
        try {
            args = argsJson == null || argsJson.length() == 0 ? new JSONObject() : new JSONObject(argsJson);
        } catch (JSONException e) {
            error(id, "TypeError", "参数不是合法 JSON");
            return;
        }
        switch (method == null ? "" : method) {
            case "getDevices":
                getDevices(id);
                break;
            case "requestDevice":
                requestDevice(id, args);
                break;
            case "open":
                open(id, args.optString("id"));
                break;
            case "close":
                close(id, args.optString("id"));
                break;
            case "claimInterface":
                claim(id, args.optString("id"), args.optInt("number", -1));
                break;
            case "releaseInterface":
                release(id, args.optString("id"), args.optInt("number", -1));
                break;
            case "selectConfiguration":
                selectConfiguration(id, args.optString("id"), args.optInt("value", 1));
                break;
            case "selectAlternate":
                selectAlternate(id, args.optString("id"), args.optInt("number", -1),
                        args.optInt("alternate", 0));
                break;
            case "transferIn":
                transferIn(id, args.optString("id"), args.optInt("endpoint", -1),
                        args.optInt("length", 0));
                break;
            case "transferOut":
                transferOut(id, args.optString("id"), args.optInt("endpoint", -1),
                        args.optString("data"));
                break;
            default:
                error(id, "NotSupportedError", "不支持的 USB 操作：" + method);
        }
    }

    // ---------------------------------------------------------------- 枚举与授权

    private void getDevices(String id) {
        io.execute(() -> {
            if (!usbAllowed()) {
                ok(id, "{\"devices\":[]}");
                return;
            }
            JSONArray list = new JSONArray();
            for (UsbDevice device : usb.getDeviceList().values()) {
                if (!hasAdbInterface(device)) {
                    continue;
                }
                if (!usb.hasPermission(device)) {
                    continue;
                }
                known.put(device.getDeviceName(), device);
                list.put(describe(device));
            }
            JSONObject out = new JSONObject();
            try {
                out.put("devices", list);
            } catch (JSONException ignored) {
                // 不会发生
            }
            ok(id, out.toString());
        });
    }

    private void requestDevice(String id, JSONObject args) {
        JSONArray filters = args.optJSONArray("filters");
        JSONArray excludes = args.optJSONArray("exclusionFilters");
        main.post(() -> {
            if (disposed) {
                return;
            }
            List<UsbDevice> candidates = new ArrayList<>();
            for (UsbDevice device : usb.getDeviceList().values()) {
                if (!hasAdbInterface(device)) {
                    continue;
                }
                if (!matches(device, filters)) {
                    continue;
                }
                if (matches(device, excludes)) {
                    continue;
                }
                candidates.add(device);
            }
            if (candidates.isEmpty()) {
                // 没插设备时也给一个能操作的弹窗，而不是只丢一条 Toast 就结束
                try {
                    AlertDialog empty = new AlertDialog.Builder(activity)
                            .setTitle("未识别到 USB 设备")
                            .setMessage("请用 OTG 线连接目标手机，并在目标手机上打开"
                                    + "「开发者选项 → USB 调试」。插好后点「重新扫描」。")
                            .setPositiveButton("重新扫描", (d, w) -> requestDevice(id, args))
                            .setNegativeButton("取消", (d, w) -> error(id, "NotFoundError", "未选择设备"))
                            .setCancelable(false)
                            .create();
                    empty.show();
                } catch (Throwable t) {
                    Log.e(TAG, "空设备提示弹窗失败", t);
                    error(id, "NotFoundError", "没有发现可用的 ADB USB 设备（请用 OTG 线连接目标手机并打开 USB 调试）");
                }
                return;
            }
            for (UsbDevice device : candidates) {
                known.put(device.getDeviceName(), device);
            }
            // 不论识别到几台都弹窗让用户挑：只有一台时也保留这一步，
            // 避免「点连接就直接连到某台设备」这种看不出连接目标的行为。
            String[] labels = new String[candidates.size()];
            for (int i = 0; i < candidates.size(); i++) {
                UsbDevice d = candidates.get(i);
                String name = safeProduct(d);
                String state = usb.hasPermission(d) ? "已授权" : "需在手机上确认授权";
                labels[i] = name + "  (" + hex(d.getVendorId()) + ":" + hex(d.getProductId()) + ")"
                        + "  ·  " + state;
            }
            try {
                AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setTitle("选择要连接的 USB 设备（已识别 " + candidates.size() + " 台）")
                        .setItems(labels, (d, which) -> askPermission(id, candidates.get(which)))
                        .setNegativeButton("取消", (d, which) -> error(id, "NotFoundError", "已取消选择设备"))
                        // 不允许点空白/返回键静默关掉：那会让页面收到一个说不清原因的 AbortError
                        .setCancelable(false)
                        .create();
                dialog.show();
            } catch (Throwable t) {
                Log.e(TAG, "设备选择弹窗失败", t);
                error(id, "NetworkError", "无法弹出设备选择框：" + message(t));
            }
        });
    }

    private void askPermission(String id, UsbDevice device) {
        if (usb.hasPermission(device)) {
            finishDevice(id, device);
            return;
        }
        awaiting.put(device.getDeviceName(), id);
        main.postDelayed(() -> {
            String waiting = awaiting.remove(device.getDeviceName());
            if (waiting != null) {
                error(waiting, "AbortError", "等待 USB 授权超时，请重新连接");
            }
        }, 60_000);
        Intent intent = new Intent(ACTION_PERMISSION).setPackage(activity.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(activity, permissionSeq.getAndIncrement(),
                intent, flags);
        usb.requestPermission(device, pi);
    }

    private void finishDevice(String id, UsbDevice device) {
        known.put(device.getDeviceName(), device);
        JSONObject out = new JSONObject();
        try {
            out.put("device", describe(device));
        } catch (JSONException ignored) {
            // 不会发生
        }
        ok(id, out.toString());
    }

    // ---------------------------------------------------------------- 连接与接口

    private void open(String id, String name) {
        io.execute(() -> {
            UsbDevice device = known.get(name);
            if (device == null) {
                error(id, "NotFoundError", "设备已移除，请重新选择");
                return;
            }
            if (!usb.hasPermission(device)) {
                error(id, "SecurityError", "没有该设备的 USB 权限");
                return;
            }
            if (sessions.containsKey(name)) {
                ok(id, "{}");
                return;
            }
            UsbDeviceConnection connection = usb.openDevice(device);
            if (connection == null) {
                error(id, "NetworkError", "无法打开 USB 设备，可能被其他程序占用");
                return;
            }
            sessions.put(name, new Session(device, connection));
            ok(id, "{}");
        });
    }

    private void close(String id, String name) {
        io.execute(() -> {
            Session session = sessions.remove(name);
            if (session != null) {
                closeQuietly(session);
            }
            ok(id, "{}");
        });
    }

    private void claim(String id, String name, int number) {
        io.execute(() -> {
            Session session = sessions.get(name);
            if (session == null) {
                error(id, "InvalidStateError", "设备未打开");
                return;
            }
            UsbInterface target = findInterface(session.device, number, 0);
            if (target == null) {
                error(id, "NotFoundError", "找不到接口 " + number);
                return;
            }
            try {
                if (!session.connection.claimInterface(target, true)) {
                    error(id, "NetworkError", "接口 " + number + " 被占用（请先断开其他 ADB 程序）");
                    return;
                }
            } catch (Throwable t) {
                error(id, "NetworkError", "占用接口失败：" + message(t));
                return;
            }
            session.claimed.put(number, target);
            ok(id, "{}");
        });
    }

    private void release(String id, String name, int number) {
        io.execute(() -> {
            Session session = sessions.get(name);
            if (session == null) {
                error(id, "InvalidStateError", "设备未打开");
                return;
            }
            UsbInterface target = session.claimed.remove(number);
            if (target != null) {
                session.connection.releaseInterface(target);
            }
            ok(id, "{}");
        });
    }

    private void selectConfiguration(String id, String name, int value) {
        io.execute(() -> {
            Session session = sessions.get(name);
            if (session == null) {
                error(id, "InvalidStateError", "设备未打开");
                return;
            }
            // Android 只暴露第一套配置；conflicting 值不等于它时按设备实际能力处理
            if (session.device.getConfigurationCount() > 0) {
                android.hardware.usb.UsbConfiguration configuration = session.device.getConfiguration(0);
                if (configuration != null && configuration.getId() != value && value > 1) {
                    error(id, "NotFoundError", "设备没有配置 " + value);
                    return;
                }
                if (configuration != null && !session.connection.setConfiguration(configuration)) {
                    error(id, "NetworkError", "切换 USB 配置失败");
                    return;
                }
            }
            ok(id, "{}");
        });
    }

    private void selectAlternate(String id, String name, int number, int alternate) {
        io.execute(() -> {
            Session session = sessions.get(name);
            if (session == null) {
                error(id, "InvalidStateError", "设备未打开");
                return;
            }
            UsbInterface target = findInterface(session.device, number, alternate);
            if (target == null) {
                error(id, "NotFoundError", "找不到备用设置 " + number + "/" + alternate);
                return;
            }
            if (!session.connection.setInterface(target)) {
                error(id, "NetworkError", "切换备用设置失败");
                return;
            }
            session.claimed.put(number, target);
            ok(id, "{}");
        });
    }

    // ---------------------------------------------------------------- 批量收发

    private void transferIn(String id, String name, int endpointNumber, int length) {
        if (length <= 0 || length > 8 * 1024 * 1024) {
            error(id, "TypeError", "非法的读取长度：" + length);
            return;
        }
        io.execute(() -> {
            Session session = sessions.get(name);
            if (session == null || session.closed) {
                error(id, "NetworkError", "设备已断开");
                return;
            }
            UsbEndpoint endpoint = findEndpoint(session.device, endpointNumber, UsbConstants.USB_DIR_IN);
            if (endpoint == null) {
                error(id, "NotFoundError", "找不到 IN 端点 " + endpointNumber);
                return;
            }
            byte[] buffer = new byte[length];
            int read;
            try {
                // timeout=0 表示不限时：ADB 会长时间等待下一条包，交给 close() 打断
                read = session.connection.bulkTransfer(endpoint, buffer, length, 0);
            } catch (Throwable t) {
                error(id, "NetworkError", "读取失败：" + message(t));
                return;
            }
            if (read < 0) {
                error(id, "NetworkError", "USB 读取中断");
                return;
            }
            JSONObject out = new JSONObject();
            try {
                byte[] data = read == length ? buffer : java.util.Arrays.copyOf(buffer, read);
                out.put("data", Base64.encodeToString(data, Base64.NO_WRAP));
            } catch (JSONException ignored) {
                // 不会发生
            }
            ok(id, out.toString());
        });
    }

    private void transferOut(String id, String name, int endpointNumber, String data) {
        byte[] bytes;
        try {
            bytes = Base64.decode(data == null ? "" : data, Base64.NO_WRAP);
        } catch (Throwable t) {
            error(id, "TypeError", "写入数据不是合法 Base64");
            return;
        }
        io.execute(() -> {
            Session session = sessions.get(name);
            if (session == null || session.closed) {
                error(id, "NetworkError", "设备已断开");
                return;
            }
            UsbEndpoint endpoint = findEndpoint(session.device, endpointNumber, UsbConstants.USB_DIR_OUT);
            if (endpoint == null) {
                error(id, "NotFoundError", "找不到 OUT 端点 " + endpointNumber);
                return;
            }
            int written;
            try {
                written = session.connection.bulkTransfer(endpoint, bytes, bytes.length, 0);
            } catch (Throwable t) {
                error(id, "NetworkError", "写入失败：" + message(t));
                return;
            }
            if (written < 0) {
                error(id, "NetworkError", "USB 写入中断");
                return;
            }
            ok(id, "{}");
        });
    }

    // ---------------------------------------------------------------- 设备拔出

    private void onDetached(UsbDevice device) {
        String name = device.getDeviceName();
        Session session = sessions.remove(name);
        if (session != null) {
            closeQuietly(session);
        }
        known.remove(name);
        String id = awaiting.remove(name);
        if (id != null) {
            error(id, "NotFoundError", "USB 设备已拔出");
        }
        main.post(() -> sink.runJs("window.__gjUsbEvent && window.__gjUsbEvent('detach',"
                + JSONObject.quote(name) + ")"));
    }

    // ---------------------------------------------------------------- 工具

    private JSONObject describe(UsbDevice device) {
        JSONObject out = new JSONObject();
        try {
            out.put("id", device.getDeviceName());
            out.put("vendorId", device.getVendorId());
            out.put("productId", device.getProductId());
            out.put("serialNumber", safeSerial(device));
            out.put("manufacturerName", safeString(device.getManufacturerName()));
            out.put("productName", safeString(device.getProductName()));
            JSONArray configurations = new JSONArray();
            JSONObject configuration = new JSONObject();
            configuration.put("configurationValue", 1);
            JSONArray interfaces = new JSONArray();
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface iface = device.getInterface(i);
                JSONObject entry = new JSONObject();
                entry.put("interfaceNumber", iface.getId());
                JSONArray alternates = new JSONArray();
                JSONObject alt = new JSONObject();
                alt.put("alternateSetting", iface.getAlternateSetting());
                alt.put("interfaceClass", iface.getInterfaceClass());
                alt.put("interfaceSubclass", iface.getInterfaceSubclass());
                alt.put("interfaceProtocol", iface.getInterfaceProtocol());
                JSONArray endpoints = new JSONArray();
                for (int e = 0; e < iface.getEndpointCount(); e++) {
                    UsbEndpoint ep = iface.getEndpoint(e);
                    JSONObject point = new JSONObject();
                    point.put("endpointNumber", ep.getAddress() & 0x7F);
                    point.put("direction",
                            ep.getDirection() == UsbConstants.USB_DIR_IN ? "in" : "out");
                    point.put("packetSize", ep.getMaxPacketSize());
                    endpoints.put(point);
                }
                alt.put("endpoints", endpoints);
                alternates.put(alt);
                entry.put("alternates", alternates);
                // Android 把同一接口的备用设置拆成多条 UsbInterface，这里合并到一个接口下
                JSONObject existing = null;
                for (int k = 0; k < interfaces.length(); k++) {
                    JSONObject candidate = interfaces.optJSONObject(k);
                    if (candidate != null && candidate.optInt("interfaceNumber") == iface.getId()) {
                        existing = candidate;
                        break;
                    }
                }
                if (existing == null) {
                    entry.put("alternates", alternates);
                    interfaces.put(entry);
                } else {
                    existing.getJSONArray("alternates").put(alt);
                }
            }
            configuration.put("interfaces", interfaces);
            configurations.put(configuration);
            out.put("configurations", configurations);
        } catch (JSONException e) {
            Log.w(TAG, "描述设备失败", e);
        }
        return out;
    }

    private static boolean hasAdbInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == ADB_CLASS
                    && iface.getInterfaceSubclass() == ADB_SUBCLASS
                    && iface.getInterfaceProtocol() == ADB_PROTOCOL) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(UsbDevice device, JSONArray filters) {
        if (filters == null || filters.length() == 0) {
            return true;
        }
        for (int i = 0; i < filters.length(); i++) {
            JSONObject filter = filters.optJSONObject(i);
            if (filter == null) {
                continue;
            }
            if (filter.has("vendorId") && filter.optInt("vendorId") != device.getVendorId()) {
                continue;
            }
            if (filter.has("productId") && filter.optInt("productId") != device.getProductId()) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static UsbInterface findInterface(UsbDevice device, int number, int alternate) {
        UsbInterface fallback = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getId() != number) {
                continue;
            }
            if (iface.getAlternateSetting() == alternate) {
                return iface;
            }
            if (fallback == null) {
                fallback = iface;
            }
        }
        return fallback;
    }

    private static UsbEndpoint findEndpoint(UsbDevice device, int number, int direction) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            for (int e = 0; e < iface.getEndpointCount(); e++) {
                UsbEndpoint endpoint = iface.getEndpoint(e);
                if ((endpoint.getAddress() & 0x7F) == number && endpoint.getDirection() == direction) {
                    return endpoint;
                }
            }
        }
        return null;
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    private static String safeProduct(UsbDevice device) {
        String name = safeString(device.getProductName());
        return name.length() == 0 ? device.getDeviceName() : name;
    }

    private static String safeSerial(UsbDevice device) {
        try {
            String serial = device.getSerialNumber();
            return serial == null ? device.getDeviceName() : serial;
        } catch (Throwable t) {
            return device.getDeviceName();
        }
    }

    private static String hex(int value) {
        return String.format(java.util.Locale.ROOT, "%04x", value);
    }

    private static String message(Throwable t) {
        String text = t.getMessage();
        return text == null || text.length() == 0 ? t.getClass().getSimpleName() : text;
    }

    /** 云端开关：总开关或 USB 开关关掉时，任何连机操作都不执行。 */
    private boolean usbAllowed() {
        return GjgjxFlags.usb(activity);
    }

    private static void closeQuietly(Session session) {
        session.closed = true;
        session.claimed.clear();
        try {
            session.connection.close();
        } catch (Throwable ignored) {
            // 已关闭
        }
    }

    private void ok(String id, String payloadJson) {
        if (disposed) {
            return;
        }
        deliver("window.__gjUsbResult(" + JSONObject.quote(id) + ",true,"
                + JSONObject.quote(payloadJson) + ")");
    }

    private void error(String id, String name, String message) {
        if (disposed) {
            return;
        }
        JSONObject out = new JSONObject();
        try {
            out.put("error", name);
            out.put("message", message);
        } catch (JSONException ignored) {
            // 不会发生
        }
        deliver("window.__gjUsbResult(" + JSONObject.quote(id) + ",false,"
                + JSONObject.quote(out.toString()) + ")");
    }

    private void deliver(String js) {
        main.post(() -> {
            if (!disposed) {
                sink.runJs(js);
            }
        });
    }
}
