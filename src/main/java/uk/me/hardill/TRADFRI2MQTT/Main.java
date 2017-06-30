/**
 * 
 */
package uk.me.hardill.TRADFRI2MQTT;

import static uk.me.hardill.TRADFRI2MQTT.TradfriConstants.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map.Entry;
import java.util.Vector;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.collections4.BidiMap;
import org.apache.commons.collections4.bidimap.DualHashBidiMap;
import org.eclipse.californium.core.CaliforniumLogger;
import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapHandler;
import org.eclipse.californium.core.CoapObserveRelation;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.scandium.DTLSConnector;
import org.eclipse.californium.scandium.ScandiumLogger;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.californium.scandium.dtls.pskstore.StaticPskStore;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * @author hardillb & r41d
 *
 */
public class Main {

	static {
		CaliforniumLogger.disableLogging();
		ScandiumLogger.disable();
//		ScandiumLogger.initialize();
//		ScandiumLogger.setLevel(Level.FINE);
	}

	private DTLSConnector dtlsConnector;
	private MqttClient mqttClient;
	private CoapEndpoint endPoint;

	private String ip;
	private boolean retainedQueues;

	// mapping: device ID -> device names
	private BidiMap<Integer, String> id2device = new DualHashBidiMap<>();
	// mapping: room ID -> room name
	private BidiMap<Integer, String> id2room = new DualHashBidiMap<>();
	// mapping room ID -> Mood (ID,Name)
	// The outer HashMap must not be a BidiMap because it leads to bugs when more than one empty BidiMap is contained as a value
	private HashMap<Integer, BidiMap<Integer,String>> roomID2moods = new HashMap<>();
	private Vector<CoapObserveRelation> watching = new Vector<>();

	Main(String psk, String ip, String broker, boolean retained) {
		this.ip = ip;
		this.retainedQueues = retained;
		DtlsConnectorConfig.Builder builder = new DtlsConnectorConfig.Builder(new InetSocketAddress(0));
		builder.setPskStore(new StaticPskStore("", psk.getBytes()));
		dtlsConnector = new DTLSConnector(builder.build());
		endPoint = new CoapEndpoint(dtlsConnector, NetworkConfig.getStandard());

		MemoryPersistence persistence = new MemoryPersistence();
		try {
			mqttClient = new MqttClient(broker, MqttClient.generateClientId(), persistence);
			mqttClient.connect();
			mqttClient.setCallback(new MqttCallback() {

				@Override
				public void messageArrived(String topic, MqttMessage message) throws Exception {
					// TODO Auto-generated method stub
					System.out.println(topic + " " + message.toString());
					String parts[] = topic.split("/");

					String entityType = parts[1]; // bulb or room
					String entityName = parts[2]; // name of bulb or room
					assert parts[3].equals("control"); // something *really* went wrong if this doesn't hold
					String command = parts[4];

					try {
						JSONObject json = new JSONObject();
						String payload;

						switch (entityType) {

						case "bulb": // single bulb
							JSONObject settings = new JSONObject();
							JSONArray array = new JSONArray();
							array.put(settings);
							json.put(LIGHT, array);

							switch (command) {
							case "on":
								switch (message.toString()) {
								case "0":
								case "1":
									settings.put(ONOFF, Integer.parseInt(message.toString()));
									break;
								default:
									System.err.println("Invalid OnOff value '" + message.toString() + "'for bulb " + entityName);
									return;
								}
								break;
							case "dim":
								int dimval = Integer.parseInt(message.toString());
								settings.put(DIMMER, Math.min(DIMMER_MAX, Math.max(DIMMER_MIN, dimval)));
								settings.put(TRANSITION_TIME, 3); // transition in seconds
								break;
							case "temperature":
								// not sure what the COLOR_X and COLOR_Y values
								// do, it works without them...
								switch (message.toString()) {
								case "cold":
									settings.put(COLOR, COLOR_COLD);
									break;
								case "normal":
									settings.put(COLOR, COLOR_NORMAL);
									break;
								case "warm":
									settings.put(COLOR, COLOR_WARM);
									break;
								default:
									System.err.println("Invalid temperature supplied: " + message.toString());
									return;
								}
								break;
							default:
								System.err.println("Invalid command supplied: " + command);
								return;
							}
							payload = json.toString();
							Main.this.set("coaps://" + Main.this.ip + "//" + DEVICES + "/" + id2device.getKey(entityName), payload);
							break;

						case "room": // whole room
							switch (command) {
							case "on":
								switch (message.toString()) {
								case "0":
								case "1":
									json.put(ONOFF, Integer.parseInt(message.toString()));
									break;
								default:
									System.err.println("Invalid OnOff value '" + message.toString() + "'for room " + entityName);
									return;
								}
								break;
							case "dim":
								json.put(DIMMER, Integer.parseInt(message.toString()));
								json.put(TRANSITION_TIME, 3);
								break;
							case "mood":
								String moodName = message.toString();
								int roomID = id2room.getKey(entityName);
								Integer moodID = roomID2moods.get(roomID).getKey(moodName);
								if (moodID != null) {
									json.put(SCENE_ID, moodID);
								} else {
									System.err.println("Mood " + moodName + " for room " + entityName + " not found");
									return;
								}
								break;
							default:
								System.err.println("Invalid command for room: " + command);
								return;
							}
							payload = json.toString();
							Main.this.set("coaps://" + Main.this.ip + "//" + GROUPS + "/" + id2room.getKey(entityName), payload);
							break;

						default:
							System.err.println("Invalid entityType: " + entityType);
							return;
						}

					} catch (Exception e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}

				@Override
				public void deliveryComplete(IMqttDeliveryToken token) {
					// TODO Auto-generated method stub
				}

				@Override
				public void connectionLost(Throwable cause) {
					// TODO Auto-generated method stub
				}
			});
			mqttClient.subscribe("TRÅDFRI/bulb/+/control/+");
			mqttClient.subscribe("TRÅDFRI/room/+/control/+");
		} catch (MqttException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
		Runnable command = new Runnable() {
			@Override
			public void run() {
				System.out.println("re-reg");
				for (CoapObserveRelation rel : watching) {
					rel.reregister();
				}
			}
		};
		executor.scheduleAtFixedRate(command, 120, 120, TimeUnit.SECONDS);
	}

	private void discover() {

		// Discover Bulbs
		try {
			URI uri = new URI("coaps://" + ip + "//" + DEVICES);
			CoapClient client = new CoapClient(uri);
			client.setEndpoint(endPoint);
			CoapResponse response = client.get();
			if (response == null) {
				System.out.println("Connection to Gateway timed out, please check ip address or increase the ACK_TIMEOUT in the Californium.properties file");
				System.exit(-1);
			}
			JSONArray devices = new JSONArray(response.getResponseText());
			for (int i = 0; i < devices.length(); i++) {
				this.watch(DEVICES, ""+devices.getInt(i));
			}
			client.shutdown();
		} catch (URISyntaxException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// Discover Rooms/Groups
		try {
			URI uri = new URI("coaps://" + ip + "//" + GROUPS);
			CoapClient client = new CoapClient(uri);
			client.setEndpoint(endPoint);
			CoapResponse response = client.get();
			if (response == null) {
				System.out.println("Connection to Gateway timed out, please check ip address or increase the ACK_TIMEOUT in the Californium.properties file");
				System.exit(-1);
			}
			JSONArray rooms = new JSONArray(response.getResponseText());
			for (int i = 0; i < rooms.length(); i++) {
				this.watch(GROUPS, "" + rooms.getInt(i));
			}
			client.shutdown();
		} catch (URISyntaxException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// Discover all Moods for all Rooms that were found
		// Moods are structured as follows: IP:5684/15005/RoomID/MoodID
		try {
			URI sceneURI = new URI("coaps://" + ip + "//" + SCENE);
			CoapClient client = new CoapClient(sceneURI);
			client.setEndpoint(endPoint);
			CoapResponse responseRooms = client.get();
			if (responseRooms == null) {
				System.out.println("Connection to Gateway timed out, please check ip address or increase the ACK_TIMEOUT in the Californium.properties file");
				System.exit(-1);
			}
			JSONArray moodRooms = new JSONArray(responseRooms.getResponseText());
			for (int roomIdx = 0; roomIdx < moodRooms.length(); roomIdx++) {
				int roomID = moodRooms.getInt(roomIdx);
				// prepare Bidirectional Map for storing the moods for the current room
				roomID2moods.put(roomID, new DualHashBidiMap<Integer, String>());

				URI moodUri = new URI("coaps://" + ip + "//" + SCENE + "/" + roomID);
				client = new CoapClient(moodUri);
				client.setEndpoint(endPoint);
				CoapResponse responseMoods = client.get();
				if (responseMoods == null) {
					System.out.println("Connection to Gateway timed out, please check ip address or increase the ACK_TIMEOUT in the Californium.properties file");
					System.exit(-1);
				}
				JSONArray moodsIDs = new JSONArray(responseMoods.getResponseText());
				for (int moodIdx = 0; moodIdx < moodsIDs.length(); moodIdx++) {
					this.watch(SCENE, "" + roomID, "" + moodsIDs.getInt(moodIdx));
				}
				client.shutdown();
			}
		} catch (URISyntaxException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	private void set(String uriString, String payload) {
		//System.out.println("payload\n" + payload);
		try {
			URI uri = new URI(uriString);
			CoapClient client = new CoapClient(uri);
			client.setEndpoint(endPoint);
			CoapResponse response = client.put(payload, MediaTypeRegistry.TEXT_PLAIN);
			if (response != null && response.isSuccess()) {
				//System.out.println("Yay");
			} else {
				System.out.println("Sending payload to " + uriString + " failed!");
			}
			client.shutdown();
		} catch (URISyntaxException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private void watch(String realm, final String... path) {

		try {
			String uriString = "coaps://" + ip + "//" + realm + "/" + Arrays.asList(path).stream().collect(Collectors.joining("/"));
			CoapClient client = new CoapClient(new URI(uriString));
			client.setEndpoint(endPoint);
			CoapHandler handler = new CoapHandler() {

				@Override
				public void onLoad(CoapResponse response) {
					//System.out.println(response.getResponseText());
					//System.out.println(response.getOptions().toString());
					try {
						JSONObject json = new JSONObject(response.getResponseText());
						int ID = json.getInt(INSTANCE_ID);
						String name = json.getString(NAME);
						// TODO change this test to something based on 5750 values
						// 2 = light?
						// 0 = remote/dimmer?
						if (json.has(TYPE) && json.getInt(TYPE) == TYPE_BULB && json.has(LIGHT)) { // single bulb
							String socket = "";
							try {
								socket = " " + json.getJSONObject("3").getString("1").split(" ")[2];
							} catch (JSONException e) {}
							System.out.println("Processing" + socket + " Bulb " + name + " " + response.getResponseText());

							id2device.put(ID, name);

							JSONObject light = json.getJSONArray(LIGHT).getJSONObject(0);

							if (!light.has(ONOFF)) {
								System.err.println("Bulb '" + name + "' has no On/Off value (probably no power on lightbulb socket)");
								return; // skip this lamp for now
							}
							int state = light.getInt(ONOFF);
							String topicBulbOnOff = "TRÅDFRI/bulb/" + name + "/state/on";
							String topicBulbDim = "TRÅDFRI/bulb/" + name + "/state/dim";
							String topicBulbTemp = "TRÅDFRI/bulb/" + name + "/state/temperature";

							MqttMessage messageBulbOnOff = new MqttMessage();
							messageBulbOnOff.setPayload(Integer.toString(state).getBytes());
							if (retainedQueues) {
								messageBulbOnOff.setRetained(true);
							}
							mqttClient.publish(topicBulbOnOff, messageBulbOnOff);

							MqttMessage messageBulbDim = null;
							if (light.has(DIMMER)) {
								messageBulbDim = new MqttMessage();
								int dim = light.getInt(DIMMER);
								messageBulbDim.setPayload(Integer.toString(dim).getBytes());
								if (retainedQueues) {
									messageBulbDim.setRetained(true);
								}
								mqttClient.publish(topicBulbDim, messageBulbDim);
							} else {
								System.err.println("Bulb '" + name + "' has no dimming value (maybe just no power on lightbulb socket)");
								// no dim topic is created for this bulb
							}

							MqttMessage messageBulbTemp = null;
							if (light.has(COLOR)) {
								messageBulbTemp = new MqttMessage();
								String temperature = light.getString(COLOR);
								messageBulbTemp.setPayload(temperature.getBytes());
								if (retainedQueues) {
									messageBulbTemp.setRetained(true);
								}
								mqttClient.publish(topicBulbTemp, messageBulbTemp);
							} else { // just fyi for the user. maybe add further handling later
								System.out.println("Bulb '" + name + "' doesn't support color temperature");
							}

						} else if (json.has(HS_ACCESSORY_LINK)) { // groups have this entry
							JSONArray lamps = null;
							try {
								lamps = json.getJSONObject("9018").getJSONObject("15002").getJSONArray("9003");
							} catch (JSONException e) {}
							List<String> lampNames = new ArrayList<>();
							if (lamps != null)
								for (int i = 0; i < lamps.length(); i++)
									lampNames.add(id2device.get(lamps.getInt(i)));
							String ll = " (" + lampNames.stream().collect(Collectors.joining(" ")) + ")";
							System.out.println("Processing Room " + name + ll + " " + response.getResponseText());
							id2room.put(json.getInt(INSTANCE_ID), name);

							String topicRoomOnOff = "TRÅDFRI/room/" + name + "/state/on";
							String topicRoomDim = "TRÅDFRI/room/" + name + "/state/dim";

							MqttMessage messageRoomOnOff = new MqttMessage();
							int state = json.getInt(ONOFF);
							messageRoomOnOff.setPayload(Integer.toString(state).getBytes());
							if (retainedQueues) {
								messageRoomOnOff.setRetained(true);
							}
							mqttClient.publish(topicRoomOnOff, messageRoomOnOff);

							MqttMessage messageRoomDim = new MqttMessage();
							int dim = json.getInt(DIMMER);
							messageRoomDim.setPayload(Integer.toString(dim).getBytes());
							if (retainedQueues) {
								messageRoomDim.setRetained(true);
							}
							mqttClient.publish(topicRoomDim, messageRoomDim);

						} else if (json.has(IKEA_MOODS)) {
							System.out.println("Processing Mood " + name + " for Room ID " + path[0] + " " + response.getResponseText());
							// Store Mood in database
							roomID2moods.get(Integer.parseInt(path[0])).put(ID, name);
						} else if (json.has(TYPE) && json.getInt(TYPE) == TYPE_REMOTE) {
							System.out.println("Processing Remote " + name + " " + response.getResponseText());
							// save this to device list, even though it's not used yet
							id2device.put(json.getInt(INSTANCE_ID), name);
						} else {
							System.out.println("Got entity '" + name + "' that is neither bulb, group or remote..." + " " + response.getResponseText());
						}
					} catch (JSONException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					} catch (MqttPersistenceException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					} catch (MqttException e) {
						System.err.println("Publishing failed: " + e.getMessage());
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}

				@Override
				public void onError() {
					System.out.println("CoAP request timed out or was rejected by the server.");
				}
			};
			CoapObserveRelation relation = client.observe(handler);
			watching.add(relation);

		} catch (URISyntaxException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	/**
	 * @param args
	 * @throws InterruptedException 
	 */
	public static void main(String[] args) throws InterruptedException {
		Options options = new Options();
		options.addOption("psk", true, "The Secret on the base of the gateway");
		options.addOption("ip", true, "The IP address of the gateway");
		options.addOption("broker", true, "MQTT URL");
		options.addOption("retained", true, "Topics are retained");

		CommandLineParser parser = new DefaultParser();
		CommandLine cmd = null;
		try {
			cmd = parser.parse(options, args);
		} catch (ParseException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		String psk = cmd.getOptionValue("psk");
		String ip = cmd.getOptionValue("ip");
		String broker = cmd.getOptionValue("broker");
		String retained = cmd.getOptionValue("retained");

		if (psk == null || ip == null || broker == null) {
			HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp("TRADFRI2MQTT", options);
			System.exit(1);
		}

		Main m = new Main(psk, ip, broker, retained != null);
		m.discover();
	}

}
