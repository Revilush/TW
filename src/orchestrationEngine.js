const { ORCHESTRATION_TICK_INTERVAL_MS } = require("../config");

class OrchestrationEngine {
  constructor({
    deviceManager,
    deviceHealthMonitor,
    pairingEngine,
    schedulerEngine,
    commandDispatcher,
    smsController,
    mediaController
  } = {}) {
    this.deviceManager = deviceManager;
    this.deviceHealthMonitor = deviceHealthMonitor;
    this.pairingEngine = pairingEngine;
    this.schedulerEngine = schedulerEngine;
    this.commandDispatcher = commandDispatcher;
    this.smsController = smsController;
    this.mediaController = mediaController;

    this.deviceStates = new Map();
    this.loopTimer = null;
  }

  start() {
    if (this.loopTimer) {
      return;
    }

    this.loopTimer = setInterval(() => {
      this.tick();
    }, ORCHESTRATION_TICK_INTERVAL_MS);

    console.log(`[ORCHESTRATION] started tick_interval_ms=${ORCHESTRATION_TICK_INTERVAL_MS}`);

    this.tick();
  }

  stop() {
    if (this.loopTimer) {
      clearInterval(this.loopTimer);
      this.loopTimer = null;
    }
  }

  isRunning() {
    return Boolean(this.loopTimer);
  }

  tick() {
    const activeDevices = this.deviceHealthMonitor.getActiveDevices();
    const activeIds = new Set(activeDevices.map((device) => device.device_id));

    for (const device of activeDevices) {
      this.ensureState(device);
    }

    for (const [deviceId, state] of this.deviceStates.entries()) {
      const currentDevice = activeDevices.find((device) => device.device_id === deviceId);

      if (!activeIds.has(deviceId)) {
        state.available = false;
        continue;
      }

      state.available = true;

      if (state.current_phase === "SMS") {
        this.ensureSmsBatch(currentDevice, state);
      } else if (state.current_phase === "MEDIA") {
        this.ensureMediaSession(currentDevice, state);
      }
    }

    this.scheduleCallPhase(activeDevices);
  }

  onCallStarted(callRecord) {
    for (const device of [callRecord.deviceA, callRecord.deviceB]) {
      const state = this.ensureState(device);
      state.current_phase = "CALL";
      state.last_error = null;
    }
  }

  onCallCompleted(callRecord) {
    for (const [currentDevice, partnerDevice] of [
      [callRecord.deviceA, callRecord.deviceB],
      [callRecord.deviceB, callRecord.deviceA]
    ]) {
      const state = this.ensureState(currentDevice);
      state.last_partner = partnerDevice.device_id;
      state.call_count += 1;
      state.available = true;
      state.last_error = callRecord.errors[0] || null;

      if (state.call_count >= 3) {
        state.current_phase = "SMS";
        state.sms_sent = 0;
        state.sms_total = 0;
        state.sms_cycle_started = false;
        state.sms_target_ids = [];
        this.ensureSmsBatch(currentDevice, state);
      } else {
        state.current_phase = "IDLE";
      }
    }
  }

  getDeviceStates() {
    const result = {};

    for (const [deviceId, state] of this.deviceStates.entries()) {
      result[deviceId] = this.serializeState(state);
    }

    return result;
  }

  scheduleCallPhase(activeDevices) {
    const eligibleDevices = activeDevices.filter((device) => {
      const state = this.ensureState(device);
      return (
        state.current_phase === "IDLE" &&
        state.call_count < 3 &&
        !this.schedulerEngine.isDeviceBusy(device.device_id)
      );
    });

    if (eligibleDevices.length < 2) {
      return;
    }

    const pairingResult = this.pairingEngine.generatePairs(eligibleDevices);

    for (const pair of pairingResult.pairs) {
      const [deviceA, deviceB] = pair.devices;
      const stateA = this.ensureState(deviceA);
      const stateB = this.ensureState(deviceB);

      try {
        this.schedulerEngine.scheduleCall(deviceA, deviceB);
        stateA.current_phase = "CALL";
        stateB.current_phase = "CALL";
      } catch (error) {
        stateA.last_error = error.message;
        stateB.last_error = error.message;
      }
    }
  }

  ensureSmsBatch(device, state) {
    if (
      !device ||
      !this.isDeviceEligible(device) ||
      state.sms_cycle_started ||
      this.smsController.hasActiveBatch(device.device_id)
    ) {
      return;
    }

    const targetDevices = this.resolveSmsTargets(device.device_id);
    if (targetDevices.length === 0) {
      state.last_error = "No SMS target available";
      return;
    }

    try {
      state.sms_cycle_started = true;
      state.sms_target_ids = targetDevices.map((targetDevice) => targetDevice.device_id);
      const batch = this.smsController.sendSmsBatch(device, targetDevices);
      state.sms_total = batch.total_messages;
      state.sms_sent = batch.sent_messages;
      state.last_error = null;
    } catch (error) {
      state.last_error = error.message;
    }
  }

  onSmsBatchCompleted(batchRecord) {
    const state = this.deviceStates.get(batchRecord.device_id);
    if (!state) {
      return;
    }

    state.sms_total = batchRecord.total_messages;
    state.sms_sent = batchRecord.sent_messages;

    if (batchRecord.status === "COMPLETED") {
      const device = this.deviceManager.getDevice(batchRecord.device_id);
      this.startMediaPhase(device, state);
    } else if (batchRecord.status === "COMPLETED_WITH_WARNINGS") {
      state.last_error = batchRecord.last_error || "SMS batch completed with warnings";
      const device = this.deviceManager.getDevice(batchRecord.device_id);
      this.startMediaPhase(device, state);
    } else {
      state.last_error = batchRecord.last_error || "SMS batch failed";
    }
  }

  startMediaPhase(device, state) {
    if (!device || !this.isDeviceEligible(device)) {
      return;
    }

    if (state.current_phase === "MEDIA") {
      this.ensureMediaSession(device, state);
      return;
    }

    try {
      this.mediaController.startPlayback(device, this.resolveYoutubeUrl(device, state));
      state.current_phase = "MEDIA";
      state.last_error = null;
    } catch (error) {
      state.last_error = error.message;
    }
  }

  ensureMediaSession(device, state) {
    if (!device || state.current_phase !== "MEDIA") {
      return;
    }

    if (!this.mediaController.hasActiveSession(device.device_id)) {
      this.startMediaPhase(device, state);
    }
  }

  onMediaSessionCompleted(sessionRecord) {
    const state = this.deviceStates.get(sessionRecord.device_id);
    if (!state) {
      return;
    }

    this.resetCycle(sessionRecord.device_id);
  }

  resetCycle(deviceId) {
    const state = this.deviceStates.get(deviceId);
    if (!state) {
      return;
    }

    state.call_count = 0;
    state.current_phase = "IDLE";
    state.sms_total = 0;
    state.sms_sent = 0;
    state.sms_cycle_started = false;
    state.sms_target_ids = [];
    state.last_error = null;
  }

  ensureState(device) {
    if (!this.deviceStates.has(device.device_id)) {
      this.deviceStates.set(device.device_id, {
        device_id: device.device_id,
        call_count: 0,
        last_partner: null,
        current_phase: "IDLE",
        sms_total: 0,
        sms_sent: 0,
        sms_cycle_started: false,
        sms_target_ids: [],
        available: true,
        last_error: null
      });
    }

    return this.deviceStates.get(device.device_id);
  }

  isDeviceEligible(device) {
    return Boolean(device && device.is_active);
  }

  resolveSmsTargets(deviceId) {
    const candidates = this.deviceManager
      .getAllDevices()
      .filter((device) => device.device_id !== deviceId && device.phone_number);

    return shuffleDevices(candidates).slice(0, 3);
  }

  resolveYoutubeUrl(device, state) {
    void state;
    return `https://www.youtube.com/watch?v=dQw4w9WgXcQ&node=${encodeURIComponent(device.device_id)}`;
  }

  serializeState(state) {
    return {
      device_id: state.device_id,
      call_count: state.call_count,
      last_partner: state.last_partner,
      current_phase: state.current_phase,
      sms_total: state.sms_total,
      sms_sent: state.sms_sent,
      sms_cycle_started: state.sms_cycle_started,
      sms_target_ids: [...state.sms_target_ids],
      available: state.available,
      last_error: state.last_error
    };
  }
}

function shuffleDevices(devices) {
  const copy = [...devices];
  for (let index = copy.length - 1; index > 0; index -= 1) {
    const randomIndex = Math.floor(Math.random() * (index + 1));
    [copy[index], copy[randomIndex]] = [copy[randomIndex], copy[index]];
  }
  return copy;
}

module.exports = {
  OrchestrationEngine
};
