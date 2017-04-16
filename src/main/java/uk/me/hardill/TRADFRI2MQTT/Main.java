/**
 * 
 */
package uk.me.hardill.TRADFRI2MQTT;

import static uk.me.hardill.TRADFRI2MQTT.TradfriConstants.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Vector;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
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
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * @author hardillb
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
	
	private HashMap<String, Integer> name2id = new HashMap<>();
	private Vector<CoapObserveRelation> watching = new Vector<>();
	
	Main(String psk, String ip, String broker) {
		this.ip = ip;
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
					boolean bulb = parts[1].equals("bulb");
					int id = name2id.get(parts[2]);
					
					System.out.println(id);
					String command = parts[4];
					System.out.println(command);
					try{
						JSONObject json = new JSONObject();
						if (bulb) { // single bulb
							JSONObject settings = new JSONObject();
							JSONArray array = new JSONArray();
							array.put(settings);
							json.put(LIGHT, array);
							if (command.equals("dim")) {
								settings.put(DIMMER, Math.min(DIMMER_MAX, Math.max(DIMMER_MIN, Integer.parseInt(message.toString()))));
								settings.put(TRANSITION_TIME, 3);	// transition in seconds
							} else if (command.equals("temperature")) {
								// not sure what the COLOR_X and COLOR_Y values do, it works without them...
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
								}
							} else if (command.equals("on")) {
								if (message.toString().equals("0")) {
									settings.put(ONOFF, 0);
								} else {
									settings.put(ONOFF, 1);
								}
							}
							String payload = json.toString();
							Main.this.set("coaps://" + Main.this.ip + "//" + DEVICES + "/" + id, payload);
						} else { // whole group
							if (command.equals("dim")) {
								json.put(DIMMER, Integer.parseInt(message.toString()));
								json.put(TRANSITION_TIME, 3);
							} else {
								if (message.toString().equals("0")) {
									json.put(ONOFF, 0);
								} else {
									json.put(ONOFF, 1);
								}
							}
							String payload = json.toString();
							Main.this.set("coaps://" + Main.this.ip + "//" + GROUPS + "/" + id, payload);
						}
					} catch (Exception e) {
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
				for(CoapObserveRelation rel: watching) {
					rel.reregister();
				}
			}
		};
		executor.scheduleAtFixedRate(command, 120, 120, TimeUnit.SECONDS);
	}
	
	private void discover() {
		//bulbs
		try {
			URI uri = new URI("coaps://" + ip + "//" + DEVICES);
			CoapClient client = new CoapClient(uri);
			client.setEndpoint(endPoint);
			CoapResponse response = client.get();
			if (response == null) {
				System.out.println("Connection to Gateway timed out, please check ip address or increase the ACK_TIMEOUT in the Californium.properties file");
				System.exit(-1);
			}
			JSONArray array = new JSONArray(response.getResponseText());
			for (int i=0; i<array.length(); i++) {
				String devUri = "coaps://" + ip + "//" + DEVICES + "/" + array.getInt(i);
				this.watch(devUri);
			}
			client.shutdown();
		} catch (URISyntaxException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		try {
			URI uri = new URI("coaps://" + ip + "//" + GROUPS);
			CoapClient client = new CoapClient(uri);
			client.setEndpoint(endPoint);
			CoapResponse response = client.get();
			if (response == null) {
				System.out.println("Connection to Gateway timed out, please check ip address or increase the ACK_TIMEOUT in the Californium.properties file");
				System.exit(-1);
			}
			JSONArray array = new JSONArray(response.getResponseText());
			for (int i=0; i<array.length(); i++) {
				String devUri = "coaps://" + ip + "//" + GROUPS + "/" + array.getInt(i);
				this.watch(devUri);
			}
			client.shutdown();
		} catch (URISyntaxException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	private void set(String uriString, String payload) {
		System.out.println("payload\n" + payload);
		try {
			URI uri = new URI(uriString);
			CoapClient client = new CoapClient(uri);
			client.setEndpoint(endPoint);
			CoapResponse response = client.put(payload, MediaTypeRegistry.TEXT_PLAIN);
			if (response.isSuccess()) {
				System.out.println("Yay");
			} else {
				System.out.println("Boo");
			}
			
			client.shutdown();
			
		} catch (URISyntaxException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	private void watch(String uriString) {
		
		try {
			URI uri = new URI(uriString);
			
			CoapClient client = new CoapClient(uri);
			client.setEndpoint(endPoint);
			CoapHandler handler = new CoapHandler() {
				
				@Override
				public void onLoad(CoapResponse response) {
					System.out.println(response.getResponseText());
					System.out.println(response.getOptions().toString());
					try {
						JSONObject json = new JSONObject(response.getResponseText());
						//TODO change this test to something based on 5750 values
						// 2 = light?
						// 0 = remote/dimmer?
						if (json.has(LIGHT) && (json.has(TYPE) && json.getInt(TYPE) == 2)) { // single bulb

							JSONObject light = json.getJSONArray(LIGHT).getJSONObject(0);

							if (!light.has(ONOFF)) {
								System.err.println("Bulb '" + json.getString(NAME) + "' has no On/Off value (probably no power on lightbulb socket)");
								return; // skip this lamp for now
							}
							int state = light.getInt(ONOFF);
							String topic = "TRÅDFRI/bulb/" + json.getString(NAME) + "/state/on";
							String topic2 = "TRÅDFRI/bulb/" + json.getString(NAME) + "/state/dim";
							String topic3 = "TRÅDFRI/bulb/" + json.getString(NAME) + "/state/temperature";

							MqttMessage message = new MqttMessage();
							message.setPayload(Integer.toString(state).getBytes());
//							message.setRetained(true);

							name2id.put(json.getString(NAME), json.getInt(INSTANCE_ID));

							MqttMessage message2 = null;
							if (light.has(DIMMER)) {
								message2 = new MqttMessage();
								int dim = light.getInt(DIMMER);
								message2.setPayload(Integer.toString(dim).getBytes());
//								message2.setRetained(true);
							} else {
								System.err.println("Bulb '" + json.getString(NAME) + "' has no dimming value (maybe just no power on lightbulb socket)");
							}

							MqttMessage message3 = null;
							if (light.has(COLOR)) {
								message3 = new MqttMessage();
								String temperature = light.getString(COLOR);
								message3.setPayload(temperature.getBytes());
//								message3.setRetained(true);
							} else { // just fyi for the user. maybe add further handling later
								System.out.println("Bulb '" + json.getString(NAME) + "' doesn't support color temperature");
							}

							try {
								mqttClient.publish(topic, message);
								if (message2 != null) {
									mqttClient.publish(topic2, message2);
								}
								if (message3 != null) {
									mqttClient.publish(topic3, message3);
								}
							} catch (MqttException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
						} else if (json.has(HS_ACCESSORY_LINK)) { // groups have this entry
							//room?
							System.out.println("room");
							name2id.put(json.getString(NAME), json.getInt(INSTANCE_ID));

							String topic = "TRÅDFRI/room/" + json.getString(NAME) + "/state/on";
							String topic2 = "TRÅDFRI/room/" + json.getString(NAME) + "/state/dim";

							MqttMessage message = new MqttMessage();
							int state = json.getInt(ONOFF);
							message.setPayload(Integer.toString(state).getBytes());

							MqttMessage message2 = new MqttMessage();
							int dim = json.getInt(DIMMER);
							message2.setPayload(Integer.toString(dim).getBytes());

							try {
								mqttClient.publish(topic, message);
								mqttClient.publish(topic2, message2);
							} catch (MqttException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
						} else {
							System.out.println("not bulb");
						}
					} catch (JSONException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					
				}
				
				@Override
				public void onError() {
					// TODO Auto-generated method stub
					System.out.println("problem with observe");
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
	 */
	public static void main(String[] args) {
		Options options = new Options();
		options.addOption("psk", true, "The Secret on the base of the gateway");
		options.addOption("ip", true, "The IP address of the gateway");
		options.addOption("broker", true, "MQTT URL");
		
		CommandLineParser parser = new DefaultParser();
		CommandLine cmd = null;
		try {
			cmd = parser.parse( options, args);
		} catch (ParseException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		String psk = cmd.getOptionValue("psk");
		String ip = cmd.getOptionValue("ip");
		String broker = cmd.getOptionValue("broker");
		
		if (psk == null || ip == null || broker == null) {
			HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp( "TRADFRI2MQTT", options );
			System.exit(1);
		}

		Main m = new Main(psk, ip, broker);
		m.discover();
	}

}
