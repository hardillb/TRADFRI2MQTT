FROM openjdk:8-jre-alpine

RUN		mkdir -p /usr/src/app
WORKDIR 	/usr/src/app
COPY 		target .
ENTRYPOINT 	["java","-jar","TRADFRI2MQTT-0.0.4-SNAPSHOT.jar"]
