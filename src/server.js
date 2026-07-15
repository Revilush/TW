const express = require("express");
const http = require("http");
const { DeviceManager } = require("./deviceManager");
const { DeviceHealthMonitor } = require("./deviceHealthMonitor");
const { CommandDispatcher } = require("./commandDispatcher");
const { createWebSocketHandler } = require("./websocketHandler");
const { PairingEngine } = require("./pairingEngine");
const { SchedulerEngine } = require("./schedulerEngine");
const { OrchestrationEngine } = require("./orchestrationEngine");
const { SmsController } = require("./smsController");
const { MediaController } = require("./mediaController");
const { createDashboardHub, buildDashboardSnapshot } = require("./dashboardHub");
const { DevicePoolManager } = require("./devicePoolManager");

const PORT = Number(process.env.PORT || 3000);

const app = express();
const server = http.createServer(app);

const deviceManager = new DeviceManager();
const devicePoolManager = new DevicePoolManager();
const deviceHealthMonitor = new DeviceHealthMonitor({
  deviceManager,
  devicePoolManager
});
const commandDispatcher = new CommandDispatcher(deviceManager);
const pairingEngine = new PairingEngine();
const smsController = new SmsController({
  commandDispatcher,
  onBatchCompleted: (batch) => orchestrationEngine.onSmsBatchCompleted(batch)
});
const mediaController = new MediaController({
  commandDispatcher,
  onSessionCompleted: (session) => orchestrationEngine.onMediaSessionCompleted(session)
});
const schedulerEngine = new SchedulerEngine({
  commandDispatcher,
  onCallStarted: (call) => orchestrationEngine.onCallStarted(call),
  onCallCompleted: (call) => orchestrationEngine.onCallCompleted(call)
});
const orchestrationEngine = new OrchestrationEngine({
  deviceManager,
  deviceHealthMonitor,
  pairingEngine,
  schedulerEngine,
  commandDispatcher,
  smsController,
  mediaController
});
const dashboardHub = createDashboardHub({
  deviceHealthMonitor,
  orchestrationEngine,
  schedulerEngine,
  smsController,
  mediaController,
  devicePoolManager
});

app.use(express.json());
app.use((req, res, next) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");

  if (req.method === "OPTIONS") {
    return res.sendStatus(204);
  }

  return next();
});

app.get("/health", (_req, res) => {
  res.json({
    ok: true,
    uptime_seconds: Math.round(process.uptime())
  });
});

app.get("/dashboard/snapshot", (_req, res) => {
  res.json(
    buildDashboardSnapshot({
      deviceHealthMonitor,
      orchestrationEngine,
      schedulerEngine,
      smsController,
      mediaController,
      devicePoolManager
    })
  );
});

app.get("/pool", (_req, res) => {
  res.json(devicePoolManager.getConfig());
});

app.put("/pool", (req, res) => {
  const config = devicePoolManager.setConfig(req.body || {});
  dashboardHub.broadcastSnapshot();
  res.json(config);
});

app.post("/pool/numbers", (req, res) => {
  try {
    const config = devicePoolManager.addPhoneNumber(req.body?.phone_number);
    dashboardHub.broadcastSnapshot();
    return res.status(201).json(config);
  } catch (error) {
    return res.status(400).json({
      error: error.message
    });
  }
});

app.delete("/pool/numbers/:phoneNumber", (req, res) => {
  try {
    const config = devicePoolManager.removePhoneNumber(req.params.phoneNumber);
    dashboardHub.broadcastSnapshot();
    return res.json(config);
  } catch (error) {
    return res.status(400).json({
      error: error.message
    });
  }
});

app.get("/devices", (_req, res) => {
  const devices = deviceHealthMonitor.getAllDevices();
  res.json({
    count: devices.length,
    devices
  });
});

app.get("/devices/active", (_req, res) => {
  const devices = deviceHealthMonitor.getActiveDevices();
  res.json({
    count: devices.length,
    devices
  });
});

app.post("/pairs/generate", (_req, res) => {
  const activeDevices = deviceHealthMonitor.getActiveDevices();
  const pairingResult = pairingEngine.generatePairs(activeDevices);

  res.json({
    device_count: activeDevices.length,
    pair_count: pairingResult.pairs.length,
    idle_count: pairingResult.idleDevices.length,
    ...pairingResult
  });
});

app.get("/calls/active", (_req, res) => {
  const activeCalls = schedulerEngine.getActiveCalls();
  res.json({
    count: activeCalls.length,
    calls: activeCalls
  });
});

app.post("/calls/schedule", (req, res) => {
  const { deviceAId, deviceBId } = req.body || {};

  if (!deviceAId || !deviceBId) {
    return res.status(400).json({
      error: "deviceAId and deviceBId are required"
    });
  }

  const deviceA = deviceManager.getDevice(deviceAId);
  const deviceB = deviceManager.getDevice(deviceBId);

  if (!deviceA || !deviceB) {
    return res.status(404).json({
      error: "One or both devices were not found"
    });
  }

  try {
    const call = schedulerEngine.scheduleCall(deviceA, deviceB);
    return res.status(202).json({
      status: "scheduled",
      call
    });
  } catch (error) {
    return res.status(409).json({
      error: error.message
    });
  }
});

app.post("/calls/next", (_req, res) => {
  orchestrationEngine.tick();
  dashboardHub.broadcastSnapshot();
  return res.status(202).json({
    status: "tick_triggered",
    active_calls: schedulerEngine.getActiveCalls(),
    device_states: orchestrationEngine.getDeviceStates()
  });
});

app.get("/orchestration/state", (_req, res) => {
  res.json({
    device_health: deviceHealthMonitor.getAllDevices(),
    device_states: orchestrationEngine.getDeviceStates(),
    active_calls: schedulerEngine.getActiveCalls(),
    active_sms_batches: smsController.getActiveBatches(),
    active_media_sessions: mediaController.getActiveSessions()
  });
});

app.post("/media/start", (req, res) => {
  const { deviceId, youtubeUrl } = req.body || {};
  const device = deviceManager.getDevice(deviceId);

  if (!deviceId || !youtubeUrl) {
    return res.status(400).json({
      error: "deviceId and youtubeUrl are required"
    });
  }

  if (!device) {
    return res.status(404).json({
      error: "Device not found"
    });
  }

  try {
    const session = mediaController.startPlayback(device, youtubeUrl);
    dashboardHub.broadcastSnapshot();
    return res.status(202).json({
      status: "started",
      session
    });
  } catch (error) {
    return res.status(409).json({
      error: error.message
    });
  }
});

app.post("/sms/send", (req, res) => {
  const { deviceId, targetNumber, messages } = req.body || {};
  const device = deviceManager.getDevice(deviceId);

  if (!deviceId || !targetNumber) {
    return res.status(400).json({
      error: "deviceId and targetNumber are required"
    });
  }

  if (!device) {
    return res.status(404).json({
      error: "Device not found"
    });
  }

  try {
    const batch = smsController.sendSmsBatch(device, targetNumber, messages);
    dashboardHub.broadcastSnapshot();
    return res.status(202).json({
      status: "scheduled",
      batch
    });
  } catch (error) {
    return res.status(409).json({
      error: error.message
    });
  }
});

app.post("/orchestration/start", (_req, res) => {
  orchestrationEngine.start();
  dashboardHub.broadcastSnapshot();
  res.status(202).json({
    status: "running",
    orchestration_running: orchestrationEngine.isRunning()
  });
});

app.post("/orchestration/stop", (_req, res) => {
  orchestrationEngine.stop();
  dashboardHub.broadcastSnapshot();
  res.status(202).json({
    status: "stopped",
    orchestration_running: orchestrationEngine.isRunning()
  });
});

app.post("/devices/:deviceId/ring", (req, res) => {
  const { deviceId } = req.params;

  try {
    const message = commandDispatcher.sendCommand(deviceId, {
      type: "RING"
    });
    dashboardHub.broadcastSnapshot();
    return res.status(202).json({
      status: "queued",
      message
    });
  } catch (error) {
    return res.status(409).json({
      error: error.message
    });
  }
});

app.post("/devices/:deviceId/commands", (req, res) => {
  const { deviceId } = req.params;
  const { command } = req.body || {};

  if (!command) {
    return res.status(400).json({
      error: "command is required"
    });
  }

  try {
    const message = commandDispatcher.sendCommand(deviceId, command);
    return res.status(202).json({
      status: "queued",
      message
    });
  } catch (error) {
    return res.status(409).json({
      error: error.message
    });
  }
});

app.get("/logs/commands", (req, res) => {
  const limit = Number(req.query.limit || 100);
  res.json({
    count: Math.min(limit, commandDispatcher.getLogs(limit).length),
    logs: commandDispatcher.getLogs(limit)
  });
});

const deviceSocketServer = createWebSocketHandler({
  deviceManager,
  deviceHealthMonitor,
  commandDispatcher
});

server.on("upgrade", (request, socket, head) => {
  const { url } = request;

  if (url === "/ws") {
    deviceSocketServer.handleUpgrade(request, socket, head, (ws) => {
      deviceSocketServer.emit("connection", ws, request);
    });
    return;
  }

  if (url === "/dashboard/ws") {
    dashboardHub.wss.handleUpgrade(request, socket, head, (ws) => {
      dashboardHub.wss.emit("connection", ws, request);
    });
    return;
  }

  socket.destroy();
});

server.listen(PORT, () => {
  orchestrationEngine.start();
  console.log(`Portal server listening on port ${PORT}`);
  console.log(`HTTP endpoints ready at http://localhost:${PORT}`);
  console.log(`WebSocket endpoint ready at ws://localhost:${PORT}/ws`);
  console.log(`Dashboard WebSocket ready at ws://localhost:${PORT}/dashboard/ws`);
});
