const { DELAY_TYPES, getRandomDelay } = require("../config");

class MediaController {
  constructor({ commandDispatcher, onSessionCompleted } = {}) {
    this.commandDispatcher = commandDispatcher;
    this.onSessionCompleted = onSessionCompleted || (() => {});
    this.activeSessions = new Map();
  }

  startPlayback(device, youtubeUrl) {
    if (!device?.device_id) {
      throw new Error("device.device_id is required");
    }

    if (!youtubeUrl) {
      throw new Error("youtubeUrl is required");
    }

    if (this.activeSessions.has(device.device_id)) {
      throw new Error(`Media session already active for ${device.device_id}`);
    }

    const startTime = new Date();
    const durationMs = getRandomDelay(DELAY_TYPES.YT_DURATION);
    const expectedEndTime = new Date(startTime.getTime() + durationMs);

    this.commandDispatcher.sendCommand(device.device_id, {
      type: "YT",
      url: youtubeUrl,
      duration: durationMs
    });

    console.log(`[MEDIA_SESSION] device_id=${device.device_id} duration_ms=${durationMs}`);

    const sessionRecord = {
      session_id: `${device.device_id}::${startTime.getTime()}`,
      device_id: device.device_id,
      youtube_url: youtubeUrl,
      start_time: startTime.toISOString(),
      expected_end_time: expectedEndTime.toISOString(),
      duration_ms: durationMs,
      status: "ACTIVE",
      timer: setTimeout(() => {
        this.completeSession(device.device_id);
      }, durationMs)
    };

    this.activeSessions.set(device.device_id, sessionRecord);
    return this.serializeSession(sessionRecord);
  }

  getSession(deviceId) {
    const sessionRecord = this.activeSessions.get(deviceId);
    return sessionRecord ? this.serializeSession(sessionRecord) : null;
  }

  getActiveSessions() {
    return Array.from(this.activeSessions.values()).map((sessionRecord) => this.serializeSession(sessionRecord));
  }

  hasActiveSession(deviceId) {
    return this.activeSessions.has(deviceId);
  }

  clearSession(deviceId) {
    const sessionRecord = this.activeSessions.get(deviceId);
    if (!sessionRecord) {
      return;
    }

    clearTimeout(sessionRecord.timer);
    this.activeSessions.delete(deviceId);
  }

  completeSession(deviceId) {
    const sessionRecord = this.activeSessions.get(deviceId);
    if (!sessionRecord) {
      return;
    }

    clearTimeout(sessionRecord.timer);
    sessionRecord.status = "COMPLETED";
    this.activeSessions.delete(deviceId);
    this.onSessionCompleted(this.serializeSession(sessionRecord));
  }

  serializeSession(sessionRecord) {
    return {
      session_id: sessionRecord.session_id,
      device_id: sessionRecord.device_id,
      youtube_url: sessionRecord.youtube_url,
      start_time: sessionRecord.start_time,
      expected_end_time: sessionRecord.expected_end_time,
      duration_ms: sessionRecord.duration_ms,
      status: sessionRecord.status
    };
  }
}

module.exports = {
  MediaController
};
