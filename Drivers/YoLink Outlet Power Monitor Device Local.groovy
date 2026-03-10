/**
 *  YoLink™ Outlet Power Monitor Device (Local API Edition)
 *  © 2026 John Eubanks
 *
 *  1.0.0 - Initial driver for power-monitoring plugs/outlets (YS6614, YS6602)
 */
import groovy.json.JsonSlurper

def clientVersion() { "1.0.0" }
def copyright()     { "© 2026 John Eubanks" }
def driverName()    { "YoLink™ Outlet Power Monitor Device (Local API Edition)" }

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
    definition (name: "YoLink Outlet Power Monitor Device Local",
                namespace: "almulder", author: "Albert Mulder", singleThreaded: true) {
        capability "Polling"
        capability "Refresh"
        capability "Switch"
        capability "Outlet"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "VoltageMeasurement"
        capability "CurrentMeter"

        command "reset"
        command "setDelay", [[name: "Delay ON (minutes)", type: "NUMBER"], [name: "Delay OFF (minutes)", type: "NUMBER"]]

        attribute "online", "String"
        attribute "devId", "String"
        attribute "firmware", "String"
        attribute "lastPoll", "String"
        attribute "lastResponse", "String"
        attribute "reportAt", "String"
        attribute "stateChangedAt", "String"

        attribute "state", "String"
        attribute "delayOn", "Number"
        attribute "delayOff", "Number"
        attribute "tz", "Number"

        attribute "voltage", "Number"
        attribute "current", "Number"
        attribute "amperage", "Number"
        attribute "frequency", "Number"

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

private List<String> relayTypeCandidates() {
    LinkedHashSet<String> candidates = new LinkedHashSet<String>()
    if (state?.type) candidates << state.type.toString()
    if (state?.devId) {
        try {
            String parentType = parent?.getDeviceTypeFor(state.devId)
            if (parentType) candidates << parentType
        } catch (ignored) {}
    }
    candidates << "Outlet"
    candidates << "Switch"
    candidates << "MultiOutlet"
    return candidates as List<String>
}

private Map sendRelayCommand(String suffix, Map params=[:]) {
    if (!ensureSetupContext()) return null

    Map methodErr = null
    for (String relayType : relayTypeCandidates()) {
        try {
            String method = "${relayType}.${suffix}"
            def request = [
                method      : method,
                targetDevice: state.devId,
                token       : state.token,
                params      : params
            ]
            def object = parent.pollAPI(request, state.name ?: "Outlet", relayType)
            if (object?.code == "000000") {
                if (state.type != relayType) state.type = relayType
                rememberState("online", "true")
                lastResponse("Success")
                return object
            }
            if (object?.code == "010203") {
                methodErr = object
                continue
            }
            if (object) {
                pollError(object)
                return object
            }
        } catch (Exception e) {
            log.error "sendRelayCommand(${suffix}) exception: $e"
        }
    }

    if (methodErr) pollError(methodErr)
    else lastResponse("No response from Local API")
    return methodErr
}

private Map getStateWithFallback() {
    if (!ensureSetupContext()) return null

    Map methodErr = null
    for (String relayType : relayTypeCandidates()) {
        try {
            def request = [
                method      : "${relayType}.getState",
                targetDevice: state.devId,
                token       : state.token
            ]
            def object = parent.pollAPI(request, state.name ?: "Outlet", relayType)
            if (object?.code == "000000") {
                if (state.type != relayType) state.type = relayType
                return object
            }
            if (object?.code == "010203") {
                methodErr = object
                continue
            }
            if (object) return object
        } catch (Exception e) {
            log.error "getStateWithFallback() exception: $e"
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

def on()  { setSwitchState("open") }
def off() { setSwitchState("close") }

def setDelay(delayOn=null, delayOff=null) {
    if (!ensureSetupContext()) return
    Integer onMins  = (delayOn  == null) ? null : (delayOn  as Integer)
    Integer offMins = (delayOff == null) ? null : (delayOff as Integer)
    if (onMins == null && offMins == null) return

    def params = [:]
    if (onMins  != null) params.delayOn  = Math.max(0, onMins)
    if (offMins != null) params.delayOff = Math.max(0, offMins)

    sendRelayCommand("setDelay", params)
}

private void setSwitchState(String cmdState) {
    if (!ensureSetupContext()) return
    def object = sendRelayCommand("setState", [state: cmdState])
    if (object && successful(object)) {
        parseDevice([data: object?.data ?: [:]])
    }
}

def getDevicestate() {
    if (!ensureSetupContext()) {
        logDebug { "getDevicestate skipped: missing devId/type/token" }
        return
    }
    logDebug { "OutletPower getDevicestate()" }
    try {
        def object = getStateWithFallback()
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

private void parseDevice(object) {
    def data = object?.data ?: [:]
    def st   = (data?.state instanceof Map) ? (Map) data.state : data
    def lora = (data?.loraInfo instanceof Map) ? (Map) data.loraInfo : [:]

    String rawState = st?.state
    String fw       = st?.version
    Integer tz      = asInt(st?.tz)

    Integer dOn     = asInt(st?.delay?.on)
    Integer dOff    = asInt(st?.delay?.off)

    String swState = normalizeSwitch(rawState)

    rememberState("state", rawState)
    rememberState("switch", swState)
    if (dOn  != null) rememberState("delayOn", dOn)
    if (dOff != null) rememberState("delayOff", dOff)
    if (tz   != null) rememberState("tz", tz)
    if (fw) rememberState("firmware", fw?.toUpperCase())

    BigDecimal powerW  = extractDecimal(st, ["power", "watt", "watts", "loadPower"])
    BigDecimal energyK = extractDecimal(st, ["energy", "kwh", "energyKWh", "totalKWh", "energyTotal"])
    BigDecimal voltage = extractDecimal(st, ["voltage", "volt", "volts", "lineVoltage"])
    BigDecimal current = extractDecimal(st, ["current", "amp", "amps", "lineCurrent"])
    BigDecimal frequency = extractDecimal(st, ["frequency", "freq", "hz", "lineFrequency"])

    if (powerW != null) {
        sendEvent(name: "power", value: powerW.setScale(2, BigDecimal.ROUND_HALF_UP), unit: "W")
    }
    if (energyK != null) {
        sendEvent(name: "energy", value: energyK.setScale(4, BigDecimal.ROUND_HALF_UP), unit: "kWh")
    }
    if (voltage != null) {
        sendEvent(name: "voltage", value: voltage.setScale(2, BigDecimal.ROUND_HALF_UP), unit: "V")
    }
    if (current != null) {
        sendEvent(name: "current", value: current.setScale(3, BigDecimal.ROUND_HALF_UP), unit: "A")
        sendEvent(name: "amperage", value: current.setScale(3, BigDecimal.ROUND_HALF_UP), unit: "A")
    }
    if (frequency != null) {
        sendEvent(name: "frequency", value: frequency.setScale(2, BigDecimal.ROUND_HALF_UP), unit: "Hz")
    }

    def reportAt  = data?.reportAt
    def changedAt = st?.stateChangedAt ?: data?.stateChangedAt
    if (reportAt)  rememberState("reportAt", fmtTs(reportAt))
    if (changedAt) rememberState("stateChangedAt", fmtTs(changedAt))

    String devNetType = lora?.devNetType
    def signal        = lora?.signal
    String gatewayId  = lora?.gatewayId
    def gateways      = lora?.gateways
    Integer gwCount   = asInt(gateways)

    if (devNetType) rememberState("loraDevNetType", devNetType)
    if (signal != null) rememberState("signal", "${signal} dBm")
    if (gwCount != null) rememberState("gateways", gwCount)
    if (gatewayId != null) rememberState("gatewayId", gatewayId)

    logDebug { "Parsed: state=${rawState}, switch=${swState}, power=${powerW}, energy=${energyK}, voltage=${voltage}, current=${current}" }
}

def parse(topic) { processStateData(topic?.payload) }

def processStateData(String payload) {
    try {
        if (!payload?.trim()) {
            lastResponse("MQTT Empty Payload")
            return
        }
        def root = new JsonSlurper().parseText(payload)
        if (!state?.devId && root?.deviceId) {
            rememberState("devId", root.deviceId)
        }
        if (state?.devId && root?.deviceId && state.devId != root.deviceId) return
        rememberState("online", "true")

        def dataRaw = root?.data
        Map data = (dataRaw instanceof Map) ? dataRaw : [ state: (dataRaw?.toString()) ]

        parseDevice([data: data])
        lastResponse("MQTT Success")
    } catch (e) {
        log.warn "OutletPower processStateData error: ${e}"
        lastResponse("MQTT Exception")
    }
}

def reset() {
    state.driverVersion = clientVersion()
    rememberState("switch", "off")
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

private String normalizeSwitch(String stateValue) {
    switch ((stateValue ?: "").toLowerCase()) {
        case "open":
            return "on"
        case "closed":
        case "close":
            return "off"
        default:
            return stateValue ?: "off"
    }
}

private Integer asInt(def value) {
    if (value == null) return null
    try { return (value as Integer) } catch (ignored) { return null }
}

private BigDecimal asDecimal(def value) {
    if (value == null) return null
    try {
        if (value instanceof BigDecimal) return value
        if (value instanceof Number) return new BigDecimal(value.toString())
        String s = value.toString().trim()
        if (!s) return null
        return new BigDecimal(s)
    } catch (ignored) {
        return null
    }
}

private BigDecimal extractDecimal(Map source, List<String> keys) {
    if (!(source instanceof Map) || !keys) return null
    for (String k : keys) {
        if (source.containsKey(k)) {
            BigDecimal v = asDecimal(source[k])
            if (v != null) return v
        }
    }
    return null
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

