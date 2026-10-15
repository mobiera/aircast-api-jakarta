package com.mobiera.ms.mno.jms;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSProducer;
import jakarta.jms.Session;

import org.graalvm.collections.Pair;
import org.jboss.logging.Logger;

/**
 * Round-robin pool of {@link JMSContext}/{@link JMSProducer} pairs shared by the
 * producers of a service. The pool never holds more than {@code producerCount}
 * contexts: each context is one broker connection, so the pool size bounds the
 * number of broker connections a producer can open.
 */
public abstract class AbstractProducer {

	private static final int DEFAULT_PRODUCER_COUNT = 8;

	/*
	 * Plain ints on purpose: with boxed Integers the wrap-around check
	 * (producerId == producerCount) compared references and silently stopped
	 * working above the Integer cache (>= 128), leaking one broker connection
	 * per spool().
	 */
	private int producerId = 0;
	private int producerCount = DEFAULT_PRODUCER_COUNT;

	private final Map<Integer, JMSProducer> producers = new HashMap<Integer, JMSProducer>();
	private final Map<Integer, JMSContext> contexts = new HashMap<Integer, JMSContext>();

	private static final Logger logger = Logger.getLogger(AbstractProducer.class);

	private final Object contextLockObj = new Object();

	protected Pair<Integer, Pair<JMSContext, JMSProducer>> getProducer(ConnectionFactory connectionFactory, boolean debug) {
		JMSProducer producer = null;
		JMSContext context = null;
		int id = 0;

		synchronized (contextLockObj) {
			if (producerId >= producerCount) {
				producerId = 0;
			}
			if (debug) {
				logger.info("spool: with use contexts/producer #" + producerId);
			}
			context = contexts.get(producerId);
			if (context == null) {
				context = connectionFactory.createContext(Session.CLIENT_ACKNOWLEDGE);
				contexts.put(producerId, context);
			}

			producer = producers.get(producerId);
			if (producer == null) {
				producer = context.createProducer();
				producers.put(producerId, producer);
			}
			id = producerId;
			producerId++;
			if (producerId >= producerCount) {
				producerId = 0;
			}
		}
		return Pair.create(id, Pair.create(context, producer));
	}

	/**
	 * Closes every pooled context and resets the pool, so the next
	 * {@link #getProducer} starts again from producer #0 with fresh connections.
	 */
	protected void purgeAllProducers() {
		synchronized (contextLockObj) {
			List<Map.Entry<Integer, JMSContext>> toClose = new ArrayList<Map.Entry<Integer, JMSContext>>(contexts.entrySet());
			int closed = closeAll(toClose);
			contexts.clear();
			producers.clear();
			producerId = 0;
			logger.info("purgeAllProducers: closed " + closed + "/" + toClose.size() + " producer(s), pool reset");
		}
	}

	/**
	 * Sets the pool size. Must be called before the first {@link #getProducer};
	 * when called later, surplus contexts (id >= producerCount) are closed.
	 */
	public void setProducerCount(Integer producerCount) {
		int count = (producerCount == null || producerCount < 1) ? DEFAULT_PRODUCER_COUNT : producerCount;
		synchronized (contextLockObj) {
			this.producerCount = count;
			if (producerId >= count) {
				producerId = 0;
			}
			List<Map.Entry<Integer, JMSContext>> surplus = new ArrayList<Map.Entry<Integer, JMSContext>>();
			for (Map.Entry<Integer, JMSContext> e : contexts.entrySet()) {
				if (e.getKey() >= count) {
					surplus.add(e);
				}
			}
			if (!surplus.isEmpty()) {
				int closed = closeAll(surplus);
				for (Map.Entry<Integer, JMSContext> e : surplus) {
					contexts.remove(e.getKey());
					producers.remove(e.getKey());
				}
				logger.info("setProducerCount: pool shrunk to " + count + ", closed " + closed + "/" + surplus.size() + " surplus producer(s)");
			}
		}
	}

	public int getProducerCount() {
		synchronized (contextLockObj) {
			return producerCount;
		}
	}

	/** Number of contexts (broker connections) currently held by the pool. */
	public int getOpenContextCount() {
		synchronized (contextLockObj) {
			return contexts.size();
		}
	}

	private int closeAll(List<Map.Entry<Integer, JMSContext>> entries) {
		int closed = 0;
		for (Map.Entry<Integer, JMSContext> e : entries) {
			try {
				e.getValue().close();
				closed++;
			} catch (Exception e1) {
				logger.error("purgeProducer: error closing producer #" + e.getKey(), e1);
			}
		}
		return closed;
	}
}
