package reactor.quickstart;

import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServlet;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Jon Brisbin
 */
public class WebSocketTradeServerExample {

	private static final Logger LOG         = LoggerFactory.getLogger(WebSocketTradeServerExample.class);
	private static       int    totalTrades = 10000000;
	private static CountDownLatch latch;
	private static long           startTime;

	public static void main(String[] args) throws Exception {
		Environment
		final TradeServer server = new TradeServer();

		// Use a Reactor to dispatch events using the high-speed Dispatcher
		final Reactor serverReactor = EventBus.create(env);

		// Create a single key and Selector for efficiency
		final Selector tradeExecute = Selectors.object("trade.execute");

		// For each Trade event, execute that on the server and notify connected clients
		// because each client that connects links to the serverReactor
		serverReactor.on(tradeExecute, (Event<Trade> ev) -> {
			server.execute(ev.getData());

			// Since we're async, for this test, use a latch to tell when we're done
			latch.countDown();
		});

		@SuppressWarnings("serial")
		WebSocketServlet wss = new WebSocketServlet() {
			@Override
			public void configure(WebSocketServletFactory factory) {
				factory.setCreator((req, resp) -> new WebSocketListener() {
					AtomicLong counter = new AtomicLong();

					@Override
					public void onWebSocketBinary(byte[] payload, int offset, int len) {
					}

					@Override
					public void onWebSocketClose(int statusCode, String reason) {
					}

					@Override
					public void onWebSocketConnect(final Session session) {
						LOG.info("Connected a websocket client: {}", session);

						// Keep track of a rolling average
						final AtomicReference<Float> avg = new AtomicReference<>(0f);

						serverReactor.on(tradeExecute, (Event<Trade> ev) -> {
							Trade t = ev.getData();
							avg.set((avg.get() + t.getPrice()) / 2);

							// Send a message every 1000th trade.
							// Otherwise, we completely overwhelm the browser and network.
							if (counter.incrementAndGet() % 1000 == 0) {
								try {
									session.getRemote().sendString(String.format("avg: %s", avg.get()));
								} catch (IOException e) {
									if (!"Failed to write bytes".equals(e.getMessage())) {
										e.printStackTrace();
									}
								}
							}
						});
					}

					@Override
					public void onWebSocketError(Throwable cause) {
					}

					@Override
					public void onWebSocketText(String message) {
					}
				});
			}
		};
		serve(wss);

		LOG.info("Connect websocket clients now (waiting for 10 seconds).\n"
				         + "Open websocket/src/main/webapp/ws.html in a browser...");
		Thread.sleep(10000);

		// Start a throughput timer
		startTimer();

		// Publish one event per trade
		for (int i = 0; i < totalTrades; i++) {
			// Pull next randomly-generated Trade from server
			Trade t = server.nextTrade();

			// Notify the Reactor the event is ready to be handled
			serverReactor.notify(tradeExecute.getObject(), Event.wrap(t));
		}

		// Stop throughput timer and output metrics
		endTimer();

		server.stop();

	}

	private static void startTimer() {
		LOG.info("Starting throughput test with {} trades...", totalTrades);
		latch = new CountDownLatch(totalTrades);
		startTime = System.currentTimeMillis();
	}

	private static void endTimer() throws InterruptedException {
		latch.await(30, TimeUnit.SECONDS);
		long endTime = System.currentTimeMillis();
		double elapsed = endTime - startTime;
		double throughput = totalTrades / (elapsed / 1000);

		LOG.info("Executed {} trades/sec in {}ms", (int) throughput, (int) elapsed);
	}

	private static void serve(HttpServlet servlet) throws Exception {
		ServletHandler handler = new ServletHandler();
		handler.addServletWithMapping(new ServletHolder(servlet), "/");

		HttpConfiguration httpConfig = new HttpConfiguration();
		httpConfig.setOutputBufferSize(32 * 1024);
		httpConfig.setRequestHeaderSize(8 * 1024);
		httpConfig.setResponseHeaderSize(8 * 1024);
		httpConfig.setSendDateHeader(true);

		HttpConnectionFactory connFac = new HttpConnectionFactory(httpConfig);

		Server server = new Server(3000);

		ServerConnector connector = new ServerConnector(
				server,
				Executors.newFixedThreadPool(4),
				new ScheduledExecutorScheduler(),
				new MappedByteBufferPool(),
				1,
				4,
				connFac
		);
		connector.setAcceptQueueSize(1000);
		connector.setReuseAddress(true);

		server.setHandler(handler);
		server.start();
	}

}
