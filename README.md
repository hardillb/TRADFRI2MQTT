# Invocation

java -jar TRADFRI2MQTT-X.X.X-SNAPSHOT.jar -ip {gateway IP} -psk {gateway secret} -broker {MQTT broker URL} [-retained]

The optional `-retained` configures the MQTT topics as retained.

e.g.

`java -jar TRADFRI2MQTT-X.X.X-SNAPSHOT.jar -ip 192.168.1.XXX -psk xxxxxxxxxxxxxxxx -broker tcp://localhost`

Publishes state messages on topics like this:

 - TRÅDFRI/bulb/Living Room Light/state/on
 - TRÅDFRI/bulb/Living Room Light/state/dim
 - TRÅDFRI/bulb/Living Room Light/state/temperature
 - TRÅDFRI/room/Living Room/state/on
 - TRÅDFRI/room/Living Room/state/dim
 

Subscribes to control messages on topics like this:

 - TRÅDFRI/bulb/Living Room Light/control/on
 - TRÅDFRI/bulb/Living Room Light/control/dim
 - TRÅDFRI/bulb/Living Room Light/control/temperature
 - TRÅDFRI/room/Living Room/control/on
 - TRÅDFRI/room/Living Room/control/dim

publish 0/1 to the `on` topic to turn the light off/on respectively

publish 0-254 to the `dim` topic to change the brightness

publish "cold" / "normal" / "warm" to the `temperature` topic to change temperatures. this only works on bulbs

# MQTT broker example
An easy-to-use MQTT broker is [mosquitto](https://mosquitto.org/).

After installation run it locally with `mosquitto`.

Then submit commands like this:
`mosquitto_pub -t "TRÅDFRI/bulb/LivingRoomBulb1/control/temperature" -m warm`
or subscribe like this:
`mosquitto_sub -t "TRÅDFRI/room/LivingRoom/state/on"`

# Installation on Docker

Optionally, TRADFRI2MQTT can be installed and run within a Docker image using the following instructions:

1. Clone this GIT repository.
2. Build the tradfri2mqtt docker image like so:
  `docker build -t tradfri2mqtt .`
3. Run tradfri2mqtt within a docker container:
  `docker run -rm tradfri2mqtt -ip [gateway ip] -psk [gateway secret] -broker [mqtt broker url]`
