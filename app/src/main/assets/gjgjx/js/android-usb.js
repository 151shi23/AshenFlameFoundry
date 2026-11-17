/*
 * 搞机工具箱 WebUSB 兼容层（MinewaysMobile 内置）
 *
 * 页面原本依赖 Chrome 的 navigator.usb。Android WebView 不提供该 API，
 * 这里用同名接口把它转接到原生 UsbManager（见 com.mineways.gjgjx.GjgjxUsbBridge），
 * 使 adb-daemon-webusb 无需改动即可在 App 内跑通。
 *
 * 只实现 adb-daemon-webusb 实际用到的那部分 WebUSB：
 *   getDevices / requestDevice / open / selectConfiguration / claimInterface /
 *   selectAlternateInterface / releaseInterface / transferIn / transferOut / close
 * 以及 connect / disconnect 事件。
 */
(() => {
  const bridge = window.AndroidUsb;
  if (!bridge || navigator.usb) return;

  // 云端开关：usb=false 时页面照常浏览，但不再暴露任何设备
  const cloud = window.__gjgjxCloud || { usb: true, notice: '' };
  const usbEnabled = cloud.usb !== false;

  const pending = new Map();
  let seq = 0;

  function call(method, args) {
    return new Promise((resolve, reject) => {
      const id = String(++seq);
      pending.set(id, { resolve, reject });
      try {
        bridge.call(id, method, JSON.stringify(args || {}));
      } catch (e) {
        pending.delete(id);
        reject(e);
      }
    });
  }

  window.__gjUsbResult = function (id, ok, payload) {
    const entry = pending.get(id);
    if (!entry) return;
    pending.delete(id);
    if (ok) {
      try {
        entry.resolve(payload ? JSON.parse(payload) : {});
      } catch (e) {
        entry.reject(e);
      }
      return;
    }
    let info = {};
    try {
      info = payload ? JSON.parse(payload) : {};
    } catch (e) { /* ignore */ }
    const name = info.error || 'NetworkError';
    const message = info.message || 'USB 操作失败';
    if (typeof DOMException === 'function') {
      entry.reject(new DOMException(message, name));
    } else {
      const error = new Error(message);
      error.name = name;
      entry.reject(error);
    }
  };

  function fail(name, message) {
    if (typeof DOMException === 'function') return Promise.reject(new DOMException(message, name));
    const error = new Error(message);
    error.name = name;
    return Promise.reject(error);
  }

  function bytesToBase64(bytes) {
    let text = '';
    const step = 8192;
    for (let i = 0; i < bytes.length; i += step) {
      text += String.fromCharCode.apply(null, bytes.subarray(i, i + step));
    }
    return btoa(text);
  }

  function base64ToBytes(text) {
    const binary = atob(text || '');
    const out = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
    return out;
  }

  const devicesById = new Map();

  class UsbDevice {
    constructor(info) {
      this.deviceId = info.id;
      this.vendorId = info.vendorId || 0;
      this.productId = info.productId || 0;
      this.serialNumber = info.serialNumber || '';
      this.manufacturerName = info.manufacturerName || '';
      this.productName = info.productName || '';
      this.opened = false;
      this.configuration = null;
      this.configurations = (info.configurations || []).map((cfg) => ({
        configurationValue: cfg.configurationValue,
        interfaces: (cfg.interfaces || []).map((iface) => ({
          interfaceNumber: iface.interfaceNumber,
          claimed: false,
          alternates: (iface.alternates || []).map((alt) => ({
            alternateSetting: alt.alternateSetting,
            interfaceClass: alt.interfaceClass,
            interfaceSubclass: alt.interfaceSubclass,
            interfaceProtocol: alt.interfaceProtocol,
            endpoints: (alt.endpoints || []).map((ep) => ({
              endpointNumber: ep.endpointNumber,
              direction: ep.direction,
              packetSize: ep.packetSize,
            })),
          })),
        })),
      }));
    }

    _interface(number) {
      for (const cfg of this.configurations) {
        for (const iface of cfg.interfaces) {
          if (iface.interfaceNumber === number) return iface;
        }
      }
      return null;
    }

    async open() {
      if (this.opened) return;
      await call('open', { id: this.deviceId });
      this.opened = true;
      if (!this.configuration && this.configurations.length) {
        await this.selectConfiguration(this.configurations[0].configurationValue);
      }
    }

    async selectConfiguration(value) {
      await call('selectConfiguration', { id: this.deviceId, value });
      this.configuration = this.configurations.find((c) => c.configurationValue === value) || null;
    }

    async claimInterface(number) {
      await call('claimInterface', { id: this.deviceId, number });
      const iface = this._interface(number);
      if (iface) iface.claimed = true;
    }

    async releaseInterface(number) {
      await call('releaseInterface', { id: this.deviceId, number });
      const iface = this._interface(number);
      if (iface) iface.claimed = false;
    }

    async selectAlternateInterface(number, alternate) {
      await call('selectAlternate', { id: this.deviceId, number, alternate });
    }

    async transferIn(endpointNumber, length) {
      const result = await call('transferIn', {
        id: this.deviceId, endpoint: endpointNumber, length,
      });
      return { data: new DataView(base64ToBytes(result.data)) };
    }

    async transferOut(endpointNumber, data) {
      await call('transferOut', {
        id: this.deviceId, endpoint: endpointNumber, data: bytesToBase64(data),
      });
      return { status: 'ok' };
    }

    async close() {
      await call('close', { id: this.deviceId });
      this.opened = false;
    }
  }

  if (typeof USBConnectionEvent !== 'function') {
    window.USBConnectionEvent = class USBConnectionEvent extends Event {
      constructor(type, init) {
        super(type);
        this.device = (init && init.device) || null;
      }
    };
  }

  class UsbManager extends EventTarget {
    async getDevices() {
      if (!usbEnabled) return [];
      const list = await call('getDevices', {});
      const out = [];
      for (const info of list.devices || []) {
        let device = devicesById.get(info.id);
        if (!device) {
          device = new UsbDevice(info);
          devicesById.set(info.id, device);
        }
        out.push(device);
      }
      return out;
    }

    async requestDevice(options) {
      if (!usbEnabled) {
        return fail('SecurityError', cloud.notice || '云端已关闭 USB 连机功能');
      }
      const result = await call('requestDevice', {
        filters: (options && options.filters) || [],
        exclusionFilters: (options && options.exclusionFilters) || [],
      });
      if (!result.device) {
        return fail('NotFoundError', '未选择设备');
      }
      let device = devicesById.get(result.device.id);
      if (!device) {
        device = new UsbDevice(result.device);
        devicesById.set(result.device.id, device);
      }
      return device;
    }
  }

  const manager = new UsbManager();

  window.__gjUsbEvent = function (type, deviceId) {
    const device = devicesById.get(deviceId);
    if (!device) return;
    if (type === 'detach') {
      device.opened = false;
      manager.dispatchEvent(new USBConnectionEvent('disconnect', { device }));
    } else if (type === 'attach') {
      manager.dispatchEvent(new USBConnectionEvent('connect', { device }));
    }
  };

  try {
    Object.defineProperty(navigator, 'usb', { value: manager, configurable: true });
  } catch (e) {
    try { navigator.usb = manager; } catch (e2) { /* ignore */ }
  }
})();
