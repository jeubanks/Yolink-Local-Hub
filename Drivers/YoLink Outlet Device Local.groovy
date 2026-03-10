/**
 *  YoLink™ Outlet Device (Local API Edition)
 *  © 2026 Albert Mulder
 *
 *  1.0.0 - Initial working driver (YS6604 support)
 */
import groovy.json.JsonSlurper

def clientVersion() { "1.0.0" }
def copyright()     { "© 2026 Albert Mulder" }
def driverName()    { "YoLink™ Outlet Device (Local API Edition)" }

/* ============================ Preferences ============================ */
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

/* ============================== Metadata ============================= */
metadata {
    definition (name: "YoLink Outlet Device Local",
                namespace: "almulder", author: "Albert Mulder", singleThreaded: true) {
        capability "Polling"
        capability "Refresh"
        capability "Switch"
        capability "Outlet"
        capability "PowerMeter"

        command "reset"
        command "setDelay", [[name: "Delay ON (minutes)", type: "NUMBER"], [name: "Delay OFF (minutes)", type: "NUMBER"]]

        attribute "online", "String"
        attribute "devId", "String"
        attribute "firmware", "String"
        attribute "lastPoll", "String"
        attribute "lastResponse", "String"
        attribute "reportAt", "String"
        attribute "stateChangedAt", "String"

        attribute "state", "String"             // "open" | "closed"
        attribute "delayOn", "Number"           // minutes
        attribute "delayOff", "Number"          // minutes
        attribute "tz", "Number"

        attribute "loraDevNetType", "String"
        attribute "signal", "String"            // "NN dBm"
        attribute "gateways", "Number"
        attribute "gatewayId", "String"
    }
}

/* ============================== Setup ================================ */
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

    logDebug("ServiceSetup: DNI=${state.my_dni}, DeviceId=${state.devId}, Type=${state.type}, HubIP=${state.localHubIP}, TokenSet=${!!state.token}")
    reset()
}

boolean isSetup() { (state.devId && state.token && state.type) }

def installed() {
    state.driverVersion = clientVersion()
    updated()
}

def updated() {
    state.debug = (settings?.debug == true)
    state.driverVersion = clientVersion()
}

/* ========================= Polling / Refresh ========================= */
def poll(force = null) {
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
    runIn((delay ?: 1) as int, "getDevicestate")
    sendEvent(name: "lastPoll", value: new Date().format(activeFmt(), location?.timeZone), isStateChange: true)
}

/* =============================== Commands ============================ */
def on()  { setSwitchState("open") }
def off() { setSwitchState("close") }

def setDelay(delayOn=null, delayOff=null) {
    if (!isSetup()) return
    Integer onMins  = (delayOn  == null) ? null : (delayOn  as Integer)
    Integer offMins = (delayOff == null) ? null : (delayOff as Integer)
    if (onMins == null && offMins == null) return

    def params = [:]
    if (onMins  != null) params.delayOn  = Math.max(0, onMins)
    if (offMins != null) params.delayOff = Math.max(0, offMins)

    sendCommand("${state.type}.setDelay", params)
}

private void setSwitchState(String cmdState) {
    if (!isSetup()) return
    def object = sendCommand("${state.type}.setState", [state: cmdState])
    if (object && successful(object)) {
        parseDevice([data: object?.data ?: [:]])
    }
}

private Map sendCommand(String method, Map params) {
    try {
        def request = [
            method      : method,
            targetDevice: state.devId,
            token       : state.token,
            params      : params
        ]
        def object = parent.pollAPI(request, state.name ?: "Outlet", state.type ?: "Outlet")
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
        log.error "sendCommand(${method}) exception: $e"
        return null
    }
}

/* ========================= Local API Interaction ===================== */
def getDevicestate() {
    logDebug("Outlet getDevicestate()")
    try {
        def request = [
            method      : "${state.type}.getState",
            targetDevice: state.devId,
            token       : state.token
        ]
        def object = parent.pollAPI(request, state.name ?: "Outlet", state.type ?: "Outlet")
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

/* ============================= Parsing =============================== */
private void parseDevice(object) {
    def data = object?.data ?: [:]
    def st   = (data?.state instanceof Map) ? (Map) data.state : data
    def lora = (data?.loraInfo instanceof Map) ? (Map) data.loraInfo : [:]

    String rawState = st?.state
    Integer pwr     = (st?.power != null) ? (st.power as Integer) : null
    String fw       = st?.version
    Integer tz      = (st?.tz != null) ? (st.tz as Integer) : null

    Integer dOn     = (st?.delay?.on  != null) ? (st.delay.on as Integer)  : null
    Integer dOff    = (st?.delay?.off != null) ? (st.delay.off as Integer) : null

    String swState = normalizeSwitch(rawState)

    rememberState("state", rawState)
    rememberState("switch", swState)
    if (dOn  != null) rememberState("delayOn", dOn)
    if (dOff != null) rememberState("delayOff", dOff)
    if (tz   != null) rememberState("tz", tz)
    if (fw) rememberState("firmware", fw?.toUpperCase())
    if (pwr != null) sendEvent(name: "power", value: pwr, unit: "W", isStateChange: true)

    def reportAt  = data?.reportAt
    def changedAt = st?.stateChangedAt ?: data?.stateChangedAt
    if (reportAt)  rememberState("reportAt", fmtTs(reportAt))
    if (changedAt) rememberState("stateChangedAt", fmtTs(changedAt))

    String devNetType = lora?.devNetType
    def signal        = lora?.signal
    String gatewayId  = lora?.gatewayId
    def gateways      = lora?.gateways

    if (devNetType) rememberState("loraDevNetType", devNetType)
    if (signal != null) sendEvent(name: "signal", value: "${signal} dBm", isStateChange: true)
    if (gateways != null) rememberState("gateways", gateways as int)
    if (gatewayId != null) rememberState("gatewayId", gatewayId)

    logDebug("Parsed: state=${rawState}, switch=${swState}, power=${pwr}, delayOn=${dOn}, delayOff=${dOff}, fw=${fw}")
}

/* ============================ MQTT handler =========================== */
def parse(topic) { processStateData(topic?.payload) }

def processStateData(String payload) {
    try {
        def root = new JsonSlurper().parseText(payload)
        if (state?.devId && root?.deviceId && state.devId != root.deviceId) return
        rememberState("online", "true")

        def dataRaw = root?.data
        Map data = (dataRaw instanceof Map) ? dataRaw : [ state: (dataRaw?.toString()) ]

        parseDevice([data: data])
        lastResponse("MQTT Success")
    } catch (e) {
        log.warn "Outlet processStateData error: ${e}"
        lastResponse("MQTT Exception")
    }
}

/* ============================== Utilities ============================= */
def reset() {
    state.driverVersion = clientVersion()
    rememberState("switch", "off")
    lastResponse("Reset completed")
    poll(true)
}

def lastResponse(value) { sendEvent(name: "lastResponse", value: "$value", isStateChange: true) }

def rememberState(name, value, unit=null) {
    if (state."$name" != value) {
        state."$name" = value
        if (unit) sendEvent(name: "$name", value: "$value", unit: "$unit", isStateChange: true)
        else      sendEvent(name: "$name", value: "$value", isStateChange: true)
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

/* === Date/Time formatting — pulled from parent (no attribute emitted) === */
private String activeFmt() {
    try { return parent?.getDateTimeFormat() ?: "MM/dd/yyyy hh:mm:ss a" }
    catch (ignored) { return "MM/dd/yyyy hh:mm:ss a" }
}
private String fmtTs(def ts) {
    String f = activeFmt()
    try {
        Date d = null
        if (ts instanceof Number) {
            d = new Date((ts as long))
        } else if (ts instanceof String) {
            String s = ts.trim()
            if (s.isLong()) {
                d = new Date(s.toLong())
            } else {
                String[] patterns = [
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                    "yyyy-MM-dd'T'HH:mm:ssXXX"
                ]
                for (p in patterns) { try { d = Date.parse(p, s); break } catch (ignored) {} }
            }
        }
        return d ? d.format(f, location?.timeZone) : (ts?.toString())
    } catch (e) {
        return ts?.toString()
    }
}

/* === Misc helpers === */
def successful(o) { o?.code == "000000" }

def pollError(o) {
    rememberState("online", "false")
    log.warn "Polling error: ${o?.code}"
}

def logDebug(msg) { if (state.debug == true) log.debug msg }

/* Ignore temperatureScale() broadcasts from parent app */
def temperatureScale(String scale) { /* not applicable */ }
