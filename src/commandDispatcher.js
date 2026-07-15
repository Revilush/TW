const { randomUUID } = require("crypto");
const WebSocket = require("ws");

class CommandDispatcher {
  constructor(deviceManager) {
    this.deviceManager = deviceManager;
    this.commandLogs = [];
  }

  sendCommand(deviceId, command) {
    const connection = this.deviceManager.getDeviceConnection(deviceId);

    if (!connection || connection.readyState !== WebSocket.OPEN) {
      const error = new Error(`Device ${deviceId} is not connected`);
      this.logCommand({
        device_id: deviceId,
        type: "COMMAND_FAILED",
        command,
        error: error.message
      });
      throw error;
    }

    const message = {
      type: "command",
      command_id: randomUUID(),
      issued_at: new Date().toISOString(),
      command
    };

    connection.send(JSON.stringify(message));
    this.logCommand({
      device_id: deviceId,
      type: "COMMAND_SENT",
      command_id: message.command_id,
      command: message.command
    });

    return message;
  }

  logResponse(payload) {
    this.logCommand({
      device_id: payload.device_id,
      type: "COMMAND_RESPONSE",
      command_id: payload.command_id || null,
      status: payload.status || "UNKNOWN",
      response: payload.response ?? payload.result ?? null
    });
  }

  getLogs(limit = 100) {
    return this.commandLogs.slice(-limit);
  }

  logCommand(entry) {
    const logEntry = {
      id: randomUUID(),
      timestamp: new Date().toISOString(),
      ...entry
    };

    this.commandLogs.push(logEntry);
    console.log(`[${logEntry.timestamp}] ${logEntry.type}`, JSON.stringify(logEntry));
    return logEntry;
  }
}

module.exports = {
  CommandDispatcher
};
