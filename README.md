java -jar TRADFRI2MQTT-0.0.2-SNAPSHOT.jar -ip [gateway ip] -psk [gateway secret] -broker [mqtt broker url]

e.g.

`java -jar TRADFRI2MQTT-0.0.2-SNAPSHOT.jar -ip 192.168.1.111 -psk xxxxxxxxxxxxxxxx -broker tcp://localhost`

Publishes state messages on topics like this:

 - TRÅDFRI/bulb/Living Room Light/state/on
 - TRÅDFRI/bulb/Living Room Light/state/dim
 - TRÅDFRI/bulb/Living Room Light/state/color
 - TRÅDFRI/room/Living Room/state/on
 - TRÅDFRI/room/Living Room/state/dim
 

Subscribes to control messages on topics like this:

 - TRÅDFRI/bulb/Living Room Light/control/on
 - TRÅDFRI/bulb/Living Room Light/control/dim
 - TRÅDFRI/bulb/Living Room Light/control/color
 - TRÅDFRI/room/Living Room/control/on
 - TRÅDFRI/room/Living Room/control/dim

publish 0/1 to the `on` topic to turn the light off/on respectively

publish 0-254 to the `dim` topic to change the brightness

publish "cold" / "normal" / "warm" to the `color` topic to change colors. this only works on bulbs
