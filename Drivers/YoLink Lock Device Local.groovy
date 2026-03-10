/**
 *  YoLink™ Lock Device (Local API Edition)
 *  © 2026 John Eubanks
 *
 *  1.0.0 - Initial Lock/LockV2 local driver
 */
import groovy.json.JsonSlurper

def clientVersion() { "1.0.0" }
def copyright()     { "© 2026 John Eubanks" }
def driverName()    { "YoLink™ Lock Device (Local API Edition)" }

preferences {
    input name: "info",
          type: "paragraph",
          title: "Driver Info",
          description: """<b>Driver:</b> ${driverName()}<br>
                          <b>Version:</b> v${clientVersion()}<br>
                          <b>Date/Time Format:</b> ${activeFmt()}<br><br>
                          ${copyright()}"""
    input name: "debug", type: "bool", title: "Enable debug logging", defaultValue: false
}

metadata {
    definition (name: "YoLink Lock Device Local",
                namespace: "almulder", author: "Albert Mulder", singleThreaded: true) {
        capability "Polling"
        capability "Refresh"
        capability "Lock"
        capability "Battery"
        capability "SignalStrength"

        command "reset"
        command "fetchState"
        command "setAutoLock", [[name: "Auto Lock Seconds", type: "NUMBER"]]
        command "setSoundLevel", [[name: "Sound Level (0-3)", type: "NUMBER"]]
        command "setHanding", [[name: "Handing (left|right)", type: "STRING"]]
        command "setSetButtonEnabled", [[name: "Enable Set Button (true|false)", type: "BOOL"]]

        attribute "online", "String"
        attribute "devId", "String"
        attribute "firmware", "String"
        attribute "hwVersion", "String"
        attribute "lastPoll", "String"
        attribute "lastResponse", "String"
        attribute "reportAt", "String"
        attribute "stateChangedAt", "String"

        attribute "state", "String"
        attribute "batteryRaw", "Number"
        attribute "tz", "Number"
        attribute "autoLock", "Number"
        attribute "enableSetButton", "String"
        attribute "rlSet", "String"
        attribute "soundLevel", "Number"

        attribute "loraDevNetType", "String"
        attribute "signal", "String"
        attribute "gateways", "Number"
        attribute "gatewayId", "String"
    }
}

void ServiceSetup(Hubitat_dni, subnetId, devname, devtype, devtoken, devId,
                  localHubIP=null, clientId=null, clientSecret=null) {
    state.my_dni       = Hubitat_dni
    state.subnetId     = subnetId
    state.name         = devname
    state.type         = devtype
    state.token        = devtoken
    state.devId        = devId
    state.localHubIP   = localHubIP
    state.clientId     = clientId
    state.clientSecret = clientSecret
    rememberState("devId", devId)

    logDebug { "ServiceSetup: DNI=${state.my_dni}, DeviceId=${state.devId}, Type=${state.type}, HubIP=${state.localHubIP}, TokenSet=${!!state.token}" }
    reset()
}

boolean isSetup() { (state.devId && state.token && state.type) }

private boolean ensureSetupContext() {
    if (!state.devId) {
        String dni = device?.deviceNetworkId
        if (dni) state.devId = dni
    }

    if (!state.type && state.devId) {
        try {
            String detectedType = parent?.getDeviceTypeFor(state.devId)
            if (detectedType) state.type = detectedType
        } catch (ignored) {}
    }

    if (!state.token && state.devId) {
        try {
            String token = parent?.getDeviceTokenFor(state.devId)
            if (!token) token = parent?.refreshDeviceTokenFor(state.devId)
            if (token) state.token = token
        } catch (ignored) {}
    }

    return isSetup()
}

private List<String> lockTypeCandidates() {
    LinkedHashSet<String> candidates = new LinkedHashSet<String>()
    if (state?.type) candidates << state.type.toString()
    if (state?.devId) {
        try {
            String parentType = parent?.getDeviceTypeFor(state.devId)
            if (parentType) candidates << parentType
        } catch (ignored) {}
    }
    candidates << "LockV2"
    candidates << "Lock"
    return candidates as List<String>
}

private Map sendLockRequest(String method, Map params=null, String requestType="Lock") {
    try {
        Map req = [
            method      : method,
            targetDevice: state.devId,
            token       : state.token
        ]
        if (params != null) req.params = params

        def object = parent.pollAPI(req, state.name ?: "Lock", requestType)
        if (object) {
            if (successful(object)) {
                rememberState("online", "true")
                lastResponse("Success")
            } else {
                pollError(object)
            }
        } else {
            lastResponse("No response from Local API")
        }
        return object
    } catch (Exception e) {
        lastResponse("Exception $e")
        log.error "sendLockRequest(${method}) exception: $e"
        return null
    }
}

private Map getStateWithFallback(boolean fetchFirst = false) {
    if (!ensureSetupContext()) return null

    List<String> methods = fetchFirst ? ["fetchState", "getState"] : ["getState", "fetchState"]

    // Fast path: known type — 1-2 API calls instead of scanning all candidates
    if (state.type) {
        String knownType = state.type.toString()
        for (String op : methods) {
            def object = sendLockRequest("${knownType}.${op}", null, knownType)
            if (object?.code == "000000") return object
            if (object?.code != "010203") return object   // real error, no benefit trying other types
        }
        // All methods returned 010203 — type must have changed; fall through to rediscovery
        logDebug { "getStateWithFallback: known type '${knownType}' unsupported, rediscovering..." }
    }

    // Slow path: full candidate scan (first install or after API version change)
    Map methodErr = null
    for (String lockType : lockTypeCandidates()) {
        if (lockType == state.type?.toString()) continue   // already tried in fast path
        for (String op : methods) {
            def object = sendLockRequest("${lockType}.${op}", null, lockType)
            if (object?.code == "000000") {
                if (state.type != lockType) state.type = lockType
                return object
            }
            if (object?.code == "010203") {
                methodErr = object
                continue
            }
            if (object) return object
        }
    }
    return methodErr
}

def installed() {
    state.driverVersion = clientVersion()
    updated()
}

def updated() {
    state.debug = (settings?.debug == true)
    state.driverVersion = clientVersion()
}

def poll(force = null) {
    if (!ensureSetupContext()) {
        logDebug { "Poll skipped: device not fully configured yet" }
        return
    }
    def min_seconds = 5
    def nowMs = now()
    if (force) state.lastPollMs = nowMs - (min_seconds * 1000)
    if (!state.lastPollMs || nowMs - state.lastPollMs >= (min_seconds * 1000)) {
        state.lastPollMs = nowMs
        pollDevice()
    } else {
        log.warn "Poll skipped (rate limit ${min_seconds}s)"
    }
}
def refresh() { poll(true) }

def pollDevice(delay=1) {
    if (!ensureSetupContext()) {
        logDebug { "pollDevice skipped: missing devId/type/token" }
        return
    }
    int d = (delay == null) ? 1 : (delay as int)
    if (d <= 0) getDevicestate()
    else runIn(d, "getDevicestate")
    sendEvent(name: "lastPoll", value: fmtTs(now()))
}

def getDevicestate() {
    if (!ensureSetupContext()) {
        logDebug { "getDevicestate skipped: missing devId/type/token" }
        return
    }
    logDebug { "Lock getDevicestate()" }
    try {
        def object = getStateWithFallback(false)
        if (object) {
            if (successful(object)) {
                parseDevice(object)
                rememberState("online", "true")
                lastResponse("Success")
            } else {
                pollError(object)
            }
        } else {
            lastResponse("No response from Local API")
        }
    } catch (Exception e) {
        lastResponse("Exception $e")
        log.error "getDevicestate() exception: $e"
    }
}

def fetchState() {
    if (!ensureSetupContext()) return
    def object = getStateWithFallback(true)
    if (object && successful(object)) parseDevice(object)
}

def lock()   { setLockState("locked") }
def unlock() { setLockState("unlocked") }

private void setLockState(String targetState) {
    if (!ensureSetupContext()) return

    // Fast path: known type — 1 API call instead of scanning all candidates
    if (state.type) {
        String knownType = state.type.toString()
        Map params = (knownType == "LockV2") ? [state: [lock: targetState]] : [state: targetState]
        def object = sendLockRequest("${knownType}.setState", params, knownType)
        if (object?.code == "000000") {
            parseDevice([data: object?.data ?: [:]])
            return
        }
        if (object?.code != "010203") {
            if (object) pollError(object)
            return   // real error, no benefit trying other types
        }
        // 010203 — type must have changed; fall through to rediscovery
        logDebug { "setLockState: known type '${knownType}' unsupported, rediscovering..." }
    }

    // Slow path: candidate scan (first install or after API version change)
    Map methodErr = null
    for (String lockType : lockTypeCandidates()) {
        if (lockType == state.type?.toString()) continue   // already tried in fast path
        Map params = (lockType == "LockV2") ? [state: [lock: targetState]] : [state: targetState]
        def object = sendLockRequest("${lockType}.setState", params, lockType)
        if (object?.code == "000000") {
            if (state.type != lockType) state.type = lockType
            parseDevice([data: object?.data ?: [:]])
            return
        }
        if (object?.code == "010203") {
            methodErr = object
            continue
        }
        if (object) return
    }
    if (methodErr) pollError(methodErr)
}

def setAutoLock(seconds) {
    Integer sec = asInt(seconds)
    if (sec == null) return
    setLockAttributes([autoLock: Math.max(0, sec)])
}

def setSoundLevel(level) {
    Integer lvl = asInt(level)
    if (lvl == null) return
    setLockAttributes([soundLevel: Math.max(0, Math.min(3, lvl))])
}

def setHanding(handing) {
    String v = (handing ?: "").toString().toLowerCase()
    if (!(v in ["left", "right"])) return
    setLockAttributes([rlSet: v])
}

def setSetButtonEnabled(enabled) {
    if (enabled == null) return
    setLockAttributes([enableSetButton: (enabled == true || enabled.toString().equalsIgnoreCase("true"))])
}

private void setLockAttributes(Map attrs) {
    if (!ensureSetupContext() || !attrs) return

    for (String lockType : lockTypeCandidates()) {
        if (lockType != "LockV2") continue
        def object = sendLockRequest("LockV2.setAttributes", attrs, lockType)
        if (object && successful(object)) {
            parseDevice([data: object?.data ?: [:]])
            return
        }
        if (object && object?.code != "010203") return
    }
}

private void parseDevice(object) {
    def data = object?.data ?: [:]
    Map st = [:]
    if (data?.state instanceof Map) st = (Map) data.state

    Map attrs = [:]
    if (data?.attributes instanceof Map) attrs = (Map) data.attributes
    else if (st?.attributes instanceof Map) attrs = (Map) st.attributes

    Map lora = (data?.loraInfo instanceof Map) ? (Map) data.loraInfo : [:]

    String rawLock = [st?.lock, st?.state, data?.lock].find { it != null }?.toString()
    String lockState = normalizeLock(rawLock)

    Integer battery4 = asInt([data?.battery, st?.battery].find { it != null })
    Integer batteryPct = (battery4 != null) ? (parent?.batterylevel(battery4 as String) as Integer) : null

    String fw = [data?.version, st?.version].find { it != null }?.toString()
    String hw = [data?.hwVersion, st?.hwVersion].find { it != null }?.toString()
    Integer tz = asInt([data?.tz, st?.tz].find { it != null })

    Integer autoLock = asInt(attrs?.autoLock)
    Integer soundLevel = asInt(attrs?.soundLevel)
    def enableSetBtn = attrs?.enableSetButton
    String rlSet = attrs?.rlSet?.toString()

    def reportAt = data?.reportAt
    def changedAt = data?.stateChangedAt ?: st?.stateChangedAt

    rememberState("state", rawLock)
    rememberState("lock", lockState)
    if (battery4 != null) rememberState("batteryRaw", battery4)
    if (batteryPct != null) rememberBatteryState(batteryPct as int)
    if (fw) rememberState("firmware", fw.toUpperCase())
    if (hw) rememberState("hwVersion", hw.toUpperCase())
    if (tz != null) rememberState("tz", tz)

    if (autoLock != null) rememberState("autoLock", autoLock)
    if (soundLevel != null) rememberState("soundLevel", soundLevel)
    if (enableSetBtn != null) rememberState("enableSetButton", enableSetBtn.toString())
    if (rlSet) rememberState("rlSet", rlSet)

    if (reportAt) rememberState("reportAt", fmtTs(reportAt))
    if (changedAt) rememberState("stateChangedAt", fmtTs(changedAt))

    String devNetType = lora?.devNetType?.toString()
    Integer gateways = asInt(lora?.gateways)
    String gatewayId = lora?.gatewayId?.toString()
    def signal = lora?.signal

    if (devNetType) rememberState("loraDevNetType", devNetType)
    if (signal != null) rememberState("signal", "${signal} dBm")
    if (gateways != null) rememberState("gateways", gateways)
    if (gatewayId) rememberState("gatewayId", gatewayId)

    logDebug { "Parsed: lock=${lockState}, batteryRaw=${battery4}, batteryPct=${batteryPct}, autoLock=${autoLock}, signal=${signal}" }
}

def parse(topic) { processStateData(topic?.payload) }

def processStateData(String payload) {
    try {
        if (!payload?.trim()) {
            lastResponse("MQTT Empty Payload")
            return
        }

        def root = new JsonSlurper().parseText(payload)
        if (!state?.devId && root?.deviceId) rememberState("devId", root.deviceId)
        if (state?.devId && root?.deviceId && state.devId != root.deviceId) return

        rememberState("online", "true")

        def dataRaw = root?.data
        Map data = (dataRaw instanceof Map) ? dataRaw : [ state: (dataRaw?.toString()) ]

        parseDevice([data: data])
        lastResponse("MQTT Success")
    } catch (e) {
        log.warn "Lock processStateData error: ${e}"
        lastResponse("MQTT Exception")
    }
}

def reset() {
    state.driverVersion = clientVersion()
    rememberState("lock", "unknown")
    lastResponse("Reset completed")
    poll(true)
}

def lastResponse(value) { sendEvent(name: "lastResponse", value: "$value") }

def rememberState(name, value, unit=null) {
    if (state."$name" != value) {
        state."$name" = value
        if (unit) sendEvent(name: "$name", value: "$value", unit: "$unit")
        else      sendEvent(name: "$name", value: "$value")
    }
}

def rememberBatteryState(def value) {
    if (state.battery != value) {
        state.battery = value
        sendEvent(name: "battery", value: value?.toString(), unit: "%")
    }
}

private String normalizeLock(String v) {
    String s = (v ?: "").toString().toLowerCase()
    if (s in ["locked", "lock"]) return "locked"
    if (s in ["unlocked", "unlock"]) return "unlocked"
    return "unknown"
}

private Integer asInt(def value) {
    if (value == null) return null
    try { return (value as Integer) } catch (ignored) { return null }
}

private String activeFmt() {
    try { return parent?.getDateTimeFormat() ?: "MM/dd/yyyy hh:mm:ss a" }
    catch (ignored) { return "MM/dd/yyyy hh:mm:ss a" }
}

private String fmtTs(def ts) {
    try { return parent?.fmtTs(ts) } catch (ignored) {}
    return ts?.toString()
}

def successful(o) { o?.code == "000000" }

def pollError(o) {
    rememberState("online", "false")
    log.warn "Polling error: ${o?.code}"
}

def logDebug(Closure msg) { if (state.debug == true) log.debug msg() }

def temperatureScale(String scale) { }

