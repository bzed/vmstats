package org.timconrad.vmstats;
/*
 * Copyright 2012 Tim Conrad - tim@timconrad.org
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.concurrent.BlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vmware.vim25.PerfEntityMetric;
import com.vmware.vim25.PerfMetricIntSeries;
import com.vmware.vim25.PerfMetricSeries;
import com.vmware.vim25.PerfSampleInfo;
import com.vmware.vim25.PerfMetricId;
import com.vmware.vim25.PerfProviderSummary;
import com.vmware.vim25.PerfEntityMetricBase;
import com.vmware.vim25.PerfQuerySpec;
import com.vmware.vim25.RuntimeFault;
import com.vmware.vim25.mo.ManagedEntity;
import com.vmware.vim25.mo.PerformanceManager;
// this is the consumer in the arrangement
// this goes and gets the stats for a particular VM

class statsGrabber implements Runnable {

	private final BlockingQueue<ManagedEntity> mob_queue;
	private final BlockingQueue<String[]> sender;
	private final PerformanceManager perfMgr;
	private final Hashtable<String, Hashtable<String,String>> perfKeys;
	private final Hashtable<String, String> appConfig;
	private String mobType = "bob";
    private volatile boolean cancelled;
	
	private static final Logger logger = LoggerFactory.getLogger(statsGrabber.class);
	
	public statsGrabber(PerformanceManager perfMgr, Hashtable<String, Hashtable<String, String>> perfKeys,
			BlockingQueue<ManagedEntity> mob_queue, BlockingQueue<String[]> sender, Hashtable<String, String> appConfig, String mobType) {
		this.mob_queue = mob_queue;
		this.sender = sender;
		this.perfMgr = perfMgr;
		this.perfKeys = perfKeys;
		this.appConfig = appConfig;
		this.mobType = mobType;
	}	
	
	private String[] getStats(ManagedEntity vm) {
		final ArrayList<String> temp_results = new ArrayList<String>();
		final String TAG_NS = appConfig.get("graphiteTag") + "." + appConfig.get("vcsTag");
		
		PerfProviderSummary pps;
		try {
			// TODO - maek dis smrtr
			// this is a mess, and probably expensive to do. some sort of caching mechanism would 
			// probably be better. but should probably be per-thread to avoid blocking issues with a
			// shared cache
			
			String vmName = vm.getName();
			String[] vmNameParts = vmName.split("[.]");
			String vmNameShort = vmNameParts[0];
			
			pps = this.perfMgr.queryPerfProviderSummary(vm);
			// for VM's, this is likely always 20 seconds in this context.
			int refreshRate = pps.getRefreshRate().intValue();
			PerfMetricId[] pmis = this.perfMgr.queryAvailablePerfMetric(vm, null, null, refreshRate);
			int perfEntries = 1;
			PerfQuerySpec qSpec = createPerfQuerySpec(vm, pmis, perfEntries, refreshRate);
			// pValues always returns perfEntries results. this code hasn't been tested grabbing more than 1
			// stat at a time.
			PerfEntityMetricBase[] pValues = perfMgr.queryPerf(new PerfQuerySpec[] {qSpec});
			
			if(pValues != null) {
                for (PerfEntityMetricBase pValue : pValues) {
                    PerfEntityMetric pem = (PerfEntityMetric) pValue;
                    PerfMetricSeries[] vals = pem.getValue();
                    PerfSampleInfo[] infos = pem.getSampleInfo();
                    // FIXME: just using the first record here, probably not the best thing in the world.
                    long timestamp = infos[0].getTimestamp().getTimeInMillis() / 1000;

                    for (int x = 0; vals != null && x < vals.length; x++) {
                        int counterId = vals[x].getId().getCounterId();
                        // create strings for the parts of the tag.
                        String key = perfKeys.get("" + counterId).get("key");
                        String instance = vals[x].getId().getInstance();
                        // disks will be naa.12341234, change them to naa_12341234 instead
                        instance = instance.replace(".", "_");
                        instance = instance.replace("/", ".");
                        // the 'none' rollup type is completely legitmate - it doesn't roll up, so it's live data
                        String rollup = perfKeys.get("" + counterId).get("rollup");

                        String tag;
                        if (instance.equals("")) {
                            // no instance, no period required
                            tag = TAG_NS + "." + mobType + "." + vmNameShort + "." + key + "." + rollup;
                        } else {
                            tag = TAG_NS + "." + mobType + "." + vmNameShort + "." + key + "." + instance + "." + rollup;
                        }
                        // tag should be vmstats.VMTAG.hostname.cpu.whatever.whatever at this point

                        long stat = 0;
                        // this is a bit redundant, since we're only getting 1 stat at a time
                        // however, could allow us to get more stats with a single pass.
                        // TODO: Stuff here to make it so multiple sets of data could be retrieved simultaneously
                        if (vals[x] instanceof PerfMetricIntSeries) {
                            PerfMetricIntSeries val = (PerfMetricIntSeries) vals[x];
                            long[] longs = val.getValue();
                            for (int c = 0; c < longs.length; c++) {
                                // stat is just going to stay whatever the last one is/was
                                stat = longs[c];
                            }
                        }
                        // create the final string here
                        String graphiteData = tag + " " + stat + " " + timestamp + "\n";
                        // enabling this is VERY verbose, but often simpler to stop the graphiteWriter thread
                        // and enable this.
                        // logger.debug("graphiteData: " + graphiteData);
                        temp_results.add(graphiteData);

                    }
                }
				
			}
			
		} catch (RuntimeFault e) {
			logger.info("statsGrabber: Thread: " + Thread.currentThread().getName() + " +  Interrupted: " + e.getMessage());
			e.printStackTrace();
            System.exit(100);
		} catch (RemoteException e) {
			logger.info("statsGrabber: Thread: " + Thread.currentThread().getName() + " +  Interrupted: " + e.getMessage());
			e.printStackTrace();
            System.exit(101);
		}
		return temp_results.toArray(new String[temp_results.size()]);

	}
	
	private static PerfQuerySpec createPerfQuerySpec(ManagedEntity me, PerfMetricId[] metricIds, int maxSample, int interval) {
		PerfQuerySpec qSpec = new PerfQuerySpec();
		qSpec.setEntity(me.getMOR());
		// set the maximum of metrics to be return
		// only appropriate in real-time performance collecting
		qSpec.setMaxSample(new Integer(maxSample));
		//    qSpec.setMetricId(metricIds);
		// optionally you can set format as "normal"
		qSpec.setFormat("normal");
		// set the interval to the refresh rate for the entity
		qSpec.setIntervalId(new Integer(interval));

		return qSpec;
	}

    public void cancel() {
        this.cancelled = true;
    }
	
	public void run() {
        String threadName = Thread.currentThread().getName();

        String fname = "debug-statsGrabber-" + threadName + ".log";
        BufferedWriter out = null;
        FileWriter fstream = null;
        try {
            fstream = new FileWriter(fname);
            out = new BufferedWriter(fstream);
        }catch (Exception e) {
            System.out.println("file open error");
            System.exit(-1);
        }
		try {
			while(!cancelled) {
				// take item from BlockingQueue
				ManagedEntity vm = this.mob_queue.take();
				// run the getStats function on the vm
				String[] stats = this.getStats(vm);
				// take the output from the getStats function and send to graphite.
				sender.put(stats);
                for(int x = 0; x < stats.length; x++) {
                    out.write(stats[x]);
                }
                out.flush();
			}
			
		} catch(InterruptedException e) {
			e.getStackTrace();
			logger.info("statsGrabber: Thread: " + Thread.currentThread().getName() + " +  Interrupted: " + e.getMessage());
			Thread.currentThread().interrupt();
            System.exit(102);
		} catch(Exception e) {
			e.getStackTrace();
			logger.info("statsGrabber: Thread: " + Thread.currentThread().getName() + " +  Interrupted: " + e.getMessage());
            System.exit(103);
		}
		
	}
}
