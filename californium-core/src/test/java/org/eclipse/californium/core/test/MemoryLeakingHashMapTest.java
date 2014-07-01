package org.eclipse.californium.core.test;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.Semaphore;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.Timer;

import junit.framework.Assert;

import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapHandler;
import org.eclipse.californium.core.CoapObserveRelation;
import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.coap.CoAP.Type;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.network.CoAPEndpoint;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.core.network.config.NetworkConfigDefaults;
import org.eclipse.californium.core.network.interceptors.MessageTracer;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.core.test.MemoryLeakingHashMapTest.TestResource.Mode;

public class MemoryLeakingHashMapTest {

	// Configuration for this test
	public static final int TEST_EXCHANGE_LIFECYCLE = 2470; // 2.47 seconds
	public static final int TEST_SWEEP_DEDUPLICATOR_INTERVAL = 1000; // 1 second
	public static final int TEST_BLOCK_SIZE = 16; // 16 bytes

	public static final int OBS_NOTIFICATION_INTERVALL = 500; // send one notification per 500 ms
	public static final int HOW_MANY_NOTIFICATION_WE_WAIT_FOR = 3;
	
	public static final int SERVER_PORT = 5683;
	public static final String SERVER_URI = "coap://localhost:"+SERVER_PORT;
	
	// The names of the two resources of the server
	public static final String PIGGY = "piggy";
	public static final String SEPARATE = "separate";
	
	// The server endpoint that we test
	private static CoAPEndpoint serverEndpoint;
	private static CoAPEndpoint clientEndpoint;
	private static EndpointSurveillant serverSurveillant;
	private static EndpointSurveillant clientSurveillant;

	private static String currentRequestText;
	private static String currentResponseText;
	
	public static void main(String[] args) throws Exception {
		
		NetworkConfig config = new NetworkConfig()
			// We make sure that the sweep deduplicator is used
			.setString(NetworkConfigDefaults.DEDUPLICATOR, NetworkConfigDefaults.DEDUPLICATOR_MARK_AND_SWEEP)
			.setInt(NetworkConfigDefaults.MARK_AND_SWEEP_INTERVAL, TEST_EXCHANGE_LIFECYCLE)
			.setLong(NetworkConfigDefaults.EXCHANGE_LIFECYCLE, TEST_SWEEP_DEDUPLICATOR_INTERVAL)
			
			// We set the block size to 16 bytes
			.setInt(NetworkConfigDefaults.MAX_MESSAGE_SIZE, TEST_BLOCK_SIZE)
			.setInt(NetworkConfigDefaults.DEFAULT_BLOCK_SIZE, TEST_BLOCK_SIZE);
		
		// Create the endpoint for the server and create surveillant
		serverEndpoint = new CoAPEndpoint(
				new InetSocketAddress((InetAddress) null, SERVER_PORT), config);
//		serverEndpoint.addInterceptor(new MessageTracer());
		serverSurveillant = new EndpointSurveillant("server", serverEndpoint);
		
		clientEndpoint = new CoAPEndpoint(config);
		clientEndpoint.addInterceptor(new MessageTracer());
		clientEndpoint.start();
		clientSurveillant = new EndpointSurveillant("client", clientEndpoint);
		
		// Create a server with two resources: one that sends piggy-backed
		// responses and one that sends separate responses
		CoapServer server = new CoapServer(config);
		server.addEndpoint(serverEndpoint);
		server.add(new TestResource(PIGGY,    Mode.PIGGY_BACKED_RESPONSE));
		server.add(new TestResource(SEPARATE, Mode.SEPARATE_RESPONE));
		server.start();
		
		/* TODO: remove*/ {
		JButton btnStatus = new JButton("Print Status");
		btnStatus.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				System.out.println("\nServer:");
				serverSurveillant.printHashmaps();
				
				System.out.println("\nClient:");
				clientSurveillant.printHashmaps();
			}
		});
		
		JFrame frame = new JFrame("Memory Debug Helper");
		frame.add(btnStatus);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setSize(400, 100);
		frame.setLocation(200, 200);
		frame.setVisible(true); }

		testServer();
	}
	
	private static void testServer() throws Exception {
		testSimpleNONGet(uriOf(PIGGY));
		
		testSimpleGet(uriOf(PIGGY));
		testSimpleGet(uriOf(SEPARATE));

		testBlockwise(uriOf(PIGGY));
		testBlockwise(uriOf(SEPARATE));
		
		// TODO Bug: The NON notifications are still in exchangesByMID
//		testObserve(uriOf(PIGGY));
		
		System.out.println("success");
	}
	
	private static void testSimpleNONGet(String uri) throws Exception {
		System.out.println("Test simple NON GET to "+uri);
		
		Request request = Request.newGet();
		request.setURI(uri);
		request.setType(Type.NON);
		Response response = request.send(clientEndpoint).waitForResponse(100);
		
		System.out.println("Client received response "+response.getPayloadString()+" with msg type "+response.getType());
		Assert.assertEquals(currentResponseText, response.getPayloadString());
		Assert.assertEquals(Type.NON, response.getType());
		
		serverSurveillant.waitUntilDeduplicatorShouldBeEmpty();
		serverSurveillant.assertHashMapsEmpty();
		clientSurveillant.assertHashMapsEmpty();
	}
	
	private static void testSimpleGet(String uri) throws Exception {
		System.out.println("Test simple GET to "+uri);
		
		CoapClient client = new CoapClient(uri);
		client.setEndpoint(clientEndpoint);
		
		CoapResponse response = client.get();
		System.out.println("Client received response "+response.getResponseText());
		Assert.assertEquals(currentResponseText, response.getResponseText());

		serverSurveillant.waitUntilDeduplicatorShouldBeEmpty();
		serverSurveillant.assertHashMapsEmpty();
		clientSurveillant.assertHashMapsEmpty();
	}
	
	private static void testBlockwise(String uri) throws Exception {
		System.out.println("Test blockwise POST to "+uri);
		
		CoapClient client = new CoapClient(uri);
		client.setEndpoint(clientEndpoint);
		
		String ten = "123456789.";
		currentRequestText = ten+ten+ten;
		CoapResponse response = client.post(currentRequestText, MediaTypeRegistry.TEXT_PLAIN);
		System.out.println("Client received response "+response.getResponseText());
		Assert.assertEquals(currentResponseText, response.getResponseText());
		
		serverSurveillant.waitUntilDeduplicatorShouldBeEmpty();
		serverSurveillant.assertHashMapsEmpty();
		clientSurveillant.assertHashMapsEmpty();
	}
	
	private static void testObserve(final String uri) throws Exception {
		System.out.println("Test observe relation to "+uri);
		
		// We use a semaphore to return after the test has completed
		final Semaphore semaphore = new Semaphore(0);

		/*
		 * This Handler counts the notification and cancels the relation when
		 * it has received HOW_MANY_NOTIFICATION_WE_WAIT_FOR.
		 */
		class CoapObserverAndCancler implements CoapHandler {
			private CoapObserveRelation relation;
			private int notificationCounter = 0;

			public void onLoad(CoapResponse response) {
				++notificationCounter;
				System.out.println("Client received notification "+notificationCounter+": "+response.getResponseText());
				
				if (notificationCounter == HOW_MANY_NOTIFICATION_WE_WAIT_FOR) {
					System.out.println("Client cancels observe relation to "+uri);
					// This sends a get without observe and we receive one MORE response!
					relation.proactiveCancel();
				
				} else if (notificationCounter == HOW_MANY_NOTIFICATION_WE_WAIT_FOR + 1) {
					// Now we received the response to the canceling GET request
					semaphore.release();
				}
			}
			
			public void onError() {
				Assert.assertTrue(false); // should not happen
			}
		}
		
		CoapClient client = new CoapClient(uri);
		client.setEndpoint(clientEndpoint);
		CoapObserverAndCancler handler = new CoapObserverAndCancler();
		CoapObserveRelation rel = client.observe(handler);
		handler.relation = rel;
		
		// Wait until we have received all the notifications and canceled the relation
		Thread.sleep(HOW_MANY_NOTIFICATION_WE_WAIT_FOR * OBS_NOTIFICATION_INTERVALL + 100);
		
		boolean success = semaphore.tryAcquire();
		Assert.assertTrue(success);
		
		serverSurveillant.waitUntilDeduplicatorShouldBeEmpty();
		serverSurveillant.assertHashMapsEmpty();
		clientSurveillant.assertHashMapsEmpty();
	}
	
	private static String uriOf(String resourcePath) {
		return SERVER_URI + "/" + resourcePath;
	}
	
	public static class TestResource extends CoapResource implements ActionListener {

		public enum Mode { PIGGY_BACKED_RESPONSE, SEPARATE_RESPONE; }
		
		private static Timer timer = new Timer(OBS_NOTIFICATION_INTERVALL, null);
		static { timer.start(); }
		
		private Mode mode;
		private int status;
		
		public TestResource(String name, Mode mode) {
			super(name);
			this.mode = mode;
			this.status = 0;
			
			setObservable(true);
			timer.addActionListener(this);
		}
		
		@Override public void actionPerformed(ActionEvent e) {
			++status;
			changed();
		}
		
		@Override public void handleGET(CoapExchange exchange) {
			if (mode == Mode.SEPARATE_RESPONE)
				exchange.accept();
			currentResponseText = "hello get "+status;
			exchange.respond(currentResponseText);
		}
		
		@Override public void handlePOST(CoapExchange exchange) {
			Assert.assertEquals(currentRequestText, exchange.getRequestText());
			if (mode == Mode.SEPARATE_RESPONE)
				exchange.accept();
			
			System.out.println("TestResource "+getName()+" received POST message: "+exchange.getRequestText());
			String ten = "123456789.";
			currentResponseText = "hello post "+status+ten+ten+ten;
			exchange.respond(ResponseCode.CREATED, currentResponseText);
		}
		
		@Override public void handlePUT(CoapExchange exchange) {
			Assert.assertEquals(currentRequestText, exchange.getRequestText());
			exchange.accept();
			currentResponseText = "";
			exchange.respond(ResponseCode.CHANGED);
		}
		
		@Override public void handleDELETE(CoapExchange exchange) {
			currentResponseText = "";
			exchange.respond(ResponseCode.DELETED);
		}
	}
}
