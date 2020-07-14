/*
*  Name:	Orbit B•Hyve™ Sprinler Timer
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

import groovy.time.*
import java.text.SimpleDateFormat
import groovy.transform.Field
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

@Field static String wsHost = "wss://api.orbitbhyve.com/v1/events"
@Field static String timeStampFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
@Field static boolean webSocketOpen = false
@Field static Object socketStatusLock = new Object()

metadata {
    definition (name: "Orbit Bhyve Sprinkler Timer", namespace: "kurtsanders", author: "kurt@kurtsanders.com") {
        capability "Refresh"
        capability "Sensor"
        capability "Battery"
        capability "Valve"
        capability "Initialize"

        attribute "is_connected", "bool"
        attribute "manual_preset_runtime_min", "number"
        attribute "next_start_programs", "string"
        attribute "next_start_time", "number"
        attribute "preset_runtime", "number"
        attribute "programs", "string"
        attribute "rain_icon", "string"
        attribute "rain_delay", "string"
        attribute "run_mode", "enum", ["auto, manual"]
        attribute "sprinkler_type", "string"
        attribute "start_times", "string"
        attribute "station", "string"
        attribute "scheduled_auto_on", "bool"
        attribute "water_volume_gal", "number"
        attribute "water_flow_rate", "number"
    }
}

def refresh() {
    parent.refresh()
}

def installed() {
    setWebSocketStatus(false)
    state.retryCount = 0
    state.nextRetry = 0
    sendEvent(name: "valve", value: "closed")
}

def uninstalled() {
    unschedule()
    if (getDataValue("master") == "true") {
        try {
            interfaces.webSocket.close()
        }
        catch (e) {
            
        }
    }
}

def open() {
    parent.sendRequest('open', parent.getOrbitDeviceIdFromDNI(device.deviceNetworkId), device.latestValue('station'),device.latestValue('preset_runtime') )
}

def close() {
    parent.sendRequest('close', parent.getOrbitDeviceIdFromDNI(device.deviceNetworkId), device.latestValue('station'),device.latestValue('preset_runtime') )
}

def safeWSSend(obj, retry) {
    synchronized (socketStatusLock) {
        if (!isWebSocketOpen()) {
            log.debug "Asked to send a message but the socket is closed"
            try {
                interfaces.webSocket.close()
            }
            catch (e) {

            }
            if (state.nextRetry == 0 || now() >= state.nextRetry) {
                log.debug "Reconnecting to Web Socket"
                state.retryCount++
                if (state.retryCount == 1)
                    parent.OrbitBhyveLogin()
                state.nextRetry = now() + (30000*((state.retryCount < 10) ? state.retryCount : 10))
                interfaces.webSocket.connect(wsHost)
                if (retry)
                    state.retryCommand = new JsonOutput().toJson(obj)
                return
            }
            else {
                log.warn "Waiting until ${new Date(state.nextRetry)} to reconnect ${state.retryCount}"
                return
            }
        }
        else
            state.retryCount = 0
        
        interfaces.webSocket.sendMessage(new JsonOutput().toJson(obj))
    }
}

def initialize() {
    synchronized (socketStatusLock) {
        if (getDataValue("master") == "true") {
            try {
                setWebSocketStatus(false)
                interfaces.webSocket.close()
            }
            catch (e) {
                
            }
            log.debug "Connecting to Web Socket"
            interfaces.webSocket.connect(wsHost)
        }
    }
}

def parse(String message) {
    def payload = new JsonSlurper().parseText(message)

    switch (payload.event) {
        case "watering_in_progress_notification":
        log.debug "Watering started: ${payload}"
            def dev = parent.getDeviceByIdAndStation(payload.device_id, payload.current_station)
            if (dev)
                dev.sendEvent(name: "valve", value: "open")
            break
        case "watering_complete":
        log.debug "Watering complete: ${payload}"
            def dev = parent.getDeviceById(payload.device_id)
            if (dev) 
                dev.sendEvent(name: "valve", value: "closed")
            break
        case "change_mode":
            if (payload.stations != null) {
                for (station in payload.stations) {
                    def dev = parent.getDeviceByIdAndStation(payload.device_id, station.station)
                    if (dev)
                        dev.sendEvent(name: "run_mode", value: payload.mode)
                }
            }
            else {
                def dev = parent.getDeviceById(payload.device_id)
                if (dev)
                    dev.sendEvent(name: "run_mode", value: payload.mode)
            }
            break
        case "rain_delay":
            def dev = parent.getDeviceById(payload.device_id)
            if (dev)
                dev.sendEvent(name: "rain_delay", value: payload.delay)
            break
        case "low_battery":
            parent.triggerLowBattery(device)
            break
        case "flow_sensor_state_changed":
            def dev = parent.getDeviceById(payload.device_id)
            if (dev)
                dev.sendEvent(name: "water_flow_rate", value: payload.flow_rate_gpm)
            break
        case "program_changed":
            // TODO figure this out
            break
        case "device_idle":
        case "clear_low_battery":
            // Do nothing
            break
        default:
            log.warn "Unknown message: ${message}"
    }
}

def webSocketStatus(String message) {
    if (message == "status: open") {
        synchronized (socketStatusLock) {
            log.debug "Reconnect successful"
            setWebSocketStatus(true)
            state.webSocketOpenTime = now()
            def loginMsg = [
                event: "app_connection",
                orbit_app_id: UUID.randomUUID().toString(),
                orbit_session_token: parent.getApiToken()
            ]
            
            interfaces.webSocket.sendMessage(new JsonOutput().toJson(loginMsg))
        }
        pauseExecution(1000)
        if (state.retryCommand != null) {
            log.debug "Retrying command from before connection lost ${state.retryCommand}"
            interfaces.webSocket.sendMessage(state.retryCommand)
            state.retryCommand = null
        }
        schedule("0/30 * * * * ? *", pingWebSocket)
    }
    else if (message == "status: closing") {
        synchronized (socketStatusLock) {
            log.error "Lost connection to Web Socket: ${message}"
            setWebSocketStatus(false)
        }
    }
    else if (message.startsWith("failure:")) {
        synchronized (socketStatusLock) {
            log.error "Lost connection to Web Socket: ${message}"
            setWebSocketStatus(false)
        }
    }
    else {
        synchronized (socketStatusLock) {
            log.error "web socket status: ${message}"
            setWebSocketStatus(false)\
        }
    }
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
    log.debug "Sending: ${msg}"
    safeWSSend(msg, true)
}

def pingWebSocket() {
    if (now()-(30*60*1000) >= state.webSocketOpenTime) {
        parent.debugVerbose "WebSocket has been open for 30 minutes, reconnecting"
        initialize()
        return
    }
    def pingMsg = [ event: "ping"]
    safeWSSend(pingMsg, false)
}

def getTimestamp() {
    def date = new Date()
    return date.format(timeStampFormat, TimeZone.getTimeZone('UTC'))
}

def setWebSocketStatus(status) {
    log.debug "Old statuses: ${state.webSocketOpen} ${getDataValue("webSocketOpen")} ${webSocketOpen}"
    state.webSocketOpen = status
    updateDataValue("webSocketOpen", status.toString())
    webSocketOpen = status
    log.debug "New statuses: ${state.webSocketOpen} ${getDataValue("webSocketOpen")} ${webSocketOpen}"
}

def isWebSocketOpen() {
    return state.webSocketOpen &&  webSocketOpen && getDataValue("webSocketOpen") == "true"
}