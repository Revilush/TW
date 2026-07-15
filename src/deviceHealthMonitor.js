const { HEARTBEAT_TIMEOUT_MS } = require("./deviceManager");

const LOW_BATTERY_THRESHOLD = 20;

class DeviceHealthMonitor {
  constructor({ deviceManager, devicePoolManager } = {}) {
    this.deviceManager = deviceManager;
    this.devicePoolManager = devicePoolManager;
  }

  getDeviceHealth(device) {
    if (!device) {
      return {
        status: "UNKNOWN",
        is_active: false,
        reason: "DEVICE_MISSING"
      };
    }

    const now = Date.now();
    const lastSeen = new Date(device.last_seen).getTime();
    const planDueDate = device.plan_due_date ? new Date(device.plan_due_date).getTime() : 0;

    if (!Number.isFinite(lastSeen) || now - lastSeen > HEARTBEAT_TIMEOUT_MS) {
      return {
        status: "OFFLINE",
        is_active: false,
        reason: "HEARTBEAT_TIMEOUT"
      };
    }

    if (!planDueDate || planDueDate <= now) {
      return {
        status: "BLOCKED",
        is_active: false,
        reason: "PLAN_EXPIRED"
      };
    }

    if (Number(device.battery_level) < LOW_BATTERY_THRESHOLD) {
      return {
        status: "LOW_POWER",
        is_active: false,
        reason: "BATTERY_BELOW_THRESHOLD"
      };
    }

    return {
      status: "ONLINE",
      is_active: true,
      reason: "HEALTHY"
    };
  }

  refreshDevice(device) {
    const health = this.getDeviceHealth(device);
    const pool = this.devicePoolManager?.getDevicePoolStatus(device) || {
      pool_enabled: false,
      in_pool: true,
      pool_reason: "POOL_DISABLED"
    };

    return {
      ...device,
      status: health.status,
      health_reason: health.reason,
      is_active: health.is_active && pool.in_pool,
      is_healthy: health.is_active,
      ...pool
    };
  }

  getAllDevices() {
    return this.deviceManager.getAllDevices().map((device) => this.refreshDevice(device));
  }

  getActiveDevices() {
    return this.getAllDevices().filter((device) => device.is_active);
  }

  updateDeviceStatuses() {
    return this.getAllDevices();
  }
}

module.exports = {
  DeviceHealthMonitor,
  LOW_BATTERY_THRESHOLD
};
