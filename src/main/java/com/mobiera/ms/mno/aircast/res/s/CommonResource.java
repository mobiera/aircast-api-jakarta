package com.mobiera.ms.mno.aircast.res.s;

import java.util.concurrent.Semaphore;

public class CommonResource {
	
	private static Semaphore semaphore = null;
	
	
	
	
	protected static Semaphore getSemaphore(int maxThreads) {
		if (semaphore == null) {
	    	  semaphore = new Semaphore(maxThreads, true);
	      }
		return semaphore;
		
	}
	
}
