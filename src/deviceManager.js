const HEARTBEAT_TIMEOUT_MS = 2 * 60 * 1000;

class DeviceManager {
  constructor() {
    this.devices = new Map();
  }

  registerDevice(payload, connection) {
    const now = new Date();
    const existing = this.devices.get(payload.device_id);

    if (existing?.connection && existing.connection !== connection) {
      existing.connection.close(4001, "Replaced by a newer connection");
    }

    const device = {
      device_id: payload.device_id,
      phone_number: payload.phone_number,
      battery_level: this.normalizeBattery(payload.battery_level, existing?.battery_level ?? 0),
      plan_due_date: this.normalizeDate(payload.plan_due_date, existing?.plan_due_date),
      last_seen: now,
      status: "ONLINE",
      connection
    };

    this.devices.set(device.device_id, device);
    return this.serializeDevice(device);
  }

  updateHeartbeat(deviceId, updates = {}) {
    const device = this.devices.get(deviceId);
    if (!device) {
      return null;
    }

    device.last_seen = new Date();
    device.status = "ONLINE";

    if (updates.battery_level !== undefined) {
      device.battery_level = this.normalizeBattery(updates.battery_level, device.battery_level);
    }

    if (updates.plan_due_date !== undefined) {
      device.plan_due_date = this.normalizeDate(updates.plan_due_date, device.plan_due_date);
    }

    if (updates.phone_number !== undefined) {
      device.phone_number = updates.phone_number;
    }

    return this.serializeDevice(device);
  }

  attachConnection(deviceId, connection) {
    const device = this.devices.get(deviceId);
    if (!device) {
      return null;
    }

    if (device.connection && device.connection !== connection) {
      device.connection.close(4001, "Replaced by a newer connection");
    }

    device.connection = connection;
    device.last_seen = new Date();
    device.status = "ONLINE";
    return this.serializeDevice(device);
  }

  markConnectionClosed(connection) {
    for (const device of this.devices.values()) {
      if (device.connection === connection) {
        device.connection = null;
        return this.serializeDevice(this.refreshStatus(device));
      }
    }

    return null;
  }

  touch(deviceId) {
    const device = this.devices.get(deviceId);
    if (!device) {
      return null;
    }

    device.last_seen = new Date();
    device.status = "ONLINE";
    return this.serializeDevice(device);
  }

  getDevice(deviceId) {
    const device = this.devices.get(deviceId);
    return device ? this.serializeDevice(this.refreshStatus(device)) : null;
  }

  getDeviceConnection(deviceId) {
    const device = this.devices.get(deviceId);
    if (!device) {
      return null;
    }

    this.refreshStatus(device);
    return device.connection;
  }

  getAllDevices() {
    return Array.from(this.devices.values()).map((device) => this.serializeDevice(this.refreshStatus(device)));
  }

  markStaleDevicesOffline() {
    const updated = [];

    for (const device of this.devices.values()) {
      const previousStatus = device.status;
      this.refreshStatus(device);

      if (previousStatus !== device.status) {
        updated.push(this.serializeDevice(device));
      }
    }

    return updated;
  }

  refreshStatus(device) {
    const age = Date.now() - new Date(device.last_seen).getTime();
    device.status = age >= HEARTBEAT_TIMEOUT_MS ? "OFFLINE" : "ONLINE";

    if (device.status === "OFFLINE") {
      device.connection = null;
    }

    return device;
  }

  normalizeBattery(value, fallback) {
    const numeric = Number(value);
    return Number.isFinite(numeric) ? Math.max(0, Math.min(100, numeric)) : fallback;
  }

  normalizeDate(value, fallback = null) {
    if (!value) {
      return fallback;
    }

    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? fallback : date.toISOString();
  }

  serializeDevice(device) {
    return {
      device_id: device.device_id,
      phone_number: device.phone_number,
      battery_level: device.battery_level,
      plan_due_date: device.plan_due_date,
      last_seen: new Date(device.last_seen).toISOString(),
      status: device.status
    };
  }
}

module.exports = {
  DeviceManager,
  HEARTBEAT_TIMEOUT_MS
};
