const MAX_RECENT_PAIRS = 20;

class PairingEngine {
  constructor(options = {}) {
    this.maxRecentPairs = options.maxRecentPairs || MAX_RECENT_PAIRS;
    this.recentPairs = [];
    this.usageCount = new Map();
    this.pairLastUsedAt = new Map();
    this.sequence = 0;
  }

  generatePairs(devices) {
    return this.generateSmartPairs(devices);
  }

  generateSmartPairs(devices) {
    const normalizedDevices = Array.isArray(devices)
      ? this.dedupeDevices(devices.filter((device) => device && device.device_id))
      : [];

    if (normalizedDevices.length < 2) {
      return {
        pairs: [],
        idleDevices: normalizedDevices,
        recentPairs: [...this.recentPairs],
        usageCount: this.getUsageSnapshot()
      };
    }

    const available = this.orderDevicesByUsage(normalizedDevices);
    const pairs = [];
    const idleDevices = [];
    const usedThisRound = new Set();
    const roundPairKeys = new Set();

    for (const first of available) {
      if (usedThisRound.has(first.device_id)) {
        continue;
      }

      const second = this.findBestPartner(first, available, usedThisRound, roundPairKeys);

      if (!second) {
        idleDevices.push(first);
        continue;
      }

      const pair = this.createPair(first, second);
      pairs.push(pair);
      usedThisRound.add(first.device_id);
      usedThisRound.add(second.device_id);
      roundPairKeys.add(pair.pair_key);
      this.recordPair(pair);

      console.log(
        `[PAIR_SELECTED] ${first.device_id} ↔ ${second.device_id} (usageA=${this.getUsage(first.device_id)}, usageB=${this.getUsage(second.device_id)})`
      );
    }

    return {
      pairs,
      idleDevices,
      recentPairs: [...this.recentPairs],
      usageCount: this.getUsageSnapshot()
    };
  }

  findBestPartner(device, candidates, usedThisRound, roundPairKeys) {
    const preferredCandidates = [];
    const fallbackCandidates = [];

    for (const candidate of candidates) {
      if (candidate.device_id === device.device_id || usedThisRound.has(candidate.device_id)) {
        continue;
      }

      const pairKey = this.getPairKey(device.device_id, candidate.device_id);
      if (roundPairKeys.has(pairKey)) {
        continue;
      }

      const scoredCandidate = {
        candidate,
        usage: this.getUsage(candidate.device_id),
        pairLastUsedAt: this.pairLastUsedAt.get(pairKey) || 0,
        pairKey
      };

      if (this.isRecentPair(device.device_id, candidate.device_id)) {
        fallbackCandidates.push(scoredCandidate);
      } else {
        preferredCandidates.push(scoredCandidate);
      }
    }

    const rankedCandidates = preferredCandidates.length > 0 ? preferredCandidates : fallbackCandidates;
    if (rankedCandidates.length === 0) {
      return null;
    }

    rankedCandidates.sort((left, right) => {
      if (left.usage !== right.usage) {
        return left.usage - right.usage;
      }

      if (left.pairLastUsedAt !== right.pairLastUsedAt) {
        return left.pairLastUsedAt - right.pairLastUsedAt;
      }

      return left.candidate.device_id.localeCompare(right.candidate.device_id);
    });

    return rankedCandidates[0].candidate;
  }

  orderDevicesByUsage(devices) {
    return this.shuffleDevices(devices).sort((left, right) => {
      const usageDelta = this.getUsage(left.device_id) - this.getUsage(right.device_id);
      if (usageDelta !== 0) {
        return usageDelta;
      }

      return left.device_id.localeCompare(right.device_id);
    });
  }

  dedupeDevices(devices) {
    const seen = new Set();
    return devices.filter((device) => {
      if (seen.has(device.device_id)) {
        return false;
      }

      seen.add(device.device_id);
      return true;
    });
  }

  createPair(first, second) {
    return {
      pair_key: this.getPairKey(first.device_id, second.device_id),
      devices: [first, second],
      created_at: new Date().toISOString()
    };
  }

  recordPair(pair) {
    const [first, second] = pair.devices;
    const pairKey = pair.pair_key;

    this.recentPairs.push(pairKey);
    if (this.recentPairs.length > this.maxRecentPairs) {
      this.recentPairs.shift();
    }

    this.usageCount.set(first.device_id, this.getUsage(first.device_id) + 1);
    this.usageCount.set(second.device_id, this.getUsage(second.device_id) + 1);
    this.sequence += 1;
    this.pairLastUsedAt.set(pairKey, this.sequence);
  }

  isRecentPair(firstId, secondId) {
    return this.recentPairs.includes(this.getPairKey(firstId, secondId));
  }

  getPairKey(firstId, secondId) {
    return [firstId, secondId].sort().join("::");
  }

  getUsage(deviceId) {
    return this.usageCount.get(deviceId) || 0;
  }

  getUsageSnapshot() {
    return Object.fromEntries(this.usageCount.entries());
  }

  shuffleDevices(devices) {
    const copy = [...devices];

    for (let index = copy.length - 1; index > 0; index -= 1) {
      const randomIndex = Math.floor(Math.random() * (index + 1));
      [copy[index], copy[randomIndex]] = [copy[randomIndex], copy[index]];
    }

    return copy;
  }
}

const defaultPairingEngine = new PairingEngine();

function generatePairs(devices) {
  return defaultPairingEngine.generateSmartPairs(devices);
}

function generateSmartPairs(devices) {
  return defaultPairingEngine.generateSmartPairs(devices);
}

module.exports = {
  PairingEngine,
  generatePairs,
  generateSmartPairs,
  MAX_RECENT_PAIRS
};
