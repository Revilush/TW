const { DELAY_TYPES, getRandomDelay } = require("../config");

class SchedulerEngine {
  constructor({ commandDispatcher, onCallStarted, onCallCompleted } = {}) {
    this.commandDispatcher = commandDispatcher;
    this.onCallStarted = onCallStarted || (() => {});
    this.onCallCompleted = onCallCompleted || (() => {});
    this.activeCalls = new Map();
    this.deviceToCall = new Map();
  }

  scheduleCall(deviceA, deviceB) {
    this.assertDevicePair(deviceA, deviceB);

    if (this.isDeviceBusy(deviceA.device_id) || this.isDeviceBusy(deviceB.device_id)) {
      const activeCallId = this.deviceToCall.get(deviceA.device_id) || this.deviceToCall.get(deviceB.device_id);
      throw new Error(`Duplicate call prevented: device already scheduled in ${activeCallId}`);
    }

    const callId = this.getCallId(deviceA.device_id, deviceB.device_id);
    const waitMs = getRandomDelay(DELAY_TYPES.CALL_DELAY);
    const durationMs = getRandomDelay(DELAY_TYPES.CALL_DURATION);
    const now = Date.now();

    const callRecord = {
      call_id: callId,
      state: "WAIT",
      deviceA,
      deviceB,
      wait_ms: waitMs,
      duration_ms: durationMs,
      scheduled_at: new Date(now).toISOString(),
      scheduled_start_at: new Date(now + waitMs).toISOString(),
      scheduled_end_at: null,
      started_at: null,
      ended_at: null,
      waitTimer: null,
      endTimer: null,
      errors: []
    };

    callRecord.waitTimer = setTimeout(() => {
      this.startCall(callId);
    }, waitMs);

    this.activeCalls.set(callId, callRecord);
    this.deviceToCall.set(deviceA.device_id, callId);
    this.deviceToCall.set(deviceB.device_id, callId);

    console.log(`[CALL_SCHEDULED] ${callId} wait_ms=${waitMs} duration_ms=${durationMs}`);

    return this.serializeCall(callRecord);
  }

  getActiveCalls() {
    return Array.from(this.activeCalls.values()).map((callRecord) => this.serializeCall(callRecord));
  }

  isDeviceBusy(deviceId) {
    return this.deviceToCall.has(deviceId);
  }

  startCall(callId) {
    const callRecord = this.activeCalls.get(callId);
    if (!callRecord || callRecord.state !== "WAIT") {
      return;
    }

    callRecord.state = "START_CALL";
    callRecord.started_at = new Date().toISOString();
    callRecord.scheduled_end_at = new Date(Date.now() + callRecord.duration_ms).toISOString();

    for (const [sourceDevice, targetDevice] of [
      [callRecord.deviceA, callRecord.deviceB],
      [callRecord.deviceB, callRecord.deviceA]
    ]) {
      try {
        this.commandDispatcher.sendCommand(sourceDevice.device_id, {
          type: "CALL",
          phone_number: targetDevice.phone_number
        });
      } catch (error) {
        callRecord.errors.push(`CALL dispatch failed for ${sourceDevice.device_id}: ${error.message}`);
      }
    }

    callRecord.endTimer = setTimeout(() => {
      this.endCall(callId);
    }, callRecord.duration_ms);

    console.log(`[CALL_STARTED] ${callId} duration ${callRecord.duration_ms}ms`);
    this.onCallStarted(this.serializeCall(callRecord));
  }

  endCall(callId) {
    const callRecord = this.activeCalls.get(callId);
    if (!callRecord || callRecord.state === "END_CALL") {
      return;
    }

    callRecord.state = "END_CALL";
    callRecord.ended_at = new Date().toISOString();

    for (const device of [callRecord.deviceA, callRecord.deviceB]) {
      try {
        this.commandDispatcher.sendCommand(device.device_id, {
          type: "END_CALL"
        });
      } catch (error) {
        callRecord.errors.push(`END_CALL dispatch failed for ${device.device_id}: ${error.message}`);
      }
    }

    this.cleanupCall(callRecord);

    console.log(`[CALL_ENDED] ${callId}`);
    this.onCallCompleted(this.serializeCall(callRecord));
  }

  cleanupCall(callRecord) {
    clearTimeout(callRecord.waitTimer);
    clearTimeout(callRecord.endTimer);
    this.activeCalls.delete(callRecord.call_id);
    this.deviceToCall.delete(callRecord.deviceA.device_id);
    this.deviceToCall.delete(callRecord.deviceB.device_id);
  }

  assertDevicePair(deviceA, deviceB) {
    if (!deviceA?.device_id || !deviceB?.device_id) {
      throw new Error("Both devices must include device_id");
    }

    if (deviceA.device_id === deviceB.device_id) {
      throw new Error("A device cannot be paired with itself");
    }
  }

  getCallId(deviceAId, deviceBId) {
    return [deviceAId, deviceBId].sort().join("::");
  }

  serializeCall(callRecord) {
    return {
      call_id: callRecord.call_id,
      state: callRecord.state,
      deviceA: callRecord.deviceA,
      deviceB: callRecord.deviceB,
      wait_ms: callRecord.wait_ms,
      duration_ms: callRecord.duration_ms,
      scheduled_at: callRecord.scheduled_at,
      scheduled_start_at: callRecord.scheduled_start_at,
      scheduled_end_at: callRecord.scheduled_end_at,
      started_at: callRecord.started_at,
      ended_at: callRecord.ended_at,
      errors: [...callRecord.errors]
    };
  }
}

module.exports = {
  SchedulerEngine
};
