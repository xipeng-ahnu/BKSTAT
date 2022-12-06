package xipeng.statcontroller;

import org.slf4j.Logger;


import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.Queue;

public class FlowStatClient implements Runnable{
	private SocketChannel clientChan = null;
	private String serverIP = "127.0.0.1";
	private int serverPort = 6666;
	
	private String msgToSend = null;
	
	private static Logger log = null;
	
	private boolean stopReport = false;
	
	private int connectCount = 1000;
	
	private boolean isConnected = false;
	
	private static Queue<String> msgQueue = new LinkedList<String>();
	
	public boolean isConnected() {
		return isConnected;
	}

	public void setConnected(boolean isConnected) {
		this.isConnected = isConnected;
	}

	public boolean isStopReport() {
		return stopReport;
	}

	public void setStopReport(boolean stopReport) {
		this.stopReport = stopReport;
	}

	public FlowStatClient(String sIP, int port, Logger log, int connectCount) throws Exception {
		if (sIP!=null && !sIP.equals("")) {
			this.serverIP = sIP;
		}
		if (port > 1024 && port < 65535) {
			this.serverPort = port;
		}
		
		if(connectCount > 0) {
			this.connectCount = connectCount;
		}
		FlowStatClient.log = log;
		this.clientChan = SocketChannel.open();
		this.clientChan.configureBlocking(false); //non-blocking mode client
		//this.msgToSend = ByteBuffer.allocate(65535);
		
	}
	
//	public synchronized void sendMsg(String msg) {	
//		if (msg.equals("")) {
//			this.msgToSend=null;
//		}else {
//			if (this.msgToSend!=null) {
//				this.msgToSend+=msg;
//			}else {
//				this.msgToSend =msg;
//			}
//		}
//		
//	}
	
	public synchronized void sendMsg(String msg) {	
		if (msg==null || msg.equals("")) {
			return;
		}else {
			msgQueue.add(msg);
		}
		
	}
	
//	public String getMsg() {
//		return this.msgToSend;
//	}
	
//	@Override
//	public void run(){
//		try {
//			int counter = 0;
//			if (!this.clientChan.connect(new InetSocketAddress(this.serverIP, this.serverPort))){
//				
//				while (!this.clientChan.finishConnect()){
//					//System.out.print(".");
//					counter++;
//					if(counter > this.connectCount) {
//						log.info("Attempt to connect with master by {} times but fail at last!",counter);
//						this.clientChan.close();
//						return;
//					}
//					Thread.sleep(100);
//				}
//			}
//			log.info("Slave controller connected with Master controller at tcp://{} successfully!", this.serverIP+":"+this.serverPort);
//			this.setConnected(true);
//			String m = null;
//			ByteBuffer writeBuf = null;
//			while(!this.isStopReport()){
//				m = this.getMsg();
//				if(m==null||m.equals("")) {
//					Thread.sleep(100);
//					continue;
//				}
//				//clear msgToSend
//				this.sendMsg("");
//				
//				writeBuf = ByteBuffer.wrap(m.getBytes());
//				while(writeBuf.hasRemaining()) {
//					this.clientChan.write(writeBuf);
//				}
//				
//				Thread.sleep(20);
//			}
//			this.clientChan.close();
//			this.setConnected(false);
//		}catch(Exception ex) {
//			log.info("Error in running FlowStatClient for sending stat msgs from slave to master: {}",ex.getMessage());
//		}
//	}
	
	@Override
	public void run(){
		try {
			int counter = 0;
			if (!this.clientChan.connect(new InetSocketAddress(this.serverIP, this.serverPort))){
				
				while (!this.clientChan.finishConnect()){
					//System.out.print(".");
					counter++;
					if(counter > this.connectCount) {
						log.info("Attempt to connect with master by {} times but fail at last!",counter);
						this.clientChan.close();
						return;
					}
					Thread.sleep(100);
				}
			}
			log.info("Slave controller connected with Master controller at tcp://{} successfully!", this.serverIP+":"+this.serverPort);
			this.setConnected(true);
			String m = null;
			ByteBuffer writeBuf = null;
			while(!this.isStopReport()){
				m = msgQueue.poll();
				if(m==null||m.equals("")) {
					Thread.sleep(30);
					continue;
				}
				
				writeBuf = ByteBuffer.wrap(m.getBytes());
				while(writeBuf.hasRemaining()) {
					this.clientChan.write(writeBuf);
				}
				//Thread.sleep(2);
			}
			this.clientChan.close();
			this.setConnected(false);
		}catch(Exception ex) {
			log.info("Error in running FlowStatClient for sending stat msgs from slave to master: {}",ex.getMessage());
		}
	}
	
}
