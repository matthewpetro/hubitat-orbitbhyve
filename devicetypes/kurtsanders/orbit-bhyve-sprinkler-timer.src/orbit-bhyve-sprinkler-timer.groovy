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

metadata {
    definition (name: "Orbit Bhyve Sprinkler Timer", namespace: "kurtsanders", author: "kurt@kurtsanders.com") {
        capability "Refresh"
        capability "Sensor"
        capability "Battery"
        capability "Valve"

        attribute "battery_display", "string"
        attribute "icon", "string"
        attribute "id", "string"
        attribute "is_connected", "enum", ['true','false']
        attribute "lastupdate", "string"
        attribute "manual_preset_runtime_min", "number"
        attribute "name","string"
        attribute "next_start_programs", "string"
        attribute "next_start_time", "string"
        attribute "presetRuntime", "number"
        attribute "programs", "string"
        attribute "rain_icon", "string"
        attribute "rain_delay", "string"
        attribute "runmode", "enum", ["auto, manual"]
        attribute "schedulerFreq", "string"
        attribute "sprinkler_type", "string"
        attribute "start_times", "string"
        attribute "station", "string"
        attribute "statusText", "string"
        attribute "scheduled_auto_on", "enum", ['true','false']
        attribute "type", "string"
        attribute "water_volume_gal", "number"

        command "setLevel"
    }
}

def refresh() {
    parent.refresh()
}

def installed() {
    sendEvent(name: "valve", value: "closed")
}

def open() {
    if (device.latestValue('scheduled_auto_on')=='true') {
        parent.sendRequest('open', device.latestValue('id'), device.latestValue('station'),device.latestValue('presetRuntime') )
    } else {
        def message =  "Orbit device requested to manually OPEN but scheduled_auto_on = false, ignorning request"
        log.warn message
        sendEvent(name: "valve", value: "closed", isStateChange: true, linkText: message)
    }
}

def close() {
    parent.sendRequest('close', device.latestValue('id'), device.latestValue('station'),device.latestValue('presetRuntime') )
}

def setLevel(level, rate = null) {
    def presetRuntimeOrbit = device.latestValue("presetRuntime")
    log.error "Orbit B•Hyve™ Sprinkler Timer Device Preset RunTime was ${device.latestValue("manual_preset_runtime_min")} and was asked change to a new preset RunTime of ${level}.  Orbit reports it is ${presetRuntimeOrbit}... reseting to Orbit value."
    sendEvent(name: "manual_preset_runtime_min", value: presetRuntimeOrbit, display: false)
    runIn(6,clearBanner)
}
