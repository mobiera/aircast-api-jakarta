package com.mobiera.ms.mno.jms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSProducer;

import org.graalvm.collections.Pair;
import org.junit.Test;

/**
 * The pool must never hold more contexts than its configured size, whatever
 * the size: each context is a broker connection.
 */
public class AbstractProducerTest {

	private final AtomicInteger created = new AtomicInteger();
	private final AtomicInteger closed = new AtomicInteger();

	private final ConnectionFactory factory = stub(ConnectionFactory.class, (proxy, method, args) -> {
		if (method.getName().equals("createContext")) {
			created.incrementAndGet();
			return stub(JMSContext.class, (p, m, a) -> {
				if (m.getName().equals("createProducer")) {
					return stub(JMSProducer.class, null);
				}
				if (m.getName().equals("close")) {
					closed.incrementAndGet();
				}
				return null;
			});
		}
		return null;
	});

	private final AbstractProducer pool = new AbstractProducer() {
	};

	@Test
	public void poolIsBoundedAboveTheIntegerCache() {
		pool.setProducerCount(256);
		Set<Integer> ids = new HashSet<Integer>();
		for (int i = 0; i < 10000; i++) {
			Pair<Integer, Pair<JMSContext, JMSProducer>> p = pool.getProducer(factory, false);
			ids.add(p.getLeft());
			assertTrue("id out of range: " + p.getLeft(), p.getLeft() >= 0 && p.getLeft() < 256);
		}
		assertEquals(256, created.get());
		assertEquals(256, pool.getOpenContextCount());
		assertEquals(256, ids.size());
	}

	@Test
	public void poolIsBoundedWithDefaultSize() {
		for (int i = 0; i < 100; i++) {
			pool.getProducer(factory, false);
		}
		assertEquals(8, created.get());
		assertEquals(8, pool.getOpenContextCount());
	}

	@Test
	public void purgeClosesEveryContextAndRestartsFromZero() {
		pool.setProducerCount(200);
		for (int i = 0; i < 200; i++) {
			pool.getProducer(factory, false);
		}
		assertEquals(200, pool.getOpenContextCount());

		pool.purgeAllProducers();

		assertEquals(200, closed.get());
		assertEquals(0, pool.getOpenContextCount());
		assertEquals(Integer.valueOf(0), pool.getProducer(factory, false).getLeft());
		assertEquals(201, created.get());
	}

	@Test
	public void shrinkingThePoolClosesSurplusContexts() {
		pool.setProducerCount(16);
		for (int i = 0; i < 16; i++) {
			pool.getProducer(factory, false);
		}
		pool.setProducerCount(4);
		assertEquals(12, closed.get());
		assertEquals(4, pool.getOpenContextCount());
		for (int i = 0; i < 100; i++) {
			assertTrue(pool.getProducer(factory, false).getLeft() < 4);
		}
		assertEquals(16, created.get());
	}

	@Test
	public void invalidSizeFallsBackToDefault() {
		pool.setProducerCount(0);
		assertEquals(8, pool.getProducerCount());
		pool.setProducerCount(null);
		assertEquals(8, pool.getProducerCount());
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, InvocationHandler handler) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			if (method.getDeclaringClass() == Object.class) {
				return objectMethod(proxy, method, args);
			}
			return handler == null ? null : handler.invoke(proxy, method, args);
		});
	}

	private static Object objectMethod(Object proxy, Method method, Object[] args) {
		switch (method.getName()) {
		case "hashCode":
			return System.identityHashCode(proxy);
		case "equals":
			return proxy == args[0];
		default:
			return "stub";
		}
	}
}
