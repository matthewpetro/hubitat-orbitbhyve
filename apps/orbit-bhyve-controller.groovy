/*
* Orbit™ B•Hyve™ Controller
* 2019 (c) SanderSoft™
*
* Author:   Kurt Sanders
* Email:	Kurt@KurtSanders.com
* Date:	    3/2017
* Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
* in compliance with the License. You may obtain a copy of the License at:
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
* on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
* for the specific language governing permissions and limitations under the License.
*
*/
import groovy.time.*
import java.text.SimpleDateFormat
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

definition(
    name: 		"Orbit Bhyve Controller",
    namespace: 	"kurtsanders",
    author: 	"Kurt@KurtSanders.com",
    description:"Control and monitor your network connected Orbit™ Bhyve Timer anywhere via SmartThings®",
    category: 	"My Apps",
    iconUrl: 	"",
    iconX2Url: 	"",
    iconX3Url: 	"",
    singleInstance: false
)
preferences {
    page(name:"mainMenu")
    page(name:"mainOptions")
    page(name:"notificationOptions")
    page(name:"APIPage")
    page(name:"enableAPIPage")
    page(name:"disableAPIPage")
}

def mainMenu() {
    def orbitBhyveLoginOK = false
    if (username && password) {
        orbitBhyveLoginOK = OrbitBhyveLogin()
        def respdata = orbitBhyveLoginOK ? OrbitGet("devices") : null
    }
    dynamicPage(name: "mainMenu",
                title: "Orbit B•Hyve™ Timer Account Login Information",
                nextPage: orbitBhyveLoginOK ? "mainOptions" : null,
                install: false,
                uninstall: true)
    {
        if (username && password) {
            if (state?.orbit_api_key) {
                section("Orbit B•Hyve™ Information") {
                    paragraph "Your Login Information is Valid"
                    paragraph "Account name: ${state.user_name}"
                }
            } else {
                section("Orbit B•Hyve™ Status/Information") {
                    paragraph "Your Login Information is INVALID ${state.statusText}"
                }
            }
        }
        section () {
            input ( name    : "username",
                   type     : "text",
                   title    : "Account userid?",
                   submitOnChange: true,
                 required : true
                  )
            input ( name    : "password",
                   type     : "password",
                   title    : "Account password?",
                   submitOnChange: true,
                   required : true
                  )
        }
    }
}

def mainOptions() {
    dynamicPage(name: "mainOptions",
                title: "Bhyve Timer Controller Options",
                install: true,
                uninstall: false)
    {
        section("Orbit Timer Refresh/Polling Update Interval") {
            input ( name: "schedulerFreq",
                   type: "enum",
                   title: "Run Bhyve Refresh Every (X mins)?",
                   options: ['0':'Off','1':'1 min','2':'2 mins','3':'3 mins','4':'4 mins','5':'5 mins','10':'10 mins','15':'15 mins','30':'Every ½ Hour','60':'Every Hour','180':'Every 3 Hours'],
                   required: true
                  )
        }
        section("Push Notification Options") {
            href(name: "Push Notification Options",
                 title: "Push Notification Options",
                 page: "notificationOptions",
                 description: "Notification Options",
                 defaultValue: "Tap to Select Notification Options")
        }

        section() {
            label ( name: "name",
                   title: "App Name",
                   state: (name ? "complete" : null),
                   defaultValue: "${app.name}",
                   required: false
                  )
        }
        section(hideable: true, hidden: true, "Logging Settings") {
            input ( name: "logDebugMsgs", type: "bool",
                   title: "Show Debug Messages in IDE",
                   description: "Verbose Mode",
                   required: false
                  )
            input ( name: "logInfoMsgs", type: "bool",
                   title: "Show Info Messages in IDE",
                   description: "Verbose Mode",
                   required: false
                  )
        }
    }
}

def notificationOptions() {
    dynamicPage(name: "notificationOptions",
                title: "Bhyve Timer Controller Notification Options",
                install: false,
                uninstall: false)
    {
        section("Enable Notifications:") {
            input ("notificationsEnabled", "bool", title: "Use Notifications", required: false, submitOnChange: true)
            if (notificationsEnabled)
                input "notificationDevices", "capability.notification", description: "Device(s) to notify", multiple: true, required: notificationsEnabled
        }

        section("Event Notification Filtering") {
            input ( name	: "eventsToNotify",
                   type		: "enum",
                   title	: "Which Events",
                   options: ['battery':'Battery Low','connected':'Online/Offline','rain':'Rain Delay','valve':'Valve Open/Close'],
                   description: "Select Events to Notify",
                   required: false,
                   multiple: true
                  )

        }
    }
}

def initialize() {
    add_bhyve_ChildDevice()
    setScheduler(schedulerFreq)
    if (eventsToNotify) {
    if (eventsToNotify.contains('valve')) 		{subscribe(app.getChildDevices(), "valve", valveHandler)}
    if (eventsToNotify.contains('battery')) 	{subscribe(app.getChildDevices(), "battery", batteryHandler)}
    if (eventsToNotify.contains('rain')) 		{subscribe(app.getChildDevices(), "rain_delay", rain_delayHandler)}
    if (eventsToNotify.contains('connected')) 	{subscribe(app.getChildDevices(), "is_connected", is_connectedHandler)}
    }
    runIn(5, main)
}

def updated() {
    unsubscribe()
    initialize()
}

def installed() {
    state?.isInstalled = true
    initialize()
}

def uninstalled() {
    remove_bhyve_ChildDevice()
}


def webEvent() {
    Random random = new Random()
    def data = request.JSON
    if (data.containsKey("event")) {
        debugVerbose "=> webEvent #${random.nextInt(1000)}: '${data.event}'-> data : ${data}"
        switch(data.event) {
            case 'updatealldevices':
                runIn(5, "updateTiles", [data: data.webdata])
                break
            case 'change_mode':
                if (!data.containsKey("mode")) return
                def station = data.containsKey('stations')?data.stations.station:1
                def d = getChildDevice(DTHDNI("${data.device_id}:${station}"))
                if (d) {
                    d.sendEvent(name:"run_mode", value: data.mode, displayed: false)
                }
                break
            case 'low_battery':
                if (eventsToNotify.contains('battery')) {
                    send_message("Check your Orbit watering device for a low battery condition")
                }
                break
            case 'flow_sensor_state_changed':
                def station = data.containsKey('stations')?data.stations.station:1
                def d = getChildDevice(DTHDNI("${data.device_id}:${station}"))
                if (d) {
                    def cycle_volume_gal = data.cycle_volume_gal?:null
                    if (cycle_volume_gal) {
                        d.sendEvent(name:"water_volume_gal", value: cycle_volume_gal, descriptionText:"${cycle_volume_gal} gallons")
                    }
                } else 
                    log.error "Error in obtaining the DTHDNI from 'flow_sensor_state_changed' webevent"
                break
            case 'program_changed':
                runIn(15, "refresh")
                break
            case 'watering_events':
            case 'watering_in_progress_notification':
            case 'watering_complete':
                runIn(5, "refresh")
                break
            case 'status':
                return allDeviceStatus()
                break
            default:
                debugVerbose "Skipping WebEvent ${data.event}"
                break
        }
    }
}

def allDeviceStatus() {
    def results = [:]
    app.getChildDevices().each{
        def d = getChildDevice(it.deviceNetworkId)
        def type = d.latestValue('type')
        if (!results.containsKey(type)) {
            results[type] = []
        }
        results[type].add(
            [
                name 						: it.name,
                type 						: it.typeName,
                valve						: d.latestValue('valve'),
                id							: d.latestValue('id'),
                manual_preset_runtime_min	: d.latestValue('manual_preset_runtime_min')
            ]
        )
        }
    return JsonOutput.toJson(results)
}

def findMasterHub() {
    return getChildDevices().find { 
        it.latestValue("type") == "bridge"
    }
}

def sendRequest(valveState, device_id, zone, run_time) {
    def bhyveHub = findMasterHub()
    log.debug bhyveHub
    bhyveHub.sendWSMessage(valveState, device_id, zone, run_time)
    runIn(10, "main")
}

def valveHandler(evt) {
    if (evt.isStateChange())
        send_message("The ${evt.linkText} ${evt.name} is now ${evt.value.toUpperCase()} at ${timestamp()}")
}
def rain_delayHandler(evt) {
    if (evt.isStateChange())
        send_message("The ${evt.linkText}'s rain delay is now ${evt.value} hours at ${timestamp()}")
}
def batteryHandler(evt) {
    if (evt.isStateChange() && (evt.value.toInteger() <= 40) ) 
        send_message("The ${evt.linkText}'s battery is now at ${evt.value}% at ${timestamp()}")
}
def is_connectedHandler(evt) {
    if (evt.isStateChange())
        send_message("The ${evt.linkText}'s WiFi Online Status is now ${evt.value?'Online':'Offline'} at ${timestamp()}")
}

def refresh() {
    debugVerbose("Executing Refresh Routine ID:${random()} at ${timestamp()}")
    main()
}

def main() {
    infoVerbose "Executing Main Routine ID:${random()} at ${timestamp()}"
    def data = OrbitGet("devices")
    if (data) {
        updateTiles(data)
    } else 
        log.error "OrbitGet(devices): No data returned, Critical Error: Exit"
}

def updateTiles(data) {
    Random random = new Random()
    debugVerbose("Executing updateTiles(data) #${random.nextInt(1000)} started...")
    def d
    def started_watering_station_at
    def watering_events
    def watering_volume_gal
    def wateringTimeLeft
    def zoneData
    def zone
    def zones
    def station
    def next_start_programs
    def i
    def stp
    def scheduled_auto_on
    data.each {
        def deviceType = it.type
        switch (deviceType) {
            case 'bridge':
                infoVerbose "Procesing Orbit Bridge: '${it.name}'"
                zone = 0
                zones = 1
                break
            case 'sprinkler_timer':
                zones = it.zones.size()
                stp = OrbitGet("sprinkler_timer_programs", it.id)
                break
            default:
                log.error "Invalid Orbit Device: '${it.type}' received from Orbit API...Skipping: ${it}"
                d = null
                break
        }
        for (i = 0 ; i < zones; i++) {
            station = it.containsKey('zones')?it.zones[i].station:zone
            d = getChildDevice("${DTHDNI(it.id)}:${station}")
            if (d) {
                // sendEvents calculated values for all device types
                d.sendEvent(name:"schedulerFreq", 	value: schedulerFreq, 						displayed: false)
                // sendEvents for selected fields of the data record

                d.sendEvent(name:"lastupdate", 		value: "Station ${station} last connected at\n${convertDateTime(it.last_connected_at)}", displayed: false)
                d.sendEvent(name:"name", 			value: it.name, 							displayed: false)
                d.sendEvent(name:"type", 			value: it.type, 							displayed: false)
                d.sendEvent(name:"id",	 			value: it.id, 								displayed: false)
                d.sendEvent(name:"is_connected", 	value: it.is_connected, 					displayed: false)
                d.sendEvent(name:"icon",		 	value: it.num_stations, 					displayed: false)

                // Check for Orbit WiFi bridge
                if (deviceType == 'bridge') {
                    d.sendEvent(name:"firmware_version", value: it?.firmware_version, displayed: false)
                    d.sendEvent(name:"hardware_version", value: it?.hardware_version, displayed: false)
                    return
                }

                // Check for Orbit sprinkler_timer device
                if (deviceType == 'sprinkler_timer') {
                    zoneData 	= it.zones[i]
                    station 	= zoneData.station
                    scheduled_auto_on 	= true
                    d = getChildDevice("${DTHDNI(it.id)}:${station}")
                    infoVerbose "Procesing Orbit Sprinkler Device: '${it.name}', Orbit Station #${station}, Zone Name: '${zoneData.name}'"

                    def byhveValveState = it.status.watering_status?"open":"closed"
                    d.sendEvent(name:"valve", 						value: byhveValveState )
					def presetWateringInt = (it.manual_preset_runtime_sec.toInteger()/60)
                    d.sendEvent(name:"presetRuntime", 				value: presetWateringInt, displayed: false )
                    d.sendEvent(name:"manual_preset_runtime_min", 	value: presetWateringInt, displayed: false )
                    d.sendEvent(name:"rain_delay", 					value: it.status.rain_delay )
                    d.sendEvent(name:"run_mode", 					value: it.status.run_mode, displayed: false)
                    d.sendEvent(name:"station", 					value: station, displayed: false)

                    next_start_programs = it.status.next_start_programs?it.status.next_start_programs.join(', ').toUpperCase():''
                    d.sendEvent(name:"next_start_programs", value: "Station ${station}: ${next_start_programs}", displayed: false)

                    d.sendEvent(name:"sprinkler_type", 		value: "${zoneData.num_sprinklers>0?:'Unknown'} ${zoneData.sprinkler_type?zoneData.sprinkler_type+'(s)':''} ", displayed: false)

                    if (it.containsKey('battery') && it.battery != null) {
                        d.sendEvent(name:"battery", 			value: it.battery.percent, displayed:false )
                        d.sendEvent(name:"battery_display", 	value: (Math.round(it.battery.percent.toInteger()/10.0) * 10).toString(), displayed:false )
                    } else {
                        d.sendEvent(name:"battery", 			value: 100   , displayed:false )
                        d.sendEvent(name:"battery_display", 	value: "100" , displayed:false )
                    }

                    // Check for System On/Off Mode for this device
                    if (it.scheduled_modes.containsKey('auto') && it.scheduled_modes.containsKey('off')) {
                        def dateFormat = (it.scheduled_modes.auto.annually==true)?"MMdd":"YYYYMMdd"
                        def todayDate 		= new Date().format(dateFormat, location.timeZone)
                        def begAutoAtDate 	= it.scheduled_modes.auto.at?Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",it.scheduled_modes.auto.at).format(dateFormat):''
                        def begOffAtDate 	= it.scheduled_modes.off.at?Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",it.scheduled_modes.off.at).format(dateFormat):''
                        if (!(begAutoAtDate<=todayDate && begOffAtDate>=todayDate)) {
                            scheduled_auto_on = false
                            d.sendEvent(name:"rain_icon", 		value: "sun", displayed: false )
                            d.sendEvent(name:"next_start_time", value: "System Auto Off Mode", displayed: false)
                        }
                    }
                    d.sendEvent(name:"scheduled_auto_on", value: scheduled_auto_on, displayed: false )
                    if (scheduled_auto_on) {
                        if (it.status.rain_delay > 0) {
                            d.sendEvent(name:"rain_icon", 			value: "rain", displayed: false )
                            def rainDelayDT = Date.parse("yyyy-MM-dd'T'HH:mm:ssX",it.status.next_start_time).format("yyyy-MM-dd'T'HH:mm:ssX", location.timeZone)
                            d.sendEvent(name:"next_start_time", value: durationFromNow(rainDelayDT), displayed: false)
                        } else {
                            d.sendEvent(name:"rain_icon", 		value: "sun", displayed: false )
                            def next_start_time_local = Date.parse("yyyy-MM-dd'T'HH:mm:ssX",it.status.next_start_time).format("yyyy-MM-dd'T'HH:mm:ssX", location.timeZone)
                            d.sendEvent(name:"next_start_time", value: durationFromNow(next_start_time_local), displayed: false)
                        }
                    }

                    // Sprinkler Timer Programs
                    if (stp) {
                        def msgList = []
                        def start_timesList = []
                        def freqMsg
                        stp.findAll{it.enabled.toBoolean()}.each {
                            def y = it.run_times.findAll{it.station == station}
                            start_timesList = []
                            if (y) {
                                it.start_times.each {
                                    start_timesList << Date.parse("HH:mm",it).format("h:mm a").replace("AM", "am").replace("PM","pm")
                                }
                                switch (it.frequency.type) {
                                    case 'interval':
                                    freqMsg = "every ${it.frequency.interval} day(s)"
                                    break
                                    case 'odd':
                                    case 'even':
                                    freqMsg = "every ${it.frequency.type} day"
                                    break
                                    case 'days':
                                    def dow = []
                                    def map=[
                                        0:"Sunday",
                                        1:"Monday",
                                        2:"Tuesday",
                                        3:"Wednesday",
                                        4:"Thusday",
                                        5:"Friday",
                                        6:"Saturday"]
                                    it.frequency.days.each{
                                        dow << map[it]
                                    }
                                    freqMsg = "every ${dow.join(' & ')}"
                                    break
                                    default:
                                        freqMsg = "'${it.frequency.type}' freq type unknown"
                                    break
                                }
                                msgList << "${it.name} (${it.program.toUpperCase()}): ${y.run_time[0]} mins ${freqMsg} at ${start_timesList.join(' & ')}"
                                d.sendEvent(name:"start_times", value: "${start_timesList.join(' & ')}", displayed: false)
                            }
                        }
                        if (msgList.size()>0) {
                            d.sendEvent(name:"programs", value: "${zoneData.name} Programs\n${msgList.join(',\n')}", displayed: false)
                        } else {
                            d.sendEvent(name:"programs", value: "${zoneData.name} Programs\n${it.name}: None", displayed: false)
                        }
                    }
                    // Watering Events
                    watering_events = OrbitGet('watering_events', it.id)[0]
                    if (watering_events) {
                        watering_events.irrigation = watering_events.irrigation[-1]?:0

                        if (byhveValveState == 'open') {
                            def water_volume_gal = watering_events.irrigation.water_volume_gal?:0
                            started_watering_station_at = convertDateTime(it.status.watering_status.started_watering_station_at)
                            d.sendEvent(name:"water_volume_gal", value: water_volume_gal, descriptionText:"${water_volume_gal} gallons")
                            wateringTimeLeft = durationFromNow(it.status.next_start_time, "minutes")
                            wateringTimeLeft = durationFromNow(it.status.next_start_time, "minutes")
                            d.sendEvent(name:"level", value: wateringTimeLeft, descriptionText: "${wateringTimeLeft} minutes left till end" )
                        } else {
                            d.sendEvent(name:"water_volume_gal"	, value: 0, descriptionText:"gallons", displayed: false )
                            d.sendEvent(name:"level"			, value: watering_events?.irrigation?.run_time, displayed: false)
                        }
                    } else {
                        d.sendEvent(name:"water_volume_gal"	, value: 0, descriptionText:"gallons", displayed: false )
                        d.sendEvent(name:"level"			, value: 0, displayed: false)
                    }
                }
            } else
                log.error "Invalid Orbit Device ID: '${it.id}'. If you have added a NEW bhyve device, you must rerun the SmartApp setup to create a SmartThings device"
        }
    }
}

def durationFromNow(dt,showOnly=null) {
    def endDate
    String rc
    def duration
    def dtpattern = dt.contains('Z')?"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'":"yyyy-MM-dd'T'HH:mm:ssX"
    if (dtpattern) {
        try {
            endDate = Date.parse(dtpattern, dt)
        } catch (e) {
            log.error "durationFromNow(): Error converting ${dt}: ${e}"
            return false
        }
    } else {
        log.error "durationFromNow(): Invalid date format for ${dt}"
        return false
    }
    def now = new Date()
    use (TimeCategory) {
        try {
            duration = (endDate - now)
        } catch (e) {
            log.error "TimeCategory Duration Error with enddate: '${endDate}' and  now(): '${now}': ${e}"
            rc = false
        }
    }
    if (duration) {
        rc = duration
        switch (showOnly) {
            case 'minutes':
            if (/\d+(?=\Wminutes)/) {
            def result = (rc =~ /\d+(?=\Wminutes)/)
                return (result[0])
            } else {
            return (rc.replaceAll(/\.\d+/,''))
            }
            break
            default:
                return (rc.replaceAll(/\.\d+/,'').split(',').length<3)?rc.replaceAll(/\.\d+/,''):(rc.replaceAll(/\.\d+/,'').split(',')[0,1].join())

        }
    }
    return rc
}

def timestamp(type='long', mobileTZ=false) {
    def formatstring
    Date datenow = new Date()
    switch(type){
        case 'short':
        formatstring = 'h:mm:ss a'
        break
        default:
            formatstring = 'EEE MMM d, h:mm:ss a'
        break
    }
    def tf = new java.text.SimpleDateFormat(formatstring)

    if (mobileTZ) {
        return datenow.format(formatstring, location.timeZone).replace("AM", "am").replace("PM","pm")
	} else {
        tf.setTimeZone(TimeZone.getTimeZone(state.timezone))
    }
        return tf.format(datenow).replace("AM", "am").replace("PM","pm")
}

def OrbitGet(command, device_id=null, mesh_id=null) {
    def respdata
    def params = [
        'uri'		: orbitBhyveLoginAPI(),
        'headers'	: OrbitBhyveLoginHeaders(),
    ]
    params.headers << ["orbit-api-key"	: state.orbit_api_key]
    switch (command) {
        case 'users':
            params.path = "${command}/${state.user_id}"
            break
        case 'user_feedback':
        case 'devices':
            params.path 	= "${command}"
            params.query 	= ["user_id": state.user_id]
            break
        case 'sprinkler_timer_programs':
            params.path 	= "${command}"
            params.query	= ["device_id" : device_id]
            break
        case 'zone_reports':
        case 'watering_events':
        case 'landscape_descriptions':
        case 'event_logs':
            params.path = "${command}/${device_id}"
            break
        case 'meshes':
            params.path = "${command}/${mesh_id}"
            break
        default :
            log.error "Invalid command '${command}' to execute:"
            return false
    }
    try {
        httpGet(params) { resp ->
            if(resp.status == 200) {
                respdata = resp.data
            } else {
                log.error "Fatal Error Status '${resp.status}' from Orbit B•Hyve™ ${command}.  Data='${resp?.data}'"
                return null
            }
        }
    } catch (e) {
        log.error "OrbitGet($command): something went wrong: $e"
        return null
    }
    if (command=='devices') {
        def bridgeCount = respdata.type.count {it==typelist()[1]}
        def bridgeMsg = (bridgeCount==0)?'in your Orbit b•hyve™ account':"and ${bridgeCount} network bridge device(s)"
        state.devices = "Found ${respdata.type.count { it==typelist()[0]}} sprinkler timer(s) ${bridgeMsg}."
        state.timezone = respdata[0].timezone.timezone_id
    }
    return respdata
}

def OrbitBhyveLogin() {
    debugVerbose "Start OrbitBhyveLogin() ============="
    if ((username==null) || (password==null)) { return false }
    def params = [
        'uri'			: orbitBhyveLoginAPI(),
        'headers'		: OrbitBhyveLoginHeaders(),
        'path'			: "session",
        'body'			: web_postdata()
    ]
    try {
        httpPost(params) {
            resp ->
            if(resp.status == 200) {
                debugVerbose "HttpPost Login Request was OK ${resp.status}"
                state.orbit_api_key = "${resp.data?.orbit_api_key}"
                state.user_id 		= "${resp.data?.user_id}"
                state.user_name 	= "${resp.data?.user_name}"
                state.statusText	= "Success"
            }
            else {
                log.error "Fatal Error Status '${resp.status}' from Orbit B•Hyve™ Login.  Data='${resp.data}'"
                state.orbit_api_key = null
                state.user_id 		= null
                state.user_name 	= null
                state.statusText = "Fatal Error Status '${resp.status}' from Orbit B•Hyve™ Login.  Data='${resp.data}'"
                return false
            }
        }
    }
    catch (Exception e)
    {
        log.error "Catch HttpPost Login Error: ${e}"
        state.orbit_api_key = null
        state.user_id 		= null
        state.user_name 	= null
        state.statusText = "Fatal Error for Orbit B•Hyve™ Login '${e}'"
        return false
    }

    debugVerbose "OrbitBhyveLogin(): End=========="
    return true
}

def setScheduler(schedulerFreq) {
    state.schedulerFreq = "${schedulerFreq}"

    switch(schedulerFreq) {
        case '0':
        unschedule()
        break
        case '1':
        runEvery1Minute(refresh)
        break
        case '2':
        schedule("${random(60)} 3/${schedulerFreq} * * * ?","refresh")
        break
        case '3':
        schedule("${random(60)} 3/${schedulerFreq} * * * ?","refresh")
        break
        case '4':
        schedule("${random(60)} 3/${schedulerFreq} * * * ?","refresh")
        break
        case '5':
        runEvery5Minutes(refresh)
        break
        case '10':
        runEvery10Minutes(refresh)
        break
        case '15':
        runEvery15Minutes(refresh)
        break
        case '30':
        runEvery30Minutes(refresh)
        break
        case '60':
        runEvery1Hour(refresh)
        break
        case '180':
        runEvery3Hours(refresh)
        break
        default :
        infoVerbose("Unknown Schedule Frequency")
        unschedule()
        return
    }
    if(schedulerFreq=='0'){
        infoVerbose("UNScheduled all RunEvery")
    } else {
        infoVerbose("Scheduled RunEvery${schedulerFreq}Minute")
    }
}

def random(int value=10000) {
    def runID = new Random().nextInt(value)
    return runID
}

def add_bhyve_ChildDevice() {
    def data = [:]
    def i
    def respdata = OrbitGet("devices")
    if (respdata) {
        respdata.eachWithIndex { it, index ->
            switch (it.type) {
                case 'sprinkler_timer':
                def numZones = it.zones.size()
                infoVerbose "Orbit device (${index}): ${it.name} is a ${it.type}-${it.hardware_version} with ${it.num_stations} stations, ${numZones} zone(s) and last connected at: ${convertDateTime(it.last_connected_at)}"
                for (i = 0 ; i < it.zones.size(); i++) {
                    data = [
                        DTHid 	: "${DTHDNI(it.id)}:${it.zones[i].station}",
                        DTHname : DTHName(it.type.split(" |-|_").collect{it.capitalize()}.join(" ")),
                        DTHlabel: "Bhyve ${it.zones[i].name?:it.name}"
                    ]
                    createDevice(data)
                }
                break
                case 'bridge':
                data = [
                    DTHid	: 	"${DTHDNI(it.id)}:0",
                    DTHname	:	DTHName(it.type.split(" |-|_").collect{it.capitalize()}.join(" ")),
                    DTHlabel: 	"Bhyve ${it.name}"
                ]
                createDevice(data)
                break
                default:
                    log.error "Skipping: Unknown Orbit b•hyve deviceType '${it?.type}' for '${it?.name}'"
                    data = [:]
                break
            }
        }
    } else {
        return false
    }
    return true
}

def createDevice(data) {
    def d = getChildDevice(data.DTHid)
    if (d) {
        infoVerbose "VERIFIED DTH: '${d.name}' is DNI:'${d.deviceNetworkId}'"
        return true
    } else {
        infoVerbose "MISSING DTH: Creating a NEW Orbit device for '${data.DTHname}' device as '${data.DTHlabel}' with DNI: ${data.DTHid}"
        try {
            addChildDevice(DTHnamespace(), data.DTHname, data.DTHid, null, ["name": "${data.DTHlabel}", label: "${data.DTHlabel}", completedSetup: true])
        } catch(e) {
            log.error "The Device Handler '${data.DTHname}' was not found in your 'My Device Handlers', Error-> '${e}'.  Please install this DTH device in the IDE's 'My Device Handlers'"
            return false
        }
        infoVerbose "Success: Added a new device named '${data.DTHlabel}' as DTH:'${data.DTHname}' with DNI:'${data.DTHid}'"
    }
    return true
}

def remove_bhyve_ChildDevice() {
    getAllChildDevices().each {
        debugVerbose "Deleting b•hyve™ device: ${it.deviceNetworkId}"
        try {
            deleteChildDevice(it.deviceNetworkId)
        }
        catch (e) {
            debugVerbose "${e} deleting the b•hyve™ device: ${it.deviceNetworkId}"
        }
    }
}

def convertDateTime(dt) {
    def timezone = TimeZone.getTimeZone(state.timezone)
    def rc
    switch (dt) {
        case ~/.*UTC.*/:
        rc = dt
        break
        case ~/.*Z.*/:
        rc = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", dt)
        break
        default:
            rc = Date.parse("yyyy-MM-dd'T'HH:mm:ssX", dt)
        break
    }
    return rc.format('EEE MMM d, h:mm a', timezone).replace("AM", "am").replace("PM","pm")
}

// Constant Declarations
def debugVerbose(String message) {if (logDebugMsgs){log.debug "${message}"}}
def infoVerbose(String message)  {if (logInfoMsgs){log.info "${message}"}}
String DTHDNI(id) 					{(id.startsWith('bhyve'))?id:"bhyve-${app.id}-${id}"}
String DTHnamespace()			{ return "kurtsanders" }
String DTHName(type) 			{ return "Orbit Bhyve ${type}" }
String orbitBhyveLoginAPI() 	{ return "https://api.orbitbhyve.com/v1/" }
String web_postdata() 			{ return "{\n    \"session\": {\n        \"email\": \"$username\",\n        \"password\": \"$password\"\n    }\n}" }
Map OrbitBhyveLoginHeaders() 	{
    return [
        'orbit-app-id':'Orbit Support Dashboard',
        'Content-Type':'application/json'
    ]
}
List typelist() { return ["sprinkler_timer","bridge"] }

// ======= Push Routines ============

def send_message(String msgData) {
    if (notificationsEnabled)
        notificationDevices*.deviceNotification(msgData)
}

def send_message(ArrayList msgData) {
    if (notificationsEnabled)
        notificationDevices*.deviceNotification(msgData[1])
}

// ======= WebSocket Helper Methods =======
def getApiToken() {
    return state.orbit_api_key
}

def getDeviceById(deviceId) {
    return getChildDevices().find { it.currentValue("id") == deviceId}
}