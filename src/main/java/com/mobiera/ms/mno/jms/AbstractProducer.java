package com.mobiera.ms.mno.jms;

import java.util.HashMap;
import java.util.Map;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSProducer;
import jakarta.jms.Session;

import org.graalvm.collections.Pair;
import org.jboss.logging.Logger;


public abstract class AbstractProducer {
	
	private Integer producerId = 0;
	private Integer producerCount = 8;
	
    private Map<Integer,JMSProducer> producers = new HashMap<Integer,JMSProducer>();
    private Map<Integer,JMSContext> contexts = new HashMap<Integer,JMSContext>();
    
	private static final Logger logger = Logger.getLogger(AbstractProducer.class);

	private Object contextLockObj = new Object();
    
	

	
	
	protected Pair<Integer, Pair<JMSContext, JMSProducer>> getProducer(ConnectionFactory connectionFactory, boolean debug) {
    	JMSProducer producer = null;
    	JMSContext context = null;
    	int id = 0;
    	
    	synchronized (contextLockObj) {
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
			if (producerId == producerCount) {
				producerId = 0;
			}
		}
    	return Pair.create(id, Pair.create(context, producer));
    }
    
    protected void purgeAllProducers() {
    	
    	synchronized (contextLockObj) {
    		
    		for (int id=0; id<contexts.size(); id++) {
    			JMSContext context = contexts.get(id);
        		if (context != null) {
        			try {
        	   		 	context.close();
        	   		 	logger.info("purgeAllProducers: closed producer #" + id);
     		         } catch (Exception e1) {
     		         	logger.error("purgeProducer: error closing producer #" + id, e1);
     		         }
        		}
        		contexts.remove(id);
        		producers.remove(id);
    		}
    		
    		logger.info("purgeAllProducers: remaining contexts size: " + contexts.size() + " producer size: " + producers.size());

    		contexts.clear();
    		producers.clear();
    		
    		logger.info("purgeAllProducers: cleared contexts size: " + contexts.size() + " producer size: " + producers.size());
    		
    	}
    }

	public void setProducerCount(Integer producerCount) {
		this.producerCount = producerCount;
	}
}
