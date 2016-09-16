/**
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Aeotec Z-Wave Door/Window Sensor Gen5 (ZW120-A)
 *
 *  Author: Jason Niesz
 *  Date: 2016-09-15
 */

metadata {
    definition (name: "Aeotec Z-Wave Door/Window Sensor Gen5", namespace: "mainx77", author: "Jason Niesz") {
        capability "Contact Sensor"
        capability "Sensor"
        capability "Battery"
        capability "Configuration"

        fingerprint mfr: "0086", prod: "0102", model: "0078"
    }

    simulator {
        status "open":  "command: 2001, payload: FF"
        status "closed": "command: 2001, payload: 00"
        status "wake up": "command: 8407, payload: "
    }

    preferences {
        //generate configuration inputs based on config map
        def configMap = configurationMap
        configMap.each { config ->
            if(config.value.type == "number") {
                input name: config.key, title: "${config.value.friendlyName}", description: config.value.description, type: config.value.type, range: config.value.range, required: false
            } else if(config.value.type == "enum") {
                def enumOpts = []
                config.value.enum.keySet().each { enumOpts << "${it}" }
                if(! enumOpts.empty) input name: config.key, title: "${config.value.friendlyName}", description: config.value.description, type: config.value.type, options: enumOpts, required: false
            }
        }
    }

    // UI tile definitions
    tiles {
        standardTile("contact", "device.contact", width: 2, height: 2) {
            state "open", label: '${name}', icon: "st.contact.contact.open", backgroundColor: "#ffa81e"
            state "closed", label: '${name}', icon: "st.contact.contact.closed", backgroundColor: "#79b821"
        }
        
        standardTile("tamper", "device.tamper") {
            state("clear", label:'secure',  icon:"st.security.alarm.clear", backgroundColor:"#79b821")
            state("detected", label:'tampered', icon:"st.security.alarm.alarm", backgroundColor:"#e3eb00")
        }
        
        valueTile("battery", "device.battery", decoration: "flat") {
            state "battery", label:'${currentValue}% battery', unit:""
        }

        main "contact"
        details([ "contact", "tamper", "battery" ])
    }
}

def parse(String description) {
    log.debug "description: $description"
    def result = null
    def cmd = zwave.parse(description,[ 0x98: 1, 0x20: 1, 0x71: 3, 0x84: 2, 0x30: 2, 0x80: 1 ])
    
    if (cmd) {
        result = zwaveEvent(cmd)
        log.debug "Event: ${result.inspect()}"
    } else {
        log.debug "Non-parsed event: ${description}"
    }
    return result
}

def zwaveEvent(physicalgraph.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
    def encapsulatedCommand = cmd.encapsulatedCommand([ 0x98: 1, 0x20: 1, 0x71: 3, 0x84: 2, 0x30: 2, 0x80: 1 ])
    log.debug "SecurityMessageEncapsulation: ${encapsulatedCommand}"
    
    if (encapsulatedCommand) {
        state.sec = true
        return zwaveEvent(encapsulatedCommand)
    }
}

def zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport cmd) {
    log.debug "BatteryReport: ${cmd}"
    def map = [ name: "battery", unit: "%" ]
    if (cmd.batteryLevel == 0xFF) {
        map.value = 1
        map.descriptionText = "${device.displayName} has a low battery"
        map.isStateChange = true
    } else {
        map.value = cmd.batteryLevel
    }
    state.lastbatt = now()
    [ createEvent(map), response(formatCommand(zwave.wakeUpV2.wakeUpNoMoreInformation())) ]
}

def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicSet cmd) {
    log.debug "BasicSet: ${cmd}"
    def result
    if (cmd?.value) {
        result = createEvent(name: "contact", value: "open", descriptionText: "$device.displayName is open")
    } else {
        result = createEvent(name: "contact", value: "closed", descriptionText: "$device.displayName is closed")
    }
    return result
}

def zwaveEvent(physicalgraph.zwave.commands.sensorbinaryv2.SensorBinaryReport  cmd) {
    log.debug "SensorBinaryReport: ${cmd}"
    def result
    
    if (cmd?.sensorValue) {
        result = createEvent(name: "contact", value: "open", descriptionText: "$device.displayName is open")
    } else {
        result = createEvent(name: "contact", value: "closed", descriptionText: "$device.displayName is closed")
    }
    return result
}

def zwaveEvent(physicalgraph.zwave.commands.notificationv3.NotificationReport cmd) {
    log.debug "NotificationReport: ${cmd}"
    def result
    if (cmd?.notificationType == 7 && cmd?.event == 3) {
        result = createEvent(name: "tamper", value: "detected", descriptionText: "$device.displayName tamper alarm")
    } else if (cmd?.notificationType == 7 && cmd?.event == 0) {
        result = createEvent(name: "tamper", value: "clear", descriptionText: "$device.displayName tamper clear")    
    } else {
        result = createEvent(name: "notification${cmd.notificationType}", value: "${cmd.event}", descriptionText: "${device.displayName} notification", isStateChange: false)
    }
    return result
}

def zwaveEvent(physicalgraph.zwave.commands.wakeupv2.WakeUpNotification cmd) {
    log.debug "WakeUpNotification: ${cmd}"
    log.debug "settings: ${settings.inspect()}, state: ${state.inspect()}"

    //everytime we wake up request battery report and sensor report
    def event = createEvent(descriptionText: "${device.displayName} woke up", isStateChange: false)
    def deviceCmds = [ formatCommand(zwave.basicV1.basicGet()),
                       formatCommand(zwave.batteryV1.batteryGet()),
    ]

    //add configuration commands to send to device
    def configDeviceCmds = deviceConfigCmds
    configDeviceCmds.each {
         deviceCmds << it
    }
    
    //tell device we don't have any more commands to send
    if(state.bootStrap) deviceCmds << formatCommand(zwave.wakeUpV2.wakeUpNoMoreInformation())
    state.bootStrap = true
    
    //add N delay between all the commands
    def result = delayBetween(deviceCmds, commandDelay)
    
    return [ event,response(result) ]
}

def zwaveEvent(physicalgraph.zwave.Command cmd) {
    // This will capture any commands not handled by other instances of zwaveEvent
    // and is recommended for development so you can see every command the device sends
    log.debug "UnencapsulatedCommand: ${cmd}"
    return createEvent(descriptionText: "${device.displayName}: ${cmd}")
}

def configure() {
    def deviceCmds = []
    log.debug "Configure() called"
    log.debug "settings: ${settings.inspect()}, state: ${state.inspect()}"
    
    //check to see if secure command class is set and set secure flag
    if(device.rawDescription) {
        def cc = device.rawDescription.split()[8]
        if(cc && cc.substring(3).split(',').contains('98')) {
            log.debug "Found Security Command Class 0x98, setting state.sec = true"
            state.sec = true
        }
    }
    return delayBetween([
                          formatCommand(zwave.associationV1.associationSet(groupingIdentifier:1, nodeId:zwaveHubNodeId))
                        ], commandDelay)
}

def updated() {
    log.debug "updated() called"
    log.debug "settings: ${settings.inspect()}, state: ${state.inspect()}"
    state.configure = true
}

private getCommandDelay() {
    return 700
}

private getConfigurationMap() {
    def deviceConfig = [
        binaryReportValue: [
                             index: 1,
                             size: 1,
                             friendlyName: 'Binary Report Value',
                             description: '0 - Open: 0x00, Close: 0xFF\n1 - Open: 0xFF, Close: 0x00\nDefault - 1',
                             default: 1,
                             type: 'number',
                             range: '0..1'
                           ],
        enableWakeup: [
                        index: 2,
                        size: 1,
                        friendlyName: 'Enable 10 min Wake up',
                        description: 'Enable/disable wake up 10 min\nwhen re-power on the Sensor.\nDefault - 1',
                        default: 1,
                        type: 'number',
                        range: '0..1'
                      ],
        basicReportValue: [
                            index: 3,
                            size: 1,
                            friendlyName: 'Basic Report Value',
                            description: '0 - Open: 0x00, Close: 0xFF\n1 - Open: 0xFF, Close: 0x00\nDefault - 1',
                            default: 1,
                            type: 'number',
                            range: '0..1'
                          ],
        lowBatteryThreshold: [
                               index: 39,
                               size: 1,
                               friendlyName: 'Low Battery Threshold %',
                               description: 'The range of low battery value can be\nset to 10% to 50%.\nDefault - 10',
                               default: 10,
                               type: 'number',
                               range: '10..50'
                             ],
        batteryReportInterval: [
                                 index: 111,
                                 size: 4,
                                 friendlyName: 'Battery Report Interval',
                                 description: 'How frequent to report battery interval\n0 - 2147483647',
                                 default: 0,
                                 type: 'number',
                                 range: '0..2147483647'
                               ],
        reportType: [
                      index: 121,
                      size: 4,
                      friendlyName: 'Sensor Report Type to Send',
                      description: 'The type of report to send\nDefault - Basic Report',
                      default: 'Basic Report',
                      type: 'enum',
                      enum: reportTypeMap
                    ],
        resetDefault: [
                        index: 255,
                        size: 1,
                        friendlyName: 'Reset Device to Default Settings',
                        description: 'Reset the sensor to factory settings\nDefault - 0',
                        default: 0,
                        type: 'number',
                        range: '0..1'
                      ],
    ]
    return deviceConfig
}

private getReportTypeMap() {
    return [ "Basic Report": 1,
             "Binary Report":16,
             "Both":17
    ]
}

private getDeviceConfigCmds() {
    def deviceCmds = []
    
    //Configuration commands
    if (! state.bootStrap) {
        //set the default parameters if we are boot strapping.
        def params = [ "reportType","batteryReportInterval","lowBatteryThreshold","basicReportValue","enableWakeup","binaryReportValue" ]
        params.each {
            def configCmd = configureDevice(it, configurationMap."${it}"?.default)
            if(configCmd) deviceCmds << configCmd
        }
    } else if (state.configure) {
        //create the state.settings map if it doesn't exist
        if(state.settings == null) state.settings = [:]
        
        //iterate each user setting and send configuration command parameters for new or changed values
        settings.each {
            if(state.settings?."${it.key}" == null || state.settings."${it.key}" != it.value) {
                def configCmd = configureDevice(it.key, it.value)
                if(configCmd) deviceCmds << configCmd
                state.settings."${it.key}" = it.value
            } 
        }
        state.configure = false
    }
    return deviceCmds
}

private configureDevice(param, value) {
    //set the initial configuration for bootstrap
    def deviceCmd = null
    def config = configurationMap."${param}"
    //validate config data and build set commands
    if(value != null && config != null && config?.type != null && config?.index != null && config?.size != null) {
        if(config.type == "number") {
            deviceCmd = formatCommand(zwave.configurationV1.configurationSet(parameterNumber: config.index, scaledConfigurationValue: value, size: config.size))
        } else if(config.type == "enum") {
            def enumVal = config?.enum?."${value}"
            if(enumVal != null) {
                deviceCmd = formatCommand(zwave.configurationV1.configurationSet(parameterNumber: config.index, scaledConfigurationValue: enumVal, size: config.size))
            } else {
                log.warn "Unable to map enum for ${param} to configuration value, skipping..."
            }
        } else {
            log.warn "Unhandled configuration value type for option ${param}, skipping..."
        }
    } else {
        log.warn "Invalid configuration param or value passed to configureDevice(), param: ${param} value: ${value}"
    }
    return deviceCmd
}

private formatCommand(physicalgraph.zwave.Command cmd) {
    if (state.sec) {
        zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
    } else {
        cmd.format()
    }
}
