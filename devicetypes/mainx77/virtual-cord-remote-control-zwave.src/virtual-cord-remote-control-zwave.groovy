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
 *  Graber Virtual Cord Remote Control Z-Wave (VCZ1)
 *
 *  Author: Jason Niesz
 *  Date: 2016-09-22
 */

metadata {
    definition (name: "Virtual Cord Remote Control Z-Wave", namespace: "mainx77", author: "Jason Niesz") {
        capability "Actuator"
        capability "Battery"
        capability "Button"
        fingerprint mfr: "026E", prod: "5643", model: "5A31"
    }

    simulator {
        status "button 1 pushed":  "command: 5B03, payload: 000001"
        status "button 1 held":  "command: 5B03, payload: 000201"
        status "button 1 released":  "command: 5B03, payload: 000101"
        status "button 2 pushed":  "command: 5B03, payload: 000002"
        status "button 2 held":  "command: 5B03, payload: 000202"
        status "button 2 released":  "command: 5B03, payload: 000102"
        status "wakeup":  "command: 8407, payload: "
    }

    tiles {
        standardTile("state", "device.state", width: 2, height: 2) {
            state "connected", label: "", icon: "st.unknown.zwave.remote-controller", backgroundColor: "#ffffff"
        }

        valueTile("battery", "device.battery", decoration: "flat") {
            state "battery", label:'${currentValue}% battery', unit:""
        }
        main "state"
        details(["state","battery"])
    }
}

def parse(String description) {
    def result = null
    def cmd = zwave.parse(description,[ 0x5B: 1, 0x80: 1, 0x84: 2 ])

    if (cmd) {
        result = zwaveEvent(cmd)
        log.debug "Event: ${result.inspect()}"
    } else {
        log.debug "Non-parsed event: ${description}"
    }
    return result
}

def zwaveEvent(physicalgraph.zwave.commands.wakeupv2.WakeUpNotification cmd) {
    log.debug "WakeUpNotification: ${cmd}"

    //everytime we wake up request battery report and sensor report
    def event = createEvent(descriptionText: "${device.displayName} woke up", isStateChange: false)
    def deviceCmds = [ zwave.batteryV1.batteryGet().format()
    ]

    //tell device we don't have any more commands to send
    deviceCmds << zwave.wakeUpV2.wakeUpNoMoreInformation().format()

    //add N delay between all the commands
    def result = delayBetween(deviceCmds, commandDelay)
    return [ event, response(result) ]
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
    [ createEvent(map), response(zwave.wakeUpV2.wakeUpNoMoreInformation()) ]
}

def zwaveEvent(physicalgraph.zwave.commands.centralscenev1.CentralSceneNotification cmd) {
    //log.debug "Scene: ${cmd.sceneNumber} Attributes: ${cmd.keyAttributes}"
    def event = null

    // scene 1 represents top button, scene 2 represents bottom button
    switch (cmd.sceneNumber) {
        case 1:
            event = handleKeyAttribute(cmd.sceneNumber, cmd.keyAttributes)
            break
        case 2:
            event = handleKeyAttribute(cmd.sceneNumber, cmd.keyAttributes)
            break
        default:
            log.debug "Unknown Scene Number: ${cmd.sceneNumber}"
            break
    }
    if (!state.lastbatt || (new Date().time) - state.lastbatt > 24*60*60*1000) {
        return [event,response(zwave.batteryV1.batteryGet().format())]
    } else {
        return event
    }
}

def zwaveEvent(physicalgraph.zwave.Command cmd) {
    // This will capture any commands not handled by other instances of zwaveEvent
    // and is recommended for development so you can see every command the device sends
    log.debug "UnencapsulatedCommand: ${cmd}"
    return createEvent(descriptionText: "${device.displayName}: ${cmd}")
}

private handleKeyAttribute(sceneNumber, keyAttributes) {
    def result = null
    // attribute 0 represents button press, attribute 2 represents press and hold, attribute 1 represents button hold release
    switch (keyAttributes) {
        case 0:
            result = createEvent(name: "button", value: "pushed", data: [buttonNumber: "${sceneNumber}"],
                descriptionText: "${device.displayName} Button ${sceneNumber} pushed", isStateChange: true)
            break
        case 1:
            result = createEvent(name: "button", value: "heldReleased", data: [buttonNumber: "${sceneNumber}"],
                descriptionText: "${device.displayName} Button ${sceneNumber} released", isStateChange: true)
            break
        case 2:
            result = createEvent(name: "button", value: "held", data: [buttonNumber: "${sceneNumber}"],
                descriptionText: "${device.displayName} Button ${sceneNumber} held", isStateChange: true)
            break
        default:
            log.debug "Unknown key Attribute: ${keyAttributes}"
    }
    return result
}

private getCommandDelay() {
    return 500
}