java -jar TRADFRI2MQTT-0.0.1-SNAPSHOT.jar -ip <gateway ip> -psk <gateway secret> -broker <mqtt broker url>

e.g.
java -jar TRADFRI2MQTT-0.0.1-SNAPSHOT.jar -ip 192.168.1.111 -psk xxxxxxxxxxxxxxxx -broker tcp://localhost

Publishes state messages on topics like this:

TRÅDFRI/Living Room Light/state/on
TRÅDFRI/Living Room Light/state/dim

Subscribes to control messages on topics like this:

TRÅDFRI/Living Room Light/control/on
TRÅDFRI/Living Room Light/control/dim

publish 0/1 to the `on` topic to turn the light off/on respectively
publish 0-254 to the `dim` topic to change the brightness