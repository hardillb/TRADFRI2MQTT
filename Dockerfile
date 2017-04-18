FROM 		maven:onbuild-alpine
ENTRYPOINT ["java","-jar","target/TRADFRI2MQTT-0.0.4-SNAPSHOT.jar"]
