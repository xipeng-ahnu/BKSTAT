package xipeng.statcontroller;
import java.lang.Thread.State;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.io.FileWriter;
import java.io.IOException;

import org.projectfloodlight.openflow.protocol.OFAggregateStatsReply;
import org.projectfloodlight.openflow.protocol.OFFlowStatsEntry;
import org.projectfloodlight.openflow.protocol.OFFlowStatsReply;
import org.projectfloodlight.openflow.protocol.OFStatsReply;
import org.projectfloodlight.openflow.protocol.OFStatsRequest;
import org.projectfloodlight.openflow.protocol.OFStatsType;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.ver13.OFMeterSerializerVer13;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFGroup;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.U16;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.primitives.UnsignedLong;
import com.google.common.util.concurrent.ListenableFuture;

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.threadpool.IThreadPoolService;

import xipeng.statcontroller.FlowStatClient;
import xipeng.statcontroller.FlowStatServer;

//edit by xipeng 20180316
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.exceptions.JedisConnectionException;

public class FlowStatCollector implements IFlowStatCollectionService, IFloodlightModule {

	private static final Logger log = LoggerFactory.getLogger(FlowStatCollector.class);

	private static IOFSwitchService switchService;
	private static IThreadPoolService threadPoolService;
	private static boolean isEnabled = false;
		
	private static int flowStatsInterval = 10; /* could be set by REST API, so not final */
	private static ScheduledFuture<?> flowStatsCollector;
	
	private static final String INTERVAL_FLOW_STATS_STR = "collectionIntervalFlowStatsSeconds";
	private static final String ENABLED_STR = "enable";
	private static final String CONTROLLER_ROLE = "controllerRole";
	private static final String MASTER_IP = "masterIP";
	private static final String MASTER_PORT = "masterMsgPort";
	private static final String MASTER_DO_STAT = "isMasterDoFlowStat";
	
	//edit by xipeng 20180316
	private static final String DATA_EXCHANGE = "dataExchange";
	private static final String REDIS_SERVER_IP = "redisServerIP";
	private static final String REDIS_SERVER_PORT = "redisServerPort";
	private static final String REDIS_SERVER_PASSWORD = "redisServerPassword";
	
	private static FileWriter logFile = null;
	private static FileWriter statLogFile = null;
	private static long statRound = 0;
	
	/*
	 * edit by xipeng, add east-west interface support for master and slave controller
	 * if controllerRole = 2, master controller just start a server to receive messages from slave controller
	 * if controllerRole = 3, slave controller receive stat messages and send to master
	 * 
	 * when the master startup, the message server must startup
	 * when the slave startup, if the connection to master controller is not connected, en error will report about master controller failure
	 * this makes a convenient way to discover master controller failure
	 */
	private static int controllerRole = 2; // 1 means EQUAL, 2 means MASTER, 3 means SLAVE

	private FlowStatClient fsClient = null;
	private FlowStatServer fsServer = null;
	
	private String masterIP = "127.0.0.1";
	private int masterPort = 6666;
	private boolean isMasterDoStat = false;
	
	//edit by xipeng 20180316
	private String dataExchange = "default";
	private String redisServerIP = "127.0.0.1";
	private int redisServerPort = 6379;
	private String redisPassword = "";
	
	private static Jedis redisClient = null;
	private static Pipeline redisPipeline = null;
		
	/*
	 * Periodically run flow statistics collection
	 * @see net.floodlightcontroller.core.module.IFloodlightModule#getModuleServices()
	 * This collector is for slave with default internal Flow Stat Data Exchange Type: default
	 */
	protected class FlowStatsCollector implements Runnable {
		
		private FileWriter fw;
		private FileWriter fw2;
		
		public FlowStatsCollector(FileWriter fw, FileWriter fw2) {
			this.fw = fw;
			this.fw2 = fw2;
		}
		
		@Override
		public void run() {
			//edit by xipeng 20171225
			try {
				statRound++;
				//this.fw.write("====== Round "+statRound+" Results: ======\r\n");
				this.fw2.write("====== Round "+statRound+" Reply Time: ======\r\n");
			}catch(Exception exx){
				log.info("Some thing error occured in this round stats");
			}
			Map<DatapathId, List<OFStatsReply>> replies = getSwitchStatistics(switchService.getAllSwitchDpids(), OFStatsType.FLOW);
			//xipeng edit ends here
			// the code below simulate a 165 byte stat reply for a single flow
//			StringBuffer sb = new StringBuffer("");
//			for(int t=1; t < 165; t++) {
//				sb.append("1");
//			}
//			String temp = sb.toString();
			
			for (Entry<DatapathId, List<OFStatsReply>> e : replies.entrySet()) { //each switch
				log.info("======Round {} Flow Stats for DPid {} ======",statRound, e.getKey().toString());
				String dpid = e.getKey().toString();
				// for each reply packet
				int flowCount = 0;
				int flowPkt = 0;
				for (OFStatsReply r : e.getValue()) {
					OFFlowStatsReply fsr = (OFFlowStatsReply) r;
					flowPkt ++;
					// for each flow contains in a reply packet	
					for (OFFlowStatsEntry fse : fsr.getEntries()) {
						//display content of the flow stat msg
						flowCount++;
						TableId tid = fse.getTableId();
						long durasec = fse.getDurationSec();
						long duransec = fse.getDurationNsec();
						int priority = fse.getPriority();
					    int idleTimeout = fse.getIdleTimeout();
					    int hardTimeout = fse.getHardTimeout();
					    long cookie = fse.getCookie().getValue();
					    long pktCount = fse.getPacketCount().getValue();
					    long byteCount = fse.getByteCount().getValue();
					    String match = fse.getMatch().toString();
					    String inst = null;
					    try {
					    	inst = fse.getInstructions().toString();
//					    	this.fw.write("DPid-"+dpid+" Pkt-"+flowPkt+" Flow-"+flowCount+": tableid-"+tid+", "
//						    		+ "durationSec-"+durasec+", durationNsec-"+duransec+", priority-"+priority
//						    		+", idleTimeout-"+idleTimeout+", hardTimeout-"+hardTimeout+", cookie-"+cookie
//						    		+", pktCount-"+pktCount+", byteCount-"+byteCount+", match-"+match
//						    		+", instruction-"+inst+"\r\n");
//					    }catch(IOException ex2) {
//					    	log.info("Can not write log to log file");
					    }catch(Exception e1) {
					    	log.info("instruction field is not support by this version of openflow!");
					    }
//					    String temp = "DPid-"+dpid+"Pkt-"+flowPkt+" Flow-"+flowCount+": tableid-"+tid+", "
//					    		+ "durationSec-"+durasec+", durationNsec-"+duransec+", priority-"+priority
//					    		+", idleTimeout-"+idleTimeout+", hardTimeout-"+hardTimeout+", cookie-"+cookie
//					    		+", pktCount-"+pktCount+", byteCount-"+byteCount+", match-"+match
//					    		+", instruction-"+inst+"\n";
					    
					    String temp = "DP-"+dpid+" PR-"+priority+" MA-"+match+" PC-"+pktCount+" BC-"+byteCount;
					    
					    // elephant flow suppose to be 20%, so we report 20% flows to the master controller
						//if (FlowStatCollector.this.fsClient.isConnected() && (flowCount % 10 < 2)) {
						if (flowCount % 10 < 2) {
							FlowStatCollector.this.fsClient.sendMsg(temp);
						}
						
					    
//					    log.info("Pkt-"+flowPkt+" Flow-"+flowCount+": tableid-"+tid+", "
//					    		+ "durationSec-"+durasec+", durationNsec-"+duransec+", priority-"+priority
//					    		+", idleTimeout-"+idleTimeout+", hardTimeout-"+hardTimeout+", cookie-"+cookie
//					    		+", pktCount-"+pktCount+", byteCount-"+byteCount+", match-"+match
//					    		+", instruction-"+inst);
					    
						
					}
				}
				// a switch flow stats ends here
				log.info("Total count is {}", flowCount);
				try {
					this.fw2.write("****Dpid:"+dpid+" get flows :"+flowCount+"****\r\n");
				}catch(Exception eee) {
					
				}
			}
			try {
				//this.fw.flush();
				this.fw2.flush();
			}catch(Exception exx){
				log.info("Some thing error occured in this round stats");
			}
		}
	}
	
	/*
	 * Periodically run flow statistics collection
	 * @see net.floodlightcontroller.core.module.IFloodlightModule#getModuleServices()
	 * This collector is for slave with Flow Stat Data Exchange Type: Redis
	 * Using Redis no need to understand where the redis server is or how redis deployed, 
	 * Just write into it
	 */
	protected class FlowStatsCollectorRedis implements Runnable {
		
		private FileWriter fw;
		private FileWriter fw2;		
		
		public FlowStatsCollectorRedis(FileWriter fw, FileWriter fw2) {
			this.fw = fw;
			this.fw2 = fw2;
		}
		
		@Override
		public void run() {
			//edit by xipeng 20171225
			int flowCount;
			int flowPkt;
			OFFlowStatsReply fsr;
			TableId tid;
			long durasec;
			long duransec;
			int priority;
			int idleTimeout;
		    int hardTimeout;
		    long cookie;
		    long pktCount;
		    long byteCount;
		    Match m;
		    OFPort inport;
		    int in_port;
		    MacAddress ethdst;
		    long eth_dst;
		    MacAddress ethsrc;
		    long eth_src;
		    EthType ethtype;
		    int ip_dst;
		    int ip_src;
		    int eth_type = -1; // ip-2048, arp-2054
		    IPv4Address ipdst;
		    IPv4Address ipsrc;
		    IpProtocol ipproto;
		    int ip_proto = -1; // tcp-6, udp-17
		    TransportPort tcpdst;
		    TransportPort tcpsrc;
		    int tcp_dst = -1;
		    int tcp_src = -1;
		    U16 tcpflag;
		    int tcp_flag = -1;
		    TransportPort udpdst;
		    TransportPort udpsrc;
		    int udp_dst = -1;
		    int udp_src = -1;
		    long dpid = -1;
		    
		    String redisKey, redisValue, inst;
		    
		    long curTime = System.currentTimeMillis();;		    
		    
			try {
				statRound++;
				//this.fw.write("====== Round "+statRound+" Results: ======\r\n");
				this.fw2.write("====== Round "+statRound+" Reply Time: ======\r\n");
			}catch(Exception exx){
				log.info("Some thing error occured in this round stats");
			}
			Map<DatapathId, List<OFStatsReply>> replies = getSwitchStatistics(switchService.getAllSwitchDpids(), OFStatsType.FLOW);
			
			for (Entry<DatapathId, List<OFStatsReply>> e : replies.entrySet()) { //each switch
				log.info("======Round {} Flow Stats for DPid {} ======",statRound, e.getKey().toString());
				dpid = e.getKey().getLong();
				// for each reply packet
				flowCount = 0;
				flowPkt = 0;
				for (OFStatsReply r : e.getValue()) {
					fsr = (OFFlowStatsReply) r;
					flowPkt ++;
					// for each flow contains in a reply packet	
					for (OFFlowStatsEntry fse : fsr.getEntries()) {
						//display content of the flow stat msg
						flowCount++;
						redisKey = "";
						redisValue = "";
						tid = fse.getTableId();
						durasec = fse.getDurationSec();
						duransec = fse.getDurationNsec();
						priority = fse.getPriority();
					    idleTimeout = fse.getIdleTimeout();
					    hardTimeout = fse.getHardTimeout();
					    cookie = fse.getCookie().getValue();
					    pktCount = fse.getPacketCount().getValue();
					    byteCount = fse.getByteCount().getValue();
					    //String match = fse.getMatch().toString();
					    m = fse.getMatch();
					    inport = m.get(MatchField.IN_PORT);
					    in_port = inport != null ? inport.getPortNumber() : -1;
					    ethdst = m.get(MatchField.ETH_DST);
					    eth_dst = ethdst != null ? ethdst.getLong() : -1;
					    ethsrc = m.get(MatchField.ETH_SRC);
					    eth_src = ethsrc != null ? ethsrc.getLong() : -1;
					    ethtype = m.get(MatchField.ETH_TYPE);
					    ip_dst = -1;
					    ip_src = -1;
					    
					    
					    if(ethtype!=null) {
					    	eth_type = ethtype.getValue();					    	
					    	if(eth_type==2048) {//0x800 ip packet, 2048
					    		ipdst = m.get(MatchField.IPV4_DST);
					    		ip_dst = ipdst != null ? ipdst.getInt() : -1;					    		
					    		ipsrc = m.get(MatchField.IPV4_SRC);
					    		ip_src = ipsrc != null ? ipsrc.getInt() : -1;
					    		ipproto = m.get(MatchField.IP_PROTO);
					    		ip_proto = ipproto != null? ipproto.getIpProtocolNumber() : -1;
					    		if(ip_proto == 6) {//tcp packet
					    			tcpdst = m.get(MatchField.TCP_DST);
					    			tcp_dst = tcpdst != null ? tcpdst.getPort() : -1;
					    			tcpsrc = m.get(MatchField.TCP_SRC);
					    			tcp_src = tcpsrc != null ? tcpsrc.getPort() : -1;
					    			tcpflag = m.get(MatchField.OVS_TCP_FLAGS);
					    			tcp_flag = tcpflag != null ? tcpflag.getValue() : -1;
					    		}else if(ip_proto == 17) {//udp packet
					    			udpdst = m.get(MatchField.UDP_DST);
					    			udp_dst = udpdst != null ? udpdst.getPort() : -1;
					    			udpsrc = m.get(MatchField.UDP_SRC);
					    			udp_src = udpsrc != null ? udpsrc.getPort() : -1;
					    		}
					    	}else if(eth_type==2054) {//0x806 arp packet
					    		//not implement yet
					    	}				    	
					    }
					    
					    //create redisKey for each flow stat
					    
				    	if(dpid==-1) redisKey+="#-"; else redisKey+=Long.toHexString(dpid).toUpperCase()+"-";
					    redisKey+=Integer.toHexString(priority).toUpperCase()+"-";
					    if(in_port==-1) redisKey+="#-"; else redisKey+=String.valueOf(in_port)+"-";
					    if(eth_src==-1) redisKey+="#-"; else redisKey+=Long.toHexString(eth_src).toUpperCase()+"-";
					    if(eth_dst==-1) redisKey+="#-"; else redisKey+=Long.toHexString(eth_dst).toUpperCase()+"-";
					    if(eth_type==-1) redisKey+="#-"; else redisKey+=Integer.toHexString(eth_type).toUpperCase()+"-";
					    if(eth_type==2048) { // ip packet
					    	if(ip_src==-1) redisKey+="#-"; else redisKey+=Integer.toHexString(ip_src).toUpperCase()+"-";
					    	if(ip_dst==-1) redisKey+="#-"; else redisKey+=Integer.toHexString(ip_dst).toUpperCase()+"-";
					    	if(ip_proto==-1) redisKey+="#-"; else redisKey+=Integer.toHexString(ip_proto).toUpperCase()+"-";
					    	if(ip_proto==6) {
					    		if(tcp_src==-1) redisKey+="#-"; else redisKey+=Integer.toHexString(tcp_src).toUpperCase()+"-";
					    		if(tcp_dst==-1) redisKey+="#-"; else redisKey+=Integer.toHexString(tcp_dst).toUpperCase()+"-";
					    		/*
					    		 * TCP Flags 6 bit: URG-ACK-PSH-RST-SYN-FIN
					    		 * so, for example: syn:000010=2, rst|ack: 010100=20, 
					    		 * ack|fin: 010001=17, ack: 010000=16, syn|ack: 010010=18
					    		 */
					    		if(tcp_flag==-1) redisKey+="#"; else redisKey+=Integer.toHexString(tcp_flag).toUpperCase();
					    	}else if(ip_proto==17) {
					    		if(udp_src==-1) redisKey+="#-"; else redisKey+=Integer.toHexString(udp_src).toUpperCase()+"-";
					    		if(udp_dst==-1) redisKey+="#-"; else redisKey+=Integer.toHexString(udp_dst).toUpperCase()+"-";    		
					    	}
					    	
					    	redisKey+="-"+Long.toHexString(curTime).toUpperCase();
					    }else if(eth_type==2054) { // arp packet
					    	//not implement yet
					    	redisKey+=Long.toHexString(curTime).toUpperCase();
					    }
					    
					    //System.out.println(redisKey);
					    
					    // write to redis server
					    
					    /*
					     * i first use hashset for redis to store data
					     * a problem was found, for 3812 flows , only 1092 entries stored in redis
					     * so i comment the hashset storing code below and try simply key-value method
					     */
					    /*
					    redisPipeline.hset(redisKey, "tb", String.valueOf(tid.getValue()));
					    redisPipeline.hset(redisKey, "ds", String.valueOf(durasec));
					    redisPipeline.hset(redisKey, "dn", String.valueOf(duransec));
					    redisPipeline.hset(redisKey, "it", String.valueOf(idleTimeout));
					    redisPipeline.hset(redisKey, "ht", String.valueOf(hardTimeout));
					    redisPipeline.hset(redisKey, "ck", String.valueOf(cookie));
					    redisPipeline.hset(redisKey, "pc", String.valueOf(pktCount));
					    redisPipeline.hset(redisKey, "bc", String.valueOf(byteCount));
					    redisPipeline.expire(redisKey, 120);
					    */
					    
					    /*
					     * simply set key and value for a flow stat data
					     */
					    redisValue+=String.valueOf(tid.getValue()) + "-" + 
					    			String.valueOf(durasec) + "-" +
					    			String.valueOf(duransec) + "-" +
					    			String.valueOf(idleTimeout) + "-" +
					    			String.valueOf(hardTimeout) + "-" +
					    			String.valueOf(cookie) + "-" +
					    			String.valueOf(pktCount) + "-" +
					    			String.valueOf(byteCount);
					    
					    redisPipeline.set(redisKey, redisValue);
					    
					    //inst = null; // instructions
					    //try {
					    	//inst = fse.getInstructions().toString();
//					    	this.fw.write("DPid-"+dpid+" Pkt-"+flowPkt+" Flow-"+flowCount+": tableid-"+tid+", "
//						    		+ "durationSec-"+durasec+", durationNsec-"+duransec+", priority-"+priority
//						    		+", idleTimeout-"+idleTimeout+", hardTimeout-"+hardTimeout+", cookie-"+cookie
//						    		+", pktCount-"+pktCount+", byteCount-"+byteCount+", match-"+match
//						    		+", instruction-"+inst+"\r\n");
//					    }catch(IOException ex2) {
//					    	log.info("Can not write log to log file");
					    //}catch(Exception e1) {
					    	//log.info("instruction field is not support by this version of openflow!");
					    //}
//					    String temp = "DPid-"+dpid+"Pkt-"+flowPkt+" Flow-"+flowCount+": tableid-"+tid+", "
//					    		+ "durationSec-"+durasec+", durationNsec-"+duransec+", priority-"+priority
//					    		+", idleTimeout-"+idleTimeout+", hardTimeout-"+hardTimeout+", cookie-"+cookie
//					    		+", pktCount-"+pktCount+", byteCount-"+byteCount+", match-"+match
//					    		+", instruction-"+inst+"\n";
					    
					    //String temp = "DP-"+dpid+" PR-"+priority+" MA-"+match+" PC-"+pktCount+" BC-"+byteCount;
					    
					    // elephant flow suppose to be 20%, so we report 20% flows to the master controller
						//if (FlowStatCollector.this.fsClient.isConnected() && (flowCount % 10 < 2)) {
						//if (flowCount % 10 < 2) {
						//	FlowStatCollector.this.fsClient.sendMsg(temp);
						//}					    
//					    log.info("Pkt-"+flowPkt+" Flow-"+flowCount+": tableid-"+tid+", "
//					    		+ "durationSec-"+durasec+", durationNsec-"+duransec+", priority-"+priority
//					    		+", idleTimeout-"+idleTimeout+", hardTimeout-"+hardTimeout+", cookie-"+cookie
//					    		+", pktCount-"+pktCount+", byteCount-"+byteCount+", match-"+match
//					    		+", instruction-"+inst);				    
						
					}
					// flow stats from one packet ends here
					 // after treat one packet, sync redis server once for efficiency
					redisPipeline.sync();
				}
				// a switch flow stats ends here
				log.info("Total count is {}", flowCount);
				try {
					this.fw2.write("****Dpid:"+dpid+" get flows :"+flowCount+"****\r\n");
				}catch(Exception eee) {
					
				}
			}
			try {
				//this.fw.flush();
				this.fw2.flush();
			}catch(Exception exx){
				log.info("Some thing error occured in this round stats");
			}
		}
	}
	
	protected class FlowStatsCollectorForMaster implements Runnable {
		
		private FileWriter fw;
		private FileWriter fw2;
		
		public FlowStatsCollectorForMaster(FileWriter fw, FileWriter fw2) {
			this.fw = fw;
			this.fw2 = fw2;
		}
		
		@Override
		public void run() {
			//edit by xipeng 20171225
			try {
				statRound++;
				//this.fw.write("====== Round "+statRound+" Results: ======\r\n");
				this.fw2.write("====== Round "+statRound+" Reply Time: ======\r\n");
			}catch(Exception exx){
				log.info("Some thing error occured in this round stats");
			}
			Map<DatapathId, List<OFStatsReply>> replies = getSwitchStatistics(switchService.getAllSwitchDpids(), OFStatsType.FLOW);
			//xipeng edit ends here
			for (Entry<DatapathId, List<OFStatsReply>> e : replies.entrySet()) { //each switch
				//edit by xipeng 20180324
				//if(e.getKey().getLong() % 2 != 1) {
				//	continue;
				//}
				//edit ends here
				log.info("======Round {} Flow Stats for DPid {} ======",statRound, e.getKey().toString());
				String dpid = e.getKey().toString();
				
				// for each reply packet
				int flowCount = 0;
				int flowPkt = 0;
				for (OFStatsReply r : e.getValue()) {
					OFFlowStatsReply fsr = (OFFlowStatsReply) r;
					flowPkt ++;
					// for each flow contains in a reply packet	
					for (OFFlowStatsEntry fse : fsr.getEntries()) {
						//display content of the flow stat msg
						flowCount++;
						TableId tid = fse.getTableId();
						long durasec = fse.getDurationSec();
						long duransec = fse.getDurationNsec();
						int priority = fse.getPriority();
					    int idleTimeout = fse.getIdleTimeout();
					    int hardTimeout = fse.getHardTimeout();
					    long cookie = fse.getCookie().getValue();
					    long pktCount = fse.getPacketCount().getValue();
					    long byteCount = fse.getByteCount().getValue();
					    String match = fse.getMatch().toString();
					    
					    String inst = null;
					    try {
					    	inst = fse.getInstructions().toString();
//					    	this.fw.write("DPid-"+dpid+" Pkt-"+flowPkt+" Flow-"+flowCount+": tableid-"+tid+", "
//						    		+ "durationSec-"+durasec+", durationNsec-"+duransec+", priority-"+priority
//						    		+", idleTimeout-"+idleTimeout+", hardTimeout-"+hardTimeout+", cookie-"+cookie
//						    		+", pktCount-"+pktCount+", byteCount-"+byteCount+", match-"+match
//						    		+", instruction-"+inst+"\r\n");
//					    	this.fw.flush();
//					    }catch(IOException ex2) {
//					    	log.info("Can not write log to log file");
					    }catch(Exception e1) {
					    	log.info("instruction field is not support by this version of openflow!");
					    }
					    
//					    log.info("Pkt-"+flowPkt+" Flow-"+flowCount+": tableid-"+tid+", "
//					    		+ "durationSec-"+durasec+", durationNsec-"+duransec+", priority-"+priority
//					    		+", idleTimeout-"+idleTimeout+", hardTimeout-"+hardTimeout+", cookie-"+cookie
//					    		+", pktCount-"+pktCount+", byteCount-"+byteCount+", match-"+match
//					    		+", instruction-"+inst);
					    
						
					}
				}
				// a switch flow stats ends here
				log.info("Total count is {}", flowCount);
				try {
					this.fw2.write("****Dpid:"+dpid+" get flows :"+flowCount+"****\r\n");
				}catch(Exception eee) {
					
				}
			}
			try {
				this.fw.flush();
			}catch(Exception exx){
				log.info("Some thing error occured in this round stats");
			}
		}
	}
	
	
	protected class FlowStatsCollectorForMasterNOFSC implements Runnable {
		
		private FileWriter fw;
		private FileWriter fw2;
		
		public FlowStatsCollectorForMasterNOFSC(FileWriter fw, FileWriter fw2) {
			this.fw = fw;
			this.fw2 = fw2;
		}
		
		@Override
		public void run() {
			//edit by xipeng 20180318
			statRound++;
			log.info("======Round {} Flow Stats =========",statRound);			
		}
	}
	
	
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// TODO Auto-generated method stub
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IFlowStatCollectionService.class);
		return l;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// TODO Auto-generated method stub
		Map<Class<? extends IFloodlightService>, IFloodlightService> m =
				new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
		m.put(IFlowStatCollectionService.class, this);
		return m;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		// TODO Auto-generated method stub
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IOFSwitchService.class);
		l.add(IThreadPoolService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		// TODO Auto-generated method stub
		switchService = context.getServiceImpl(IOFSwitchService.class);
		threadPoolService = context.getServiceImpl(IThreadPoolService.class);

		Map<String, String> config = context.getConfigParams(this);
		if (config.containsKey(ENABLED_STR)) {
			try {
				isEnabled = Boolean.parseBoolean(config.get(ENABLED_STR).trim());
			} catch (Exception e) {
				log.error("Could not parse '{}'. Using default of {}", ENABLED_STR, isEnabled);
			}
		}
		log.info("Statistics collection {}", isEnabled ? "enabled" : "disabled");

		if (config.containsKey(INTERVAL_FLOW_STATS_STR)) {
			try {
				flowStatsInterval = Integer.parseInt(config.get(INTERVAL_FLOW_STATS_STR).trim());
			} catch (Exception e) {
				log.error("Could not parse '{}'. Using default of {}", INTERVAL_FLOW_STATS_STR, flowStatsInterval);
			}
		}
		log.info("Flow statistics collection interval set to {}s", flowStatsInterval);
		//edit by xipeng, add CONTROLLER_ROLE for stat controller
		if (config.containsKey(CONTROLLER_ROLE)) {
			try {
				String role = config.get(CONTROLLER_ROLE);
				if (role.equals("equal")) {
					controllerRole = 1;
				}else if (role.equals("master")) {
					controllerRole = 2;
				}else if (role.equals("slave")) {
					controllerRole = 3;
				}else {
					log.error("Controller role parameter in config file for xipeng.statcontroller error!");
					throw new Exception();
				}
			}catch(Exception e) {
				log.error("Could not parse '{}'. Using default of {}", CONTROLLER_ROLE, controllerRole);
			}		
		}
		if (config.containsKey(MASTER_IP)) {
			try {
				this.masterIP = config.get(MASTER_IP);
			}catch(Exception e) {
				log.error("Could not parse '{}'. Using default of {}", MASTER_IP, this.masterIP);
			}		
		}
		if (config.containsKey(MASTER_PORT)) {
			try {
				this.masterPort = Integer.parseInt(config.get(MASTER_PORT));
			}catch(Exception e) {
				log.error("Could not parse '{}'. Using default of {}", MASTER_PORT, this.masterPort);
			}		
		}
		if (config.containsKey(MASTER_DO_STAT)) {
			try {
				this.isMasterDoStat = config.get(MASTER_DO_STAT).toLowerCase().equals("yes")?true:false;
			}catch(Exception e) {
				log.error("Could not parse '{}'. Using default of {}", MASTER_DO_STAT, this.isMasterDoStat);
			}		
		}
		//edit ends here 20180118
		
		//edit by xipeng, add redis support for data exchange
		if (config.containsKey(DATA_EXCHANGE)) {
			try {
				this.dataExchange = config.get(DATA_EXCHANGE);
				// if redis
				if(this.dataExchange.toLowerCase().equals("redis")) {
					this.dataExchange = "redis";
					if(config.containsKey(REDIS_SERVER_IP)) {
						this.redisServerIP = config.get(REDIS_SERVER_IP);
					}
					if(config.containsKey(REDIS_SERVER_PORT)) {
						this.redisServerPort = Integer.parseInt(config.get(REDIS_SERVER_PORT));
					}
					if(config.containsKey(REDIS_SERVER_PASSWORD)) {
						this.redisPassword = config.get(REDIS_SERVER_PASSWORD);
						if(this.redisPassword.equals("none")) {
							this.redisPassword = "";
						}
					}
				}else {
					this.dataExchange = "default";
				}
			}catch(Exception e) {
				log.error("Could not parse '{}'. Using default value of {}", DATA_EXCHANGE, this.dataExchange);
			}		
		}
		//edit ends here 20180316
	}

	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		// TODO Auto-generated method stub
		if (isEnabled) {
			startStatisticsCollection();
		}
	}
	
	
	/**
	 * Start all stats threads.
	 */
	private void startStatisticsCollection() {
		try{
			logFile = new FileWriter("D:/flowStatReplyData.log");
			statLogFile = new FileWriter("D:/flowStatLog.log");
		}catch(Exception ex1) {
			log.info("Can not open file D:/flowStatLog.log!");
			return;
		}
		if(controllerRole == 3) {
			// slave controller
			// if data exchange using default
			if(this.dataExchange.equals("default")) {
				int counter = 100; //how many attempt times the stat client connecting to the stat server
				try {
					this.fsClient = new FlowStatClient(this.masterIP, this.masterPort, log, counter);
					new Thread(this.fsClient).start();
				}catch(Exception ee) {
					log.info("SLAVE stat controller start failed!");
					return;
				}
				
				int temp = 0;
				while(!this.fsClient.isConnected() && temp <= counter + 5) {
					temp++;
					try {
						Thread.sleep(100);
					}catch(Exception exxx) {
						
					}
				}
				if(this.fsClient.isConnected()) {
					flowStatsCollector = threadPoolService.getScheduledExecutor().scheduleAtFixedRate(new FlowStatsCollector(logFile, statLogFile), flowStatsInterval, flowStatsInterval, TimeUnit.SECONDS);
					log.info("Slave Controller Statistics collection thread(s) started");
				}else {
					log.info("Slave Controller Can not connect to Master Controller, Exit Runing!");
					return;
				}
			}
			// if data exchange using redis
			if(this.dataExchange.equals("redis")) {
				// begin connection with redis server
				try {
					redisClient = new Jedis(this.redisServerIP, this.redisServerPort);
					if(!this.redisPassword.equals("")) {
						redisClient.auth(this.redisPassword);
					}				
				
					if(redisClient.ping().equals("PONG")) {
						log.info("Redis Server Connected!!");
					}
				}catch(JedisConnectionException jex) {
					log.info("Redis Server Connecting Failed!");
					return;
				}
				redisPipeline = redisClient.pipelined(); //use pipeline
				flowStatsCollector = threadPoolService.getScheduledExecutor().scheduleAtFixedRate(new FlowStatsCollectorRedis(logFile, statLogFile), flowStatsInterval, flowStatsInterval, TimeUnit.SECONDS);
				log.info("Slave Controller Statistics collection thread(s) started");
			}			
		}else if(controllerRole == 1 || controllerRole == 2) {
			// equal controller or master controller
			// if data exchange using default, it means master need receive data from slave directly
			if(this.dataExchange.equals("default")) {
				try {
					this.fsServer = new FlowStatServer(this.masterPort, log);
					new Thread(this.fsServer).start();
					log.warn("Statistics master controller start!");
				}catch(Exception ed) {
					log.warn("MASTER stat controller start failed!");
					return;
				}
			}
			// if data exchange using redis, master controller has its own choice
			if(this.dataExchange.equals("redis")) {
				// what master controller wanted to do with redis server data will be append in this brace
				// LEFT BLANK HERE FOR FUTURE USE
				
				
			}
			// Master not do this in our SCFSC scheme
			if(this.isMasterDoStat) {
				flowStatsCollector = threadPoolService.getScheduledExecutor().scheduleAtFixedRate(new FlowStatsCollectorForMaster(logFile, statLogFile), flowStatsInterval, flowStatsInterval, TimeUnit.SECONDS);
				log.warn("Master Controller Statistics collection thread(s) started");
			}else{
				// master still start to count times
                flowStatsCollector = threadPoolService.getScheduledExecutor().scheduleAtFixedRate(new FlowStatsCollectorForMasterNOFSC(logFile, statLogFile), flowStatsInterval, flowStatsInterval, TimeUnit.SECONDS);
			}
		}else {
			log.warn("Error: Controller Role now is: {}, must be 1(EQUAL), 2(MASTER) or 3(SLAVE)!");
			return;
		}
		/*
		try {
			logFile.flush();
			logFile.close();
			logFile = null;
		}catch(Exception ex2) {
			log.info("Can not close log file!");
		}
		*/
	}
	
	
	/**
	 * Stop all stats threads.
	 */
	private void stopStatisticsCollection() {
		if (!flowStatsCollector.cancel(false)) {
			log.error("Could not cancel flow stats thread");
		} else {
			log.warn("Statistics collection thread(s) stopped");
		}
	}
	

	@Override
	public List<OFFlowStatsReply> getFlowStatDesc(DatapathId dpid) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public OFAggregateStatsReply getAggrStatDesc(DatapathId dpid) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Map<String, Double> getStatTime(DatapathId dpid) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public synchronized void collectStatistics(boolean collect) {
		if (collect && !isEnabled) {
			startStatisticsCollection();
			isEnabled = true;
		} else if (!collect && isEnabled) {
			stopStatisticsCollection();
			isEnabled = false;
		} 
		/* otherwise, state is not changing; no-op */

	}
	
	
	private class GetStatisticsThread extends Thread {
		private List<OFStatsReply> statsReply;
		private DatapathId switchId;
		private OFStatsType statType;

		public GetStatisticsThread(DatapathId switchId, OFStatsType statType) {
			this.switchId = switchId;
			this.statType = statType;
			this.statsReply = null;
		}

		public List<OFStatsReply> getStatisticsReply() {
			return statsReply;
		}

		public DatapathId getSwitchId() {
			return switchId;
		}

		@Override
		public void run() {
			statsReply = getSwitchStatistics(switchId, statType);
		}
	}
	
	
	private Map<DatapathId, List<OFStatsReply>> getSwitchStatistics(Set<DatapathId> dpids, OFStatsType statsType) {		
		HashMap<DatapathId, List<OFStatsReply>> model = new HashMap<DatapathId, List<OFStatsReply>>();

		List<GetStatisticsThread> activeThreads = new ArrayList<GetStatisticsThread>(dpids.size());
		List<GetStatisticsThread> pendingRemovalThreads = new ArrayList<GetStatisticsThread>();
		GetStatisticsThread t;
		for (DatapathId d : dpids) {
			//edit by xipeng 20180324
			//if(d.getLong() % 2!=1) {
			//	continue;
			//}
			//edit ends here
			t = new GetStatisticsThread(d, statsType);
			activeThreads.add(t);
			t.start();
		}

		/* Join all the threads after the timeout. Set a hard timeout
		 * of 12 seconds for the threads to finish. If the thread has not
		 * finished the switch has not replied yet and therefore we won't
		 * add the switch's stats to the reply.
		 */
		for (int iSleepCycles = 0; iSleepCycles < flowStatsInterval; iSleepCycles++) {
			for (GetStatisticsThread curThread : activeThreads) {
				if (curThread.getState() == State.TERMINATED) {
					model.put(curThread.getSwitchId(), curThread.getStatisticsReply());
					pendingRemovalThreads.add(curThread);
				}
			}

			/* remove the threads that have completed the queries to the switches */
			for (GetStatisticsThread curThread : pendingRemovalThreads) {
				activeThreads.remove(curThread);
			}
			
			/* clear the list so we don't try to double remove them */
			pendingRemovalThreads.clear();

			/* if we are done finish early */
			if (activeThreads.isEmpty()) {
				break;
			}

			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				log.error("Interrupted while waiting for statistics", e);
			}
		}

		return model;
	}

	/**
	 * Get statistics from a switch.
	 * @param switchId
	 * @param statsType
	 * @return
	 */
	@SuppressWarnings("unchecked")
	protected List<OFStatsReply> getSwitchStatistics(DatapathId switchId, OFStatsType statsType) {
		IOFSwitch sw = switchService.getSwitch(switchId);
		ListenableFuture<?> future;
		List<OFStatsReply> values = null;
		Match match;
		Match h3cMatch;
		if (sw != null) {
			OFStatsRequest<?> req = null; //used for OVS
			OFStatsRequest<?> h3cReq = null; //used for H3C Switch
			switch (statsType) {
			case FLOW:
				match = sw.getOFFactory().buildMatch().build();
				h3cMatch = sw.getOFFactory().buildMatch().setExact(MatchField.ETH_TYPE, EthType.IPv4).build();
				req = sw.getOFFactory().buildFlowStatsRequest()
						.setMatch(match)
						.setOutPort(OFPort.ANY)
						.setOutGroup(OFGroup.ANY)
						.setTableId(TableId.ALL)
						.build();
				h3cReq = sw.getOFFactory().buildFlowStatsRequest()
						.setMatch(h3cMatch)
						.setOutPort(OFPort.ANY)
						.setOutGroup(OFGroup.ANY)
						.setTableId(TableId.ALL)
						.build();
				break;
			case AGGREGATE:
				match = sw.getOFFactory().buildMatch().build();
				req = sw.getOFFactory().buildAggregateStatsRequest()
						.setMatch(match)
						.setOutPort(OFPort.ANY)
						.setOutGroup(OFGroup.ANY)
						.setTableId(TableId.ALL)
						.build();
				break;
			case PORT:
				req = sw.getOFFactory().buildPortStatsRequest()
				.setPortNo(OFPort.ANY)
				.build();
				break;
			case QUEUE:
				req = sw.getOFFactory().buildQueueStatsRequest()
				.setPortNo(OFPort.ANY)
				.setQueueId(UnsignedLong.MAX_VALUE.longValue())
				.build();
				break;
			case DESC:
				req = sw.getOFFactory().buildDescStatsRequest()
				.build();
				break;
			case GROUP:
				if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_10) > 0) {
					req = sw.getOFFactory().buildGroupStatsRequest()				
							.build();
				}
				break;

			case METER:
				if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_13) >= 0) {
					req = sw.getOFFactory().buildMeterStatsRequest()
							.setMeterId(OFMeterSerializerVer13.ALL_VAL)
							.build();
				}
				break;

			case GROUP_DESC:			
				if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_10) > 0) {
					req = sw.getOFFactory().buildGroupDescStatsRequest()			
							.build();
				}
				break;

			case GROUP_FEATURES:
				if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_10) > 0) {
					req = sw.getOFFactory().buildGroupFeaturesStatsRequest()
							.build();
				}
				break;

			case METER_CONFIG:
				if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_13) >= 0) {
					req = sw.getOFFactory().buildMeterConfigStatsRequest()
							.build();
				}
				break;

			case METER_FEATURES:
				if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_13) >= 0) {
					req = sw.getOFFactory().buildMeterFeaturesStatsRequest()
							.build();
				}
				break;

			case TABLE:
				if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_10) > 0) {
					req = sw.getOFFactory().buildTableStatsRequest()
							.build();
				}
				break;

			case TABLE_FEATURES:	
				if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_10) > 0) {
					req = sw.getOFFactory().buildTableFeaturesStatsRequest()
							.build();		
				}
				break;
			case PORT_DESC:
				if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_13) >= 0) {
					req = sw.getOFFactory().buildPortDescStatsRequest()
							.build();
				}
				break;
			case EXPERIMENTER:		
			default:
				log.error("Stats Request Type {} not implemented yet", statsType.name());
				break;
			}

			try {
				/* if you are testing H3C switch,
				 * use h3cReq instead of req
				 * open vswitch use req
				 */
				if (req != null) {
					long statStartTime = System.nanoTime();
					future = sw.writeStatsRequest(req); 
					//values = (List<OFStatsReply>) future.get(flowStatsInterval*1000 / 2, TimeUnit.MILLISECONDS);
					values = (List<OFStatsReply>) future.get(flowStatsInterval*1000, TimeUnit.MILLISECONDS);
					//log.info(values.toString());
					long statEndTime = System.nanoTime();
					statLogFile.write("====== DPid "+switchId+" flow-stat-reply in "+((statEndTime - statStartTime) / 1000.0 / 1000.0)+" ms ======\r\n");
					//log.info("====== DPid {} flow-stat-reply in {} ms ======",switchId,(statEndTime - statStartTime) / 1000.0 / 1000.0);
					statLogFile.flush();
				}
			} catch (Exception e) {
				log.error("Failure retrieving statistics from switch {}. {}", sw, e);
			}
		}
		return values;
	}

}
