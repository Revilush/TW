class DevicePoolManager {
  constructor() {
    this.enabled = false;
    this.phoneNumbers = new Set();
    this.updatedAt = null;
  }

  getConfig() {
    return {
      enabled: this.enabled,
      phone_numbers: [...this.phoneNumbers].sort(),
      count: this.phoneNumbers.size,
      updated_at: this.updatedAt
    };
  }

  setConfig({ enabled = this.enabled, phone_numbers: phoneNumbers = [...this.phoneNumbers] } = {}) {
    this.enabled = Boolean(enabled);
    this.phoneNumbers = new Set(normalizePhoneNumbers(phoneNumbers));
    this.updatedAt = new Date().toISOString();
    return this.getConfig();
  }

  addPhoneNumber(phoneNumber) {
    const normalized = normalizePhoneNumber(phoneNumber);
    if (!normalized) {
      throw new Error("phone_number is required");
    }

    this.phoneNumbers.add(normalized);
    this.updatedAt = new Date().toISOString();
    return this.getConfig();
  }

  removePhoneNumber(phoneNumber) {
    const normalized = normalizePhoneNumber(phoneNumber);
    if (!normalized) {
      throw new Error("phone_number is required");
    }

    this.phoneNumbers.delete(normalized);
    this.updatedAt = new Date().toISOString();
    return this.getConfig();
  }

  clear() {
    this.phoneNumbers.clear();
    this.updatedAt = new Date().toISOString();
    return this.getConfig();
  }

  getDevicePoolStatus(device) {
    const normalized = normalizePhoneNumber(device?.phone_number);

    if (!this.enabled) {
      return {
        pool_enabled: false,
        in_pool: true,
        pool_reason: "POOL_DISABLED"
      };
    }

    if (!normalized) {
      return {
        pool_enabled: true,
        in_pool: false,
        pool_reason: "MISSING_PHONE_NUMBER"
      };
    }

    if (!this.phoneNumbers.has(normalized)) {
      return {
        pool_enabled: true,
        in_pool: false,
        pool_reason: "NOT_IN_POOL"
      };
    }

    return {
      pool_enabled: true,
      in_pool: true,
      pool_reason: "IN_POOL"
    };
  }
}

function normalizePhoneNumbers(phoneNumbers) {
  if (typeof phoneNumbers === "string") {
    return phoneNumbers
      .split(/\r?\n|,/)
      .map(normalizePhoneNumber)
      .filter(Boolean);
  }

  if (!Array.isArray(phoneNumbers)) {
    return [];
  }

  return phoneNumbers.map(normalizePhoneNumber).filter(Boolean);
}

function normalizePhoneNumber(phoneNumber) {
  return String(phoneNumber || "")
    .trim()
    .replace(/[^\d+]/g, "");
}

module.exports = {
  DevicePoolManager,
  normalizePhoneNumber,
  normalizePhoneNumbers
};
