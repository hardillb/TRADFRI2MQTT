/**
 * 
 */
package uk.me.hardill.TRADFRI2MQTT;

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
					int id = name2id.get(parts[1]);
					System.out.println(id);
					String command = parts[3];
					System.out.println(command);
					try{
						JSONObject json = new JSONObject();
						JSONObject settings = new JSONObject();
						JSONArray array = new JSONArray();
						array.put(settings);
						json.put("3311", array);
						if (command.equals("dim")) {
							settings.put("5851", Integer.parseInt(message.toString()));
							settings.put("5712", 3);	// second transition
						} else if (command.equals("on")) {
							if (message.toString().equals("0")) {
								settings.put("5850", 0);
							} else {
								settings.put("5850", 1);
							}
						}
						String payload = json.toString();
						Main.this.set("coaps://" + ip + "//15001/" + id, payload);
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
			mqttClient.subscribe("TRÅDFRI/+/control/+");
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
		try {
			URI uri = new URI("coaps://" + ip + "//15001");
			CoapClient client = new CoapClient(uri);
			client.setEndpoint(endPoint);
			CoapResponse response = client.get();
			JSONArray array = new JSONArray(response.getResponseText());
			for (int i=0; i<array.length(); i++) {
				String devUri = "coaps://"+ ip + "//15001/" + array.getInt(i);
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
						//TODO change this test to someting based on 5750 values
						// 2 = light?
						// 0 = remote/dimmer?
						if (json.has("3311")){
							MqttMessage message = new MqttMessage();
							int state = json.getJSONArray("3311").getJSONObject(0).getInt("5850");
							message.setPayload(Integer.toString(state).getBytes());
							message.setRetained(true);
							String topic = "TRÅDFRI/" + json.getString("9001") + "/state/on";
							String topic2 = "TRÅDFRI/" + json.getString("9001") + "/state/dim";
							name2id.put(json.getString("9001"), json.getInt("9003"));
							MqttMessage message2 = new MqttMessage();
							int dim = json.getJSONArray("3311").getJSONObject(0).getInt("5851");
							message2.setPayload(Integer.toString(dim).getBytes());
							message2.setRetained(true);
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
