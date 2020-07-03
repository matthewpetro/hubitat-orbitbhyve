/*
*  Name:	Orbit B•Hyve™ Bridge
*  Author: Kurt Sanders
*  Email:	Kurt@KurtSanders.com
*  Date:	3/2019
*  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License. You may obtain a copy of the License at:
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
*  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
*  for the specific language governing permissions and limitations under the License.
*
*/
def version() { return ["V4.01", "Requires Bhyve Orbit Controller"] }
// End Version Information
import groovy.time.*
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import groovy.transform.Field
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

@Field String wsHost = "wss://api.orbitbhyve.com/v1/events"
@Field String timeStampFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

metadata {
    definition (name: "Orbit Bhyve Bridge", namespace: "kurtsanders", author: "kurt@kurtsanders.com") {
        capability "Refresh"
        capability "Sensor"
        capability "Initialize"

        attribute "is_connected", "enum", ['true','false']
        attribute "id", "string"
        attribute "type", "string"
        attribute "firmware_version", "string"
        attribute "hardware_version", "string"
        attribute "schedulerFreq", "string"
        attribute "lastupdate", "string"
        attribute "statusText", "string"
        attribute "name","string"
        attribute "type","string"
    }
}

def refresh() {
    parent.refresh()
}

def uninstalled() {
    unschedule()
}

def safeWSSend(obj) {
    if (state.webSocketOpen == false) {
        log.error "Reconnecting to Web Socket"
        interfaces.webSocket.connect(wsHost)
    }
    interfaces.webSocket.sendMessage(new JsonOutput().toJson(obj))
}

def initialize() {
    if (getDataValue("master") == "true") {
        log.debug "Connecting to Web Socket"
        interfaces.webSocket.connect(wsHost)
        pauseExecution(5000)
        def loginMsg = [
            event: "app_connection",
            orbit_session_token: parent.getApiToken()
        ]
        safeWSSend(loginMsg)
    }
    else
        parent.sendInitializeCommandToMasterHub()    
}

def parse(String message) {
    
    def payload = new JsonSlurper().parseText(message)

    switch (payload.event) {
        case "watering_in_progress_notification":
            def dev = parent.getDeviceById(payload.device_id)
            if (dev)
                dev.sendEvent(name: "valve", value: "open")
            break
        case "watering_complete":
            def dev = parent.getDeviceById(payload.device_id)
            if (dev)
                dev.sendEvent(name: "valve", value: "closed")
            break
        case "change_mode":
            def dev = parent.getDeviceById(payload.device_id)
            if (dev)
                dev.sendEvent(name: "run_mode ", value: payload.mode)
            break
        case "rain_delay":
            def dev = parent.getDeviceById(payload.device_id)
            if (dev)
                dev.sendEvent(name: "rain_delay", value: payload.delay)
            break
        case "program_changed":
            // TODO figure this out
            break
        case "device_idle":
        case "clear_low_battery":
            // Do nothing
            break
        default:
            log.debug "Unknown message: ${message}"
    }
}

def webSocketStatus(String message) {
    if (message == "status: open") {
        schedule("0/30 * * * * ? *", pingWebSocket)
        state.webSocketOpen = true
    }
    else if (message == "status: closing") {
        log.error "Lost connection to Web Socket: ${message}"
        unschedule()
        state.webSocketOpen = false
    }
    log.debug "web socket status: ${message}"
}

def sendWSMessage(valveState,device_id,zone,run_time) {
    def msg = [
        event: "change_mode",
        mode: "manual",
        device_id: device_id,
        timestamp: getTimestamp()
    ]

    if (valveState == "open") {
        msg.stations = [
            [
                station: zone.toInteger(),
                run_time: run_time
            ]
        ]
    }
    else {
        msg.stations = []
    }
    safeWSSend(msg)
}

def pingWebSocket() {
    def pingMsg = [ event: "ping"]
    safeWSSend(pingMsg)
}

def getTimestamp() {
    def date = new Date()
    return date.format(timeStampFormat, TimeZone.getTimeZone('UTC'))
}