package xipeng.statcontroller;

import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.Iterator;
import org.slf4j.Logger;

public class FlowStatServer implements Runnable{
	private static final int BUFSIZE = 65535;
	private static final int TIMEOUT = 3000; //select method wait time
	
	private Selector selector;
	private ServerSocketChannel listenChan;
	private boolean stopStatServer = false;
	
	public boolean isStopStatServer() {
		return stopStatServer;
	}

	public void setStopStatServer(boolean stopStatServer) {
		this.stopStatServer = stopStatServer;
	}

	private int serverPort = 6666;
	private Logger log = null;
	
	public FlowStatServer (int port, Logger log) throws Exception {
		this.log = log;
		if(port > 1024 && port < 65536) {
			this.serverPort = port;
		}		
	}
	
	@Override
	public void run() {
		try {
			//start server
			this.selector = Selector.open();
			this.listenChan = ServerSocketChannel.open();
			this.listenChan.socket().bind(new InetSocketAddress(this.serverPort));
			this.listenChan.configureBlocking(false);
			this.listenChan.register(this.selector, SelectionKey.OP_ACCEPT);
			
			ITcpFlowStatHandler handler = new ElephantFlowStatHandler(BUFSIZE,this.log);
			
			while (!this.stopStatServer){
	            //wait until channel is prepared for I/O operations
	            if (selector.select(TIMEOUT) == 0) {
	                continue;
	            }
	            //get keys
	            Iterator<SelectionKey> keyIter = selector.selectedKeys().iterator();
	            //get every key
	            while (keyIter.hasNext()){
	                SelectionKey key = keyIter.next(); 
	                //if interest type is accept
	                if (key.isAcceptable()){
	                    handler.handleAccept(key);
	                }
	                //if interest type is read
	                if (key.isReadable()){
	                	handler.handleRead(key);
	                }
	                //if interest type is valid and type is accept
	                if (key.isValid() && key.isWritable()) {
	                	handler.handleWrite(key);
	                }
	                //remove the key
	                keyIter.remove(); 
	            }
	        }
			
		}catch(Exception ex) {
			//log.info("Stat Server Error at master controller:{}",ex.getMessage());
			ex.printStackTrace();
		}
	}
}
